package com.tyler.primelink.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telephony.TelephonyManager

/**
 * Best-effort speaker suppression when [CallWatchService] is NOT bound. Asks AudioManager for the earpiece as the
 * communication device. During a cellular call the phone process owns the audio mode and Android gives its route
 * priority, so this may be ignored — the result (accepted / ignored) is recorded in [CallAudio.lastAction] so
 * call_state tells the truth. Always clears its request when the call ends so it never lingers.
 */
object CallAudioFallback {
    private const val POLL_MS = 300L

    fun start(c: Context, placed: CallAudio.Placed) {
        if (placed.speaker) return
        val app = c.applicationContext
        Thread({ run(app, placed) }, "plink-speaker-guard").apply { isDaemon = true }.start()
    }

    @Suppress("DEPRECATION")
    private fun run(c: Context, placed: CallAudio.Placed) {
        val am = c.getSystemService(AudioManager::class.java)
        val tm = c.getSystemService(TelephonyManager::class.java)
        val canReadState = c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        var sawCall = false; var requested = false; var corrections = 0; var firstFixAt: Long? = null
        try {
            Thread.sleep(1_500)
            while (true) {
                if (CallAudio.serviceBound) return // the InCallService is handling it properly
                val now = System.currentTimeMillis()
                val state = if (canReadState) runCatching { tm.callStateForSubscription }.getOrDefault(TelephonyManager.CALL_STATE_OFFHOOK) else TelephonyManager.CALL_STATE_OFFHOOK
                if (state == TelephonyManager.CALL_STATE_OFFHOOK) sawCall = true
                else if (sawCall || now - placed.at > 20_000) return // call over (or never started)
                // No "connected" signal without the InCallService: stop 8 s after the first fix, or at the hard cap.
                val activeProxy = firstFixAt
                if (!CallAudio.inGuard(now, placed.at, activeProxy, corrections)) return
                val dev = am.communicationDevice
                CallAudio.lastRoute = dev?.let { devName(it.type) }
                if (dev?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    val target = pick(am.availableCommunicationDevices)
                    if (target != null) {
                        val ok = am.setCommunicationDevice(target); requested = requested || ok
                        corrections++; if (firstFixAt == null) firstFixAt = now
                        Thread.sleep(400)
                        val after = am.communicationDevice?.type
                        CallAudio.lastAction = "fallback: asked for ${devName(target.type)}, accepted=$ok, now=${after?.let(::devName)}" +
                            if (after == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) " (ignored by system; enable the InCallService app-op)" else ""
                        if (after == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return // the platform said no; don't keep poking
                    }
                }
                Thread.sleep(POLL_MS)
            }
        } catch (_: InterruptedException) {
        } finally {
            if (requested) runCatching { am.clearCommunicationDevice() }
        }
    }

    private fun pick(devs: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val order = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        return order.firstNotNullOfOrNull { t -> devs.firstOrNull { it.type == t } }
    }

    fun devName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"; AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth"
        else -> "device_type_$t"
    }
}
