package com.tyler.primelink.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone as P
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.tyler.primelink.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Native implementations of every Tether tool. Each one is a public Android API or a standard intent —
 * no accessibility, no screenshots, no input injection.
 */
class Actions(private val c: Context) {
    private fun granted(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun need(p: String, what: String) { if (!granted(p)) throw RpcError("permission", "$what needs the ${p.substringAfterLast('.')} permission (open Tether > Permissions)") }
    private fun canStartUi() = Settings.canDrawOverlays(c)
    private fun needUi(what: String) { if (!canStartUi()) throw RpcError("permission", "$what needs 'Display over other apps' for Tether (Android blocks background apps from opening screens otherwise)") }
    private fun start(i: Intent) { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    fun isLocked(): Boolean = c.getSystemService(KeyguardManager::class.java).isDeviceLocked

    fun call(method: String, p: JSONObject): JSONObject = when (method) {
        "status" -> status()
        "find_contact" -> findContact(Policy.str(p, "query", 80)!!, Policy.int(p, "limit", 1, 20, 8))
        "call" -> placeCall(Policy.normalizeNumber(Policy.str(p, "number", 40)!!), Policy.str(p, "name", 60, required = false), Policy.bool(p, "speaker", false))
        "cancel_call" -> JSONObject().put("cancelled", CallCountdown.cancel("prime"))
        "hang_up" -> hangUp()
        "call_state" -> callState()
        "set_alarm" -> setAlarm(Policy.int(p, "hour", 0, 23), Policy.int(p, "minute", 0, 59), Policy.label(p), Policy.days(p), p.optBoolean("vibrate", true))
        "set_timer" -> setTimer(Policy.int(p, "seconds", 1, 86_400), Policy.label(p))
        "read_notifications" -> readNotifications(Policy.int(p, "limit", 1, 100, 20), Policy.str(p, "package", 200, required = false))
        "dismiss_notification" -> dismiss(Policy.str(p, "key", 500, required = false), p.optBoolean("all", false))
        "list_apps" -> listApps()
        "launch_app" -> launchApp(Policy.pkg(p))
        "open_url" -> openUrl(Policy.url(p))
        "flashlight" -> flashlight(p.optBoolean("on", true))
        "media" -> media(Policy.choice(p, "action", Policy.MEDIA))
        "volume" -> volume(Policy.choice(p, "stream", Policy.STREAMS), Policy.int(p, "level", 0, 100))
        "read_calendar" -> throw RpcError("not_implemented", "calendar arrives in v1.1")
        else -> throw RpcError("forbidden", "unknown tool $method")
    }

    // ---------------- status ----------------
    fun permissions(): JSONObject = JSONObject()
        .put("contacts", granted(Manifest.permission.READ_CONTACTS))
        .put("call_phone", granted(Manifest.permission.CALL_PHONE))
        .put("answer_calls", granted(Manifest.permission.ANSWER_PHONE_CALLS))
        .put("phone_state", granted(Manifest.permission.READ_PHONE_STATE))
        .put("post_notifications", granted(Manifest.permission.POST_NOTIFICATIONS))
        .put("notification_access", NotifListener.enabled(c))
        .put("display_over_apps", canStartUi())
        .put("incall_service_appop", incallServiceAllowed())
        .put("battery_unrestricted", c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName))

    private fun status(): JSONObject {
        val bm = c.getSystemService(BatteryManager::class.java)
        val st = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val am = c.getSystemService(AudioManager::class.java)
        val ringer = when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
        val dnd = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        return JSONObject()
            .put("app", "Tether").put("app_version", BuildConfig.VERSION_NAME)
            .put("battery_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", plugged != 0).put("network", net).put("ringer", ringer).put("dnd", dnd)
            .put("locked", isLocked()).put("screen_on", c.getSystemService(PowerManager::class.java).isInteractive)
            .put("call_state", runCatching { callState().getString("state") }.getOrDefault("unknown"))
            .put("pending_call", CallCountdown.pendingName())
            .put("call_audio", callAudio())
            .put("permissions", permissions())
            .put("tools", Policy.toolList())
    }

    // ---------------- contacts ----------------
    private fun findContact(q: String, limit: Int): JSONObject {
        need(Manifest.permission.READ_CONTACTS, "find_contact")
        val uri = Uri.withAppendedPath(P.CONTENT_FILTER_URI, Uri.encode(q))
        val byId = LinkedHashMap<Long, JSONObject>()
        c.contentResolver.query(uri, arrayOf(P.CONTACT_ID, P.DISPLAY_NAME_PRIMARY, P.NUMBER, P.TYPE, P.LABEL, P.STARRED, P.IS_SUPER_PRIMARY), null, null,
            "${P.STARRED} DESC, ${P.DISPLAY_NAME_PRIMARY} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val o = byId.getOrPut(id) { if (byId.size >= limit) return@use; JSONObject().put("name", cur.getString(1) ?: "").put("starred", cur.getInt(5) == 1).put("numbers", JSONArray()) }
                val num = cur.getString(2) ?: continue
                val type = P.getTypeLabel(c.resources, cur.getInt(3), cur.getString(4)).toString()
                val arr = o.getJSONArray("numbers")
                val norm = num.filter { it.isDigit() || it == '+' }
                if ((0 until arr.length()).none { arr.getJSONObject(it).getString("number").filter { ch -> ch.isDigit() || ch == '+' } == norm })
                    arr.put(JSONObject().put("number", num).put("type", type).put("primary", cur.getInt(6) == 1))
            }
        }
        return JSONObject().put("contacts", JSONArray(byId.values.toList()))
    }

