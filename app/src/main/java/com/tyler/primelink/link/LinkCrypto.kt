package com.tyler.primelink.link

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Same construction as Emit's link (X25519 + HKDF-SHA256 + ChaCha20-Poly1305, SAS pairing with commitment),
 * with Tether's own domain labels ("plink-...") so keys/transcripts can never be confused with Emit's.
 * Byte-identical with the PC app plink/crypto.py (checked by CryptoVectorsTest).
 */
object LinkCrypto {
    private val rng = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun genSecret(): ByteArray = random(32)
    fun publicKey(sk: ByteArray) = ByteArray(32).also { X25519.generatePublicKey(sk, 0, it, 0) }
    fun dh(sk: ByteArray, pk: ByteArray): ByteArray {
        require(pk.size == 32) { "bad public key" }
        val out = ByteArray(32)
        X25519.scalarMult(sk, 0, pk, 0, out, 0)
        require(out.any { it != 0.toByte() }) { "low-order public key" }
        return out
    }
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, n: Int): ByteArray =
        ByteArray(n).also { HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, salt, info)) }.generateBytes(it, 0, n) }
    fun hmac(key: ByteArray, msg: ByteArray): ByteArray = HMac(SHA256Digest()).run {
        init(KeyParameter(key)); update(msg, 0, msg.size); ByteArray(32).also { doFinal(it, 0) } }
    fun lp(b: ByteArray) = byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()) + b
    fun eq(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    private fun s(x: String) = x.toByteArray()
    fun pairCommit(pubPh: ByteArray, pubPc: ByteArray, nb: ByteArray) = sha256(s("plink-commit-v1"), pubPh, pubPc, nb)
    fun pairSas(pubPc: ByteArray, pubPh: ByteArray, na: ByteArray, nb: ByteArray): String {
        val h = sha256(s("plink-sas-v1"), pubPc, pubPh, na, nb)
        val v = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return "%06d".format(v % 1_000_000)
    }
    fun pairConfirmKey(shared: ByteArray, pubPc: ByteArray, pubPh: ByteArray, na: ByteArray, nb: ByteArray) =
        hkdf(shared, sha256(s("plink-pair-v1"), pubPc, pubPh, na, nb), s("plink-pair-confirm"), 32)
    fun transcript(helloPc: ByteArray, helloPh: ByteArray) = sha256(s("plink-hs-v1"), lp(helloPc), lp(helloPh))
    /** (pc->phone, phone->pc) */
    fun sessionKeys(ee: ByteArray, ss: ByteArray, th: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = hkdf(ee + ss, th, s("plink-session-v1"), 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    // --- Prime command token: generated on the phone, handed to the PC once inside pairing (sealed under the
    //     pairing confirm key), then every command carries an HMAC with it. Rotating it on the phone cuts the PC off. ---
    private fun tokenWrapKey(ck: ByteArray) = hkdf(ck, ByteArray(0), s("plink-token-wrap"), 32)
    fun sealToken(ck: ByteArray, token: ByteArray): ByteArray = aead(true, tokenWrapKey(ck), ByteArray(12), token)
    fun openToken(ck: ByteArray, sealed: ByteArray): ByteArray = aead(false, tokenWrapKey(ck), ByteArray(12), sealed)
    fun cmdKey(token: ByteArray) = hkdf(token, ByteArray(0), s("plink-cmd-v1"), 32)
    /** MAC over the exact request bytes: id, method, ts, params-json-string. */
    fun cmdMac(cmdKey: ByteArray, id: Long, method: String, ts: Long, params: String): ByteArray =
        hmac(cmdKey, s("plink-cmd-v1") + lp(s(id.toString())) + lp(s(method)) + lp(s(ts.toString())) + lp(s(params)))

    fun aead(enc: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val c = ChaCha20Poly1305(); c.init(enc, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(input.size))
        var n = c.processBytes(input, 0, input.size, out, 0); n += c.doFinal(out, n)
        return if (n == out.size) out else out.copyOf(n)
    }
}

/** One direction: ChaCha20-Poly1305, nonce = 4 zero bytes || 8-byte BE counter, strictly sequential. */
class AeadStream(private val key: ByteArray) {
    var counter = 0L; private set
    private fun nonce() = ByteArray(12).also { n -> for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte() }
    @Synchronized private fun run(enc: Boolean, input: ByteArray): ByteArray {
        if (counter == Long.MAX_VALUE) throw IllegalStateException("nonce space exhausted")
        val out = LinkCrypto.aead(enc, key, nonce(), input)
        counter++
        return out
    }
    fun seal(pt: ByteArray) = run(true, pt)
    fun open(ct: ByteArray) = run(false, ct)
}
