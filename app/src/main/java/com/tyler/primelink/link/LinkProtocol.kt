package com.tyler.primelink.link

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

class ProtocolException(msg: String) : IOException(msg)

/** Length-prefixed frames (4-byte BE length). Same as plink/protocol.py. Commands are small, so the cap is 1 MiB. */
class FrameIO(input: InputStream, output: OutputStream) {
    private val inp = DataInputStream(input.buffered())
    private val out = DataOutputStream(output.buffered())
    fun read(): ByteArray {
        val n = inp.readInt()
        if (n < 0 || n > MAX) throw ProtocolException("frame too large")
        return ByteArray(n).also { inp.readFully(it) }
    }
    @Synchronized fun write(b: ByteArray) { if (b.size > MAX) throw ProtocolException("frame too large"); out.writeInt(b.size); out.write(b); out.flush() }
    companion object { const val MAX = 1024 * 1024 }
}

fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
fun json(vararg kv: Pair<String, Any?>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
internal fun JSONObject.bytes() = toString().toByteArray()

class SecureChannel(private val io: FrameIO, sendKey: ByteArray, recvKey: ByteArray) {
    private val tx = AeadStream(sendKey); private val rx = AeadStream(recvKey)
    fun send(o: JSONObject) = synchronized(this) { io.write(tx.seal(o.bytes())) }
    fun recv(): JSONObject = JSONObject(String(rx.open(io.read())))
}

private fun expect(o: JSONObject, t: String): JSONObject {
    if (o.optString("t") == "err") throw ProtocolException(o.optString("msg", "peer error"))
    if (o.optString("t") != t) throw ProtocolException("expected $t got ${o.optString("t")}")
    return o
}

/** Phone = responder. Mirrors pair_responder / session_responder in plink/protocol.py (same flow as Emit). */
object PhoneProtocol {
    const val VERSION = 1
    class PairedPc(val pcId: String, val name: String, val pub: ByteArray)

    /**
     * [firstFrame] = the already-read pair1 frame. [showCode] displays the SAS; [allow] blocks until the user taps
     * ALLOW / DENY on the phone. On allow, pair6 also carries the Prime command [token], sealed under the confirm key.
     */
    fun pairResponder(io: FrameIO, firstFrame: ByteArray, phoneId: String, phoneName: String, sk: ByteArray, token: ByteArray,
                      showCode: (code: String, pcName: String) -> Unit, allow: (pcName: String) -> Boolean): PairedPc {
        val pubPh = LinkCrypto.publicKey(sk)
        val m1 = expect(JSONObject(String(firstFrame)), "pair1")
        val pubPc = unb64(m1.getString("pub")); val pcName = m1.optString("name", "PC").take(40)
        val nb = LinkCrypto.random(16)
        io.write(json("t" to "pair2", "phone_id" to phoneId, "name" to phoneName, "pub" to b64(pubPh),
            "commit" to b64(LinkCrypto.pairCommit(pubPh, pubPc, nb))).bytes())
        val na = unb64(expect(JSONObject(String(io.read())), "pair3").getString("na"))
        io.write(json("t" to "pair4", "nb" to b64(nb)).bytes())
        showCode(LinkCrypto.pairSas(pubPc, pubPh, na, nb), pcName)
        val m5 = expect(JSONObject(String(io.read())), "pair5")
        val ck = LinkCrypto.pairConfirmKey(LinkCrypto.dh(sk, pubPc), pubPc, pubPh, na, nb)
        if (!LinkCrypto.eq(unb64(m5.getString("mac")), LinkCrypto.hmac(ck, "pc-ok".toByteArray()))) throw ProtocolException("pc confirm MAC bad")
        if (!allow(pcName)) { io.write(json("t" to "err", "msg" to "denied on phone").bytes()); throw ProtocolException("denied") }
        io.write(json("t" to "pair6", "mac" to b64(LinkCrypto.hmac(ck, "phone-ok".toByteArray())),
            "tok" to b64(LinkCrypto.sealToken(ck, token))).bytes())
        return PairedPc(m1.getString("pc_id"), pcName, pubPc)
    }

    fun sessionResponder(io: FrameIO, firstFrame: ByteArray, phoneId: String, sk: ByteArray,
                         lookup: (String) -> ByteArray?, ephSk: ByteArray = LinkCrypto.genSecret()): Pair<SecureChannel, String> {
        val m1 = expect(JSONObject(String(firstFrame)), "hello")
        val pcId = m1.getString("pc_id")
        if (m1.optInt("v", -1) != VERSION) { io.write(json("t" to "err", "msg" to "unsupported version").bytes()); throw ProtocolException("bad version") }
        val pcPub = lookup(pcId) ?: run { io.write(json("t" to "err", "msg" to "not paired").bytes()); throw ProtocolException("unknown pc") }
        val raw2 = json("t" to "hello_ok", "v" to VERSION, "phone_id" to phoneId, "e" to b64(LinkCrypto.publicKey(ephSk)),
            "n" to b64(LinkCrypto.random(16))).bytes()
        io.write(raw2)
        val th = LinkCrypto.transcript(firstFrame, raw2)
        val (kPc2Ph, kPh2Pc) = LinkCrypto.sessionKeys(LinkCrypto.dh(ephSk, unb64(m1.getString("e"))), LinkCrypto.dh(sk, pcPub), th)
        val ch = SecureChannel(io, kPh2Pc, kPc2Ph)
        val a = ch.recv()
        if (a.optString("t") != "auth" || !LinkCrypto.eq(unb64(a.optString("th", "")), th)) throw ProtocolException("auth expected")
        ch.send(json("t" to "auth_ok"))
        return ch to pcId
    }
}

/**
 * Checks the per-command Prime token MAC and freshness. Requests look like
 * {"id":n,"m":method,"ps":"<params json string>","ts":epoch_ms,"mac":b64}. Pure JVM (unit-tested).
 */
class CommandGate(private val cmdKey: () -> ByteArray?, private val now: () -> Long = System::currentTimeMillis) {
    private var lastId = 0L
    sealed class Result { class Ok(val id: Long, val method: String, val params: JSONObject) : Result(); class Bad(val id: Any?, val code: String, val msg: String) : Result() }
    @Synchronized fun check(req: JSONObject): Result {
        val rid = req.opt("id")
        val id = req.optLong("id", -1); val m = req.optString("m"); val ps = req.optString("ps", ""); val ts = req.optLong("ts", 0)
        val key = cmdKey() ?: return Result.Bad(rid, "auth_fail", "no Prime token on the phone; pair again")
        val mac = runCatching { unb64(req.optString("mac")) }.getOrNull() ?: return Result.Bad(rid, "auth_fail", "missing command MAC")
        if (!LinkCrypto.eq(mac, LinkCrypto.cmdMac(key, id, m, ts, ps))) return Result.Bad(rid, "auth_fail", "Prime token rejected (rotated on phone?). Pair again.")
        if (kotlin.math.abs(now() - ts) > MAX_SKEW_MS) return Result.Bad(rid, "stale", "command timestamp too old or PC clock off")
        if (id <= lastId) return Result.Bad(rid, "replay", "command id not increasing")
        lastId = id
        val p = runCatching { if (ps.isEmpty()) JSONObject() else JSONObject(ps) }.getOrNull() ?: return Result.Bad(rid, "bad_request", "params not JSON")
        return Result.Ok(id, m, p)
    }
    companion object { const val MAX_SKEW_MS = 120_000L }
}