    // ---------------- calls ----------------
    private fun placeCall(number: String, name: String?, speaker: Boolean): JSONObject {
        need(Manifest.permission.CALL_PHONE, "call")
        needUi("call")
        val fut = CallCountdown.begin(c, number, name, speaker)
        // The countdown is 3 s; give it a little longer, then report what happened.
        val outcome = runCatching { fut.get(CallCountdown.COUNTDOWN_MS + 4_000, TimeUnit.MILLISECONDS) }.getOrDefault("pending")
        return JSONObject().put("outcome", outcome).put("number", number).put("name", name ?: JSONObject.NULL).put("speaker", speaker)
            .put("speaker_guard", if (speaker) "off (speaker requested)" else if (incallServiceAllowed()) "incall_service" else "audio_manager_fallback (best effort)")
            .put("note", when (outcome) { "dialed" -> "dialing now"; "cancelled" -> "the user (or Prime) cancelled before dialing"; "pending" -> "countdown still showing"; else -> outcome })
    }

    @SuppressLint("MissingPermission")
    private fun hangUp(): JSONObject {
        val cancelled = CallCountdown.cancel("hang_up")
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            if (cancelled) return JSONObject().put("ended", false).put("cancelled_countdown", true)
            need(Manifest.permission.ANSWER_PHONE_CALLS, "hang_up")
        }
        @Suppress("DEPRECATION") val ended = c.getSystemService(TelecomManager::class.java).endCall()
        return JSONObject().put("ended", ended).put("cancelled_countdown", cancelled)
    }

    @SuppressLint("MissingPermission")
    private fun callState(): JSONObject {
        need(Manifest.permission.READ_PHONE_STATE, "call_state")
        val tm = c.getSystemService(TelephonyManager::class.java)
        val s = when (tm.callStateForSubscription) { TelephonyManager.CALL_STATE_RINGING -> "ringing"; TelephonyManager.CALL_STATE_OFFHOOK -> "in_call"; else -> "idle" }
        return JSONObject().put("state", s).put("pending_countdown", CallCountdown.pendingName() != null).put("audio", callAudio())
    }

    /** Is Telecom allowed to bind CallWatchService (MANAGE_ONGOING_CALLS app-op set to allow via adb)? */
    private fun incallServiceAllowed(): Boolean = runCatching {
        c.getSystemService(android.app.AppOpsManager::class.java)
            .unsafeCheckOpNoThrow("android:manage_ongoing_calls", android.os.Process.myUid(), c.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun callAudio(): JSONObject {
        val am = c.getSystemService(AudioManager::class.java)
        return JSONObject()
            .put("route", CallAudio.lastRoute ?: am.communicationDevice?.let { CallAudioFallback.devName(it.type) } ?: JSONObject.NULL)
            .put("speaker_guard", if (incallServiceAllowed()) "incall_service" else "audio_manager_fallback")
            .put("incall_service_bound", CallAudio.serviceBound)
            .put("last_action", CallAudio.lastAction ?: JSONObject.NULL)
    }

    // ---------------- alarms / timers (the Clock app's public intents, no UI) ----------------
    private fun setAlarm(h: Int, m: Int, label: String?, days: ArrayList<Int>?, vibrate: Boolean): JSONObject {
        needUi("set_alarm")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
        label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        days?.let { i.putExtra(AlarmClock.EXTRA_DAYS, it) }
        if (i.resolveActivity(c.packageManager) == null) throw RpcError("unavailable", "no clock app handles alarms")
        start(i)
        return JSONObject().put("set", true).put("time", "%02d:%02d".format(h, m)).put("label", label ?: JSONObject.NULL).put("days", days?.let { JSONArray(it) } ?: JSONObject.NULL)
    }
    private fun setTimer(sec: Int, label: String?): JSONObject {
        needUi("set_timer")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (i.resolveActivity(c.packageManager) == null) throw RpcError("unavailable", "no clock app handles timers")
        start(i)
        return JSONObject().put("set", true).put("seconds", sec).put("label", label ?: JSONObject.NULL)
    }

    // ---------------- notifications (NotificationListenerService; text is returned, never stored) ----------------
    private fun listener(): NotifListener = NotifListener.instance
        ?: throw RpcError("permission", if (NotifListener.enabled(c)) "notification listener not connected yet; try again" else "turn on Notification access for Tether")
    private fun readNotifications(limit: Int, pkg: String?): JSONObject {
        val l = listener()
        val pm = c.packageManager
        val list = l.activeNotifications.orEmpty().asSequence()
            .filter { it.packageName != c.packageName && (pkg == null || it.packageName == pkg) }
            .sortedByDescending { it.postTime }.take(limit)
            .map { sbn ->
                val ex = sbn.notification.extras
                val text = (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString()
                JSONObject().put("key", sbn.key).put("package", sbn.packageName)
                    .put("app", runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName))
                    .put("title", ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.take(200) ?: "")
                    .put("text", text?.take(1000) ?: "").put("time", sbn.postTime)
                    .put("ongoing", sbn.isOngoing).put("clearable", sbn.isClearable)
            }.toList()
        return JSONObject().put("notifications", JSONArray(list)).put("count", list.size)
    }
    private fun dismiss(key: String?, all: Boolean): JSONObject {
        val l = listener()
        if (all) { l.cancelAllNotifications(); return JSONObject().put("dismissed", "all_clearable") }
        val k = key ?: throw RpcError("bad_request", "give 'key' (from read_notifications) or all=true")
        if (l.activeNotifications.orEmpty().none { it.key == k }) throw RpcError("not_found", "no such notification")
        l.cancelNotification(k)
        return JSONObject().put("dismissed", k)
    }

    // ---------------- apps / urls ----------------
    private fun launchables() = c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    private fun listApps(): JSONObject {
        val pm = c.packageManager
        val apps = launchables().map { JSONObject().put("package", it.activityInfo.packageName).put("label", it.loadLabel(pm).toString()) }
            .distinctBy { it.getString("package") }.sortedBy { it.getString("label").lowercase() }
        return JSONObject().put("apps", JSONArray(apps))
    }
    private fun launchApp(pkg: String): JSONObject {
        needUi("launch_app")
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: throw RpcError("not_found", "no launchable app $pkg")
        start(i); return JSONObject().put("launched", pkg)
    }
    private fun openUrl(url: String): JSONObject {
        needUi("open_url")
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        return JSONObject().put("opened", Uri.parse(url).host)
    }

    // ---------------- v1.1 ----------------
    private fun flashlight(on: Boolean): JSONObject {
        val cm = c.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: throw RpcError("unavailable", "no flash")
        cm.setTorchMode(id, on); return JSONObject().put("flashlight", on)
    }
    private fun media(action: String): JSONObject {
        val msm = c.getSystemService(MediaSessionManager::class.java)
        val ctrl = runCatching { msm.getActiveSessions(ComponentName(c, NotifListener::class.java)) }.getOrElse { throw RpcError("permission", "media control needs Notification access") }
            .firstOrNull() ?: throw RpcError("not_found", "nothing is playing")
        val t = ctrl.transportControls
        when (action) {
            "play" -> t.play(); "pause" -> t.pause(); "next" -> t.skipToNext(); "previous" -> t.skipToPrevious(); "stop" -> t.stop()
            "toggle" -> if (ctrl.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) t.pause() else t.play()
        }
        return JSONObject().put("app", ctrl.packageName).put("action", action)
    }
    private fun volume(stream: String, pct: Int): JSONObject {
        val am = c.getSystemService(AudioManager::class.java)
        val s = when (stream) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; "notification" -> AudioManager.STREAM_NOTIFICATION; else -> AudioManager.STREAM_MUSIC }
        val max = am.getStreamMaxVolume(s); val v = Math.round(max * pct / 100f)
        runCatching { am.setStreamVolume(s, v, 0) }.getOrElse { throw RpcError("permission", "Android refused (Do Not Disturb access needed for ring changes)") }
        return JSONObject().put("stream", stream).put("level", v).put("max", max)
    }
}
