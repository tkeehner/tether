package com.tyler.primelink.actions

import org.json.JSONArray
import org.json.JSONObject

class RpcError(val code: String, msg: String) : Exception(msg)

/**
 * The ONLY things a paired PC can ask the phone to do. Every one is a native Android API or intent; there is no
 * screen reading, tapping, typing or shell. Anything not listed is refused. Pure JVM so it's unit-tested.
 * Keep in sync with plink/tools.py (the PC validates too).
 */
object Policy {
    enum class Tier { V1, V1_1 }
    class Tool(val name: String, val lockedOk: Boolean, val tier: Tier = Tier.V1)

    val TOOLS: Map<String, Tool> = listOf(
        Tool("status", lockedOk = true),
        Tool("find_contact", lockedOk = true),      // needed to place a call while locked
        Tool("call", lockedOk = true),              // 3 s "Calling… cancel" countdown before dialing
        Tool("cancel_call", lockedOk = true),
        Tool("hang_up", lockedOk = true),
        Tool("call_state", lockedOk = true),
        Tool("set_alarm", lockedOk = true),
        Tool("set_timer", lockedOk = true),
        Tool("read_notifications", lockedOk = true),
        Tool("dismiss_notification", lockedOk = true),
        Tool("list_apps", lockedOk = true),
        Tool("launch_app", lockedOk = false),
        Tool("open_url", lockedOk = false),
        // v1.1 (scaffolded; flashlight/media/volume work, calendar answers not_implemented)
        Tool("flashlight", lockedOk = true, tier = Tier.V1_1),
        Tool("media", lockedOk = true, tier = Tier.V1_1),
        Tool("volume", lockedOk = true, tier = Tier.V1_1),
        Tool("read_calendar", lockedOk = false, tier = Tier.V1_1),
    ).associateBy { it.name }

    /** Throws unless [method] is allowed in the current lock state. */
    fun gate(method: String, locked: Boolean) {
        val t = TOOLS[method] ?: throw RpcError("forbidden", "unknown or disallowed tool '$method'")
        if (locked && !t.lockedOk) throw RpcError("locked", "'$method' needs the phone unlocked")
    }

    // ---------- argument validation (all pure) ----------
    private val EMERGENCY = setOf("911", "112", "999", "000", "110", "119", "118", "08", "933")

    /** Plain dialable number only: optional leading '+', 3..15 digits. No '*' or '#' (blocks MMI/USSD codes). */
    fun normalizeNumber(raw: String): String {
        val s = raw.trim()
        if (s.any { it == '*' || it == '#' || it == ',' || it == ';' }) throw RpcError("bad_request", "number may not contain * # , ; (no USSD/MMI codes)")
        val plus = s.startsWith("+")
        val cleaned = s.removePrefix("+").filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }
        if (cleaned.isEmpty() || !cleaned.all { it in '0'..'9' }) throw RpcError("bad_request", "number must be digits")
        if (cleaned.length !in 3..15) throw RpcError("bad_request", "number length must be 3-15 digits")
        if (!plus && cleaned in EMERGENCY) throw RpcError("forbidden", "Tether never dials emergency numbers; call them yourself")
        return (if (plus) "+" else "") + cleaned
    }

    fun int(p: JSONObject, k: String, lo: Int, hi: Int, def: Int? = null): Int {
        if (!p.has(k)) return def ?: throw RpcError("bad_request", "'$k' is required")
        val v = p.opt(k)
        val n = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null } ?: throw RpcError("bad_request", "'$k' must be a number")
        if (n != Math.floor(n) || n < lo || n > hi) throw RpcError("bad_request", "'$k' must be an integer $lo..$hi")
        return n.toInt()
    }
    fun str(p: JSONObject, k: String, max: Int, required: Boolean = true): String? {
        val s = p.optString(k, "").trim()
        if (s.isEmpty()) { if (required) throw RpcError("bad_request", "'$k' is required"); return null }
        if (s.length > max) throw RpcError("bad_request", "'$k' longer than $max")
        return s
    }
    fun label(p: JSONObject) = str(p, "label", 60, required = false)?.filter { it >= ' ' }

    /** Calendar.SUNDAY=1 … SATURDAY=7. */
    fun days(p: JSONObject): ArrayList<Int>? {
        val a = p.optJSONArray("days") ?: return null
        val out = ArrayList<Int>()
        for (i in 0 until a.length()) { val d = a.optInt(i, -1); if (d !in 1..7) throw RpcError("bad_request", "days must be 1 (Sun) .. 7 (Sat)"); if (d !in out) out += d }
        return out.ifEmpty { null }
    }

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    fun pkg(p: JSONObject): String {
        val s = str(p, "package", 200)!!
        if (!PKG.matches(s)) throw RpcError("bad_request", "bad package name")
        return s
    }

    /** http(s) only, with a host, no credentials in the URL. */
    fun url(p: JSONObject): String {
        val s = str(p, "url", 2000)!!
        val u = runCatching { java.net.URI(s) }.getOrNull() ?: throw RpcError("bad_request", "not a URL")
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw RpcError("forbidden", "only http/https URLs")
        if (u.host.isNullOrBlank()) throw RpcError("bad_request", "URL needs a host")
        if (u.rawUserInfo != null) throw RpcError("forbidden", "no user:password in URLs")
        return s
    }

    /** Strict boolean: absent -> [def]; anything but a JSON true/false is refused. */
    fun bool(p: JSONObject, k: String, def: Boolean): Boolean {
        if (!p.has(k) || p.isNull(k)) return def
        return p.opt(k) as? Boolean ?: throw RpcError("bad_request", "'$k' must be true or false")
    }

    val MEDIA = setOf("play", "pause", "toggle", "next", "previous", "stop")
    val STREAMS = setOf("media", "ring", "alarm", "notification")
    fun choice(p: JSONObject, k: String, allowed: Set<String>): String {
        val s = str(p, k, 40)!!.lowercase()
        if (s !in allowed) throw RpcError("bad_request", "'$k' must be one of ${allowed.sorted()}")
        return s
    }

    /** Coarse, content-free audit description of a call (never message/notification text, URL paths or numbers' digits). */
    fun auditArgs(method: String, p: JSONObject): String = when (method) {
        "call" -> "number=" + p.optString("number").let { n -> if (n.length > 4) "…" + n.takeLast(2) else "?" }
        "launch_app" -> "pkg=" + p.optString("package").take(80)
        "open_url" -> "host=" + (runCatching { java.net.URI(p.optString("url")).host }.getOrNull() ?: "?")
        "set_alarm" -> "%02d:%02d".format(p.optInt("hour"), p.optInt("minute"))
        "set_timer" -> "${p.optInt("seconds")}s"
        "read_notifications" -> "limit=${p.optInt("limit", 20)}"
        "dismiss_notification" -> if (p.optBoolean("all")) "all" else "one"
        "find_contact" -> "query_len=${p.optString("query").length}"
        "media" -> p.optString("action").take(10)
        "volume" -> p.optString("stream").take(12)
        "flashlight" -> if (p.optBoolean("on")) "on" else "off"
        else -> ""
    }

    fun toolList(): JSONArray = JSONArray(TOOLS.values.map { JSONObject().put("name", it.name).put("locked_ok", it.lockedOk).put("tier", it.tier.name) })
}
