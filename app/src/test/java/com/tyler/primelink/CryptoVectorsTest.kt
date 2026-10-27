package com.tyler.primelink

import com.tyler.primelink.link.AeadStream
import com.tyler.primelink.link.LinkCrypto
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Same file as pc/tests/vectors/crypto_vectors.json (RFC KATs + Python-generated interop values). */
class CryptoVectorsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("crypto_vectors.json")!!.readText())
    private val i = v.getJSONObject("interop")

    @Test fun rfcKats() {
        val x = v.getJSONObject("x25519_rfc7748")
        assertEquals(x.getString("alice_pk"), hx(LinkCrypto.publicKey(h(x.getString("alice_sk")))))
        assertEquals(x.getString("shared"), hx(LinkCrypto.dh(h(x.getString("alice_sk")), h(x.getString("bob_pk")))))
        val a = v.getJSONObject("chacha20poly1305_rfc8439"); val pt = h(a.getString("pt"))
        val c = ChaCha20Poly1305(); c.init(true, AEADParameters(KeyParameter(h(a.getString("key"))), 128, h(a.getString("nonce")), h(a.getString("aad"))))
        val out = ByteArray(c.getOutputSize(pt.size)); var n = c.processBytes(pt, 0, pt.size, out, 0); n += c.doFinal(out, n)
        assertEquals(a.getString("ct_tag"), hx(out))
        val k = v.getJSONObject("hkdf_rfc5869_case1")
        assertEquals(k.getString("okm"), hx(LinkCrypto.hkdf(h(k.getString("ikm")), h(k.getString("salt")), h(k.getString("info")), k.getInt("len"))))
        val m = v.getJSONObject("hmac_sha256_rfc4231_case1")
        assertEquals(m.getString("mac"), hx(LinkCrypto.hmac(h(m.getString("key")), h(m.getString("data")))))
    }

    @Test fun pairingMatchesPython() {
        val pc = h(i.getString("pub_pc")); val ph = h(i.getString("pub_ph"))
        assertEquals(i.getString("pub_pc"), hx(LinkCrypto.publicKey(h(i.getString("sk_pc")))))
        assertEquals(i.getString("pub_ph"), hx(LinkCrypto.publicKey(h(i.getString("sk_ph")))))
        val ss = LinkCrypto.dh(h(i.getString("sk_ph")), pc); assertEquals(i.getString("static_shared"), hx(ss))
        assertEquals(i.getString("commit"), hx(LinkCrypto.pairCommit(ph, pc, h(i.getString("nb")))))
        assertEquals(i.getString("sas"), LinkCrypto.pairSas(pc, ph, h(i.getString("na")), h(i.getString("nb"))))
        val ck = LinkCrypto.pairConfirmKey(ss, pc, ph, h(i.getString("na")), h(i.getString("nb")))
        assertEquals(i.getString("confirm_key"), hx(ck))
        assertEquals(i.getString("confirm_pc_ok"), hx(LinkCrypto.hmac(ck, "pc-ok".toByteArray())))
        assertEquals(i.getString("confirm_phone_ok"), hx(LinkCrypto.hmac(ck, "phone-ok".toByteArray())))
        assertEquals(i.getString("token_sealed"), hx(LinkCrypto.sealToken(ck, h(i.getString("token")))))
        assertEquals(i.getString("token"), hx(LinkCrypto.openToken(ck, h(i.getString("token_sealed")))))
    }

    @Test fun sessionAndCommandsMatchPython() {
        val th = LinkCrypto.transcript(i.getString("hello").toByteArray(), i.getString("hello_ok").toByteArray())
        assertEquals(i.getString("transcript"), hx(th))
        val ee = LinkCrypto.dh(h(i.getString("e_pc")), LinkCrypto.publicKey(h(i.getString("e_ph")))); assertEquals(i.getString("ee"), hx(ee))
        val (k1, k2) = LinkCrypto.sessionKeys(ee, h(i.getString("static_shared")), th)
        assertEquals(i.getString("k_pc2ph"), hx(k1)); assertEquals(i.getString("k_ph2pc"), hx(k2))
        assertEquals("{\"t\":\"auth\"}", String(AeadStream(k1).open(h(i.getString("first_frame")))))
        val ck = LinkCrypto.cmdKey(h(i.getString("token"))); assertEquals(i.getString("cmd_key"), hx(ck))
        assertEquals(i.getString("cmd_mac"), hx(LinkCrypto.cmdMac(ck, i.getLong("cmd_id"), i.getString("cmd_method"), i.getLong("cmd_ts"), i.getString("cmd_ps"))))
    }

    @Test fun separatedFromEmitLabels() =
        assertNotEquals(i.getString("commit"), hx(LinkCrypto.sha256("msms-commit-v1".toByteArray(), h(i.getString("pub_ph")), h(i.getString("pub_pc")), h(i.getString("nb")))))
}
