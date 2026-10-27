package com.tyler.primelink.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LinkedPc(val pcId: String, val name: String, val pub: ByteArray, val pairedAt: Long, val lastSeen: Long)

/**
 * Phone identity (X25519 secret) and the Prime command token, both wrapped by an Android Keystore AES-GCM key
 * (never stored in the clear), plus paired PCs and toggles. Same storage scheme as Emit's link.
 */
class LinkStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("plink", Context.MODE_PRIVATE)

    var enabled: Boolean get() = p.getBoolean("enabled", false); set(v) { p.edit().putBoolean("enabled", v).apply() }

    val phoneId: String get() = p.getString("phone_id", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        .also { p.edit().putString("phone_id", it).apply() }

    @Volatile private var sk: ByteArray? = null
    @Synchronized fun secretKey(): ByteArray {
        sk?.let { return it }
        val wrapped = p.getString("sk", null)
        val key = if (wrapped != null) unwrap(unb64(wrapped)) else LinkCrypto.genSecret().also { p.edit().putString("sk", b64(wrap(it))).apply() }
        sk = key; return key
    }

    @Volatile private var tok: ByteArray? = null
    /** The Prime command token (created on first use). */
    @Synchronized fun token(): ByteArray {
        tok?.let { return it }
        val wrapped = p.getString("tok", null)
        val t = if (wrapped != null) unwrap(unb64(wrapped)) else LinkCrypto.random(32).also { p.edit().putString("tok", b64(wrap(it))).putLong("tok_at", System.currentTimeMillis()).apply() }
        tok = t; return t
    }
    @Volatile private var cmd: ByteArray? = null
    fun cmdKey(): ByteArray = cmd ?: LinkCrypto.cmdKey(token()).also { cmd = it }
    /** New token: every paired PC is cut off until it pairs again. */
    @Synchronized fun rotateToken() {
        val t = LinkCrypto.random(32)
        p.edit().putString("tok", b64(wrap(t))).putLong("tok_at", System.currentTimeMillis()).apply()
        tok = t; cmd = null
    }
    val tokenCreatedAt: Long get() = p.getLong("tok_at", 0)

    fun pcs(): List<LinkedPc> {
        val a = JSONArray(p.getString("pcs", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            LinkedPc(it.getString("id"), it.getString("name"), unb64(it.getString("pub")), it.optLong("paired"), it.optLong("seen")) }
    }
    private fun savePcs(l: List<LinkedPc>) = p.edit().putString("pcs", JSONArray(l.map {
        JSONObject().put("id", it.pcId).put("name", it.name).put("pub", b64(it.pub)).put("paired", it.pairedAt).put("seen", it.lastSeen) }).toString()).apply()
    @Synchronized fun addPc(id: String, name: String, pub: ByteArray) = savePcs(pcs().filter { it.pcId != id } + LinkedPc(id, name, pub, System.currentTimeMillis(), 0))
    @Synchronized fun revoke(id: String) = savePcs(pcs().filter { it.pcId != id })
    @Synchronized fun touch(id: String) = savePcs(pcs().map { if (it.pcId == id) LinkedPc(it.pcId, it.name, it.pub, it.pairedAt, System.currentTimeMillis()) else it })
    fun pubFor(id: String): ByteArray? = pcs().firstOrNull { it.pcId == id }?.pub
    fun nameFor(id: String): String = pcs().firstOrNull { it.pcId == id }?.name ?: "PC"

    private fun ksKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun wrap(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, ksKey())
        return c.iv + c.doFinal(b)
    }
    private fun unwrap(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, ksKey(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return c.doFinal(b, 12, b.size - 12)
    }
    companion object { private const val ALIAS = "primelink_link_v1" }
}

/** Metadata-only audit log (tool, coarse args, outcome). Never message text, notification text, URL paths or numbers. */
class Audit(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("plink_audit", Context.MODE_PRIVATE)
    class Entry(val time: Long, val pc: String, val tool: String, val args: String, val outcome: String)
    @Synchronized fun add(pc: String, tool: String, args: String, outcome: String) {
        val a = JSONArray(p.getString("log", "[]"))
        val list = (0 until a.length()).map { a.getJSONObject(it) }.takeLast(MAX - 1) +
            JSONObject().put("t", System.currentTimeMillis()).put("pc", pc.take(40)).put("tool", tool.take(40)).put("args", args.take(80)).put("out", outcome.take(60))
        p.edit().putString("log", JSONArray(list).toString()).apply()
    }
    fun entries(): List<Entry> { val a = JSONArray(p.getString("log", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.optLong("t"), it.optString("pc"), it.optString("tool"), it.optString("args"), it.optString("out")) }.reversed() }
    fun clear() = p.edit().remove("log").apply()
    companion object { const val MAX = 200 }
}
