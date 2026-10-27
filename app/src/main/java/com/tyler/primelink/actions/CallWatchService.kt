package com.tyler.primelink.actions

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

/**
 * Non-UI InCallService: no screen, Samsung's dialer stays the phone app. Telecom binds it for every call ONLY when
 * the MANAGE_ONGOING_CALLS app-op is allowed for Tether:
 *     adb shell appops set com.tyler.primelink MANAGE_ONGOING_CALLS allow
 * (undo: `... MANAGE_ONGOING_CALLS default`). Without that it is never bound and [CallAudioFallback] is used.
 *
 * It only touches calls Prime placed (see [CallAudio.matchPrimeCall]) and only moves the route OFF the speaker
 * during dialing + the first seconds after connect. It never answers, holds, records or ends anything.
 */
class CallWatchService : InCallService() {
    private val h = Handler(Looper.getMainLooper())
    private class Guard(val call: Call, val placed: CallAudio.Placed) { var activeAt: Long? = null; var corrections = 0 }
    private var guard: Guard? = null

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val g = guard ?: return
            if (g.call !== call) return
            if (state == Call.STATE_ACTIVE && g.activeAt == null) {
                g.activeAt = System.currentTimeMillis()
                // Samsung/Telecom can flip the route a beat after connect; look again a few times inside the window.
                enforce("active")
                for (d in longArrayOf(400, 1_200, 2_500, 5_000)) h.postDelayed({ enforce("active+$d") }, d)
            }
        }
    }

    override fun onCreate() { super.onCreate(); CallAudio.serviceBound = true; CallAudio.lastAction = "incall_service bound" }
    override fun onDestroy() { CallAudio.serviceBound = false; h.removeCallbacksAndMessages(null); super.onDestroy() }

    override fun onCallAdded(call: Call) {
        val d = call.details
        if (d.callDirection != Call.Details.DIRECTION_OUTGOING) return
        val placed = CallAudio.matchPrimeCall(d.handle?.schemeSpecificPart) ?: return
        CallAudio.consume(placed)
        if (placed.speaker) { CallAudio.lastAction = "speaker requested; left alone"; return }
        guard = Guard(call, placed)
        call.registerCallback(cb, h)
        enforce("added")
    }

    override fun onCallRemoved(call: Call) {
        if (guard?.call === call) { call.unregisterCallback(cb); guard = null; h.removeCallbacksAndMessages(null) }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        audioState?.let { CallAudio.lastRoute = CallAudio.routeName(it.route) }
        enforce("route_changed")
    }

    private fun enforce(why: String) {
        val g = guard ?: return
        val s = callAudioState ?: return
        CallAudio.lastRoute = CallAudio.routeName(s.route)
        if (!CallAudio.inGuard(System.currentTimeMillis(), g.placed.at, g.activeAt, g.corrections)) return
        val to = CallAudio.correction(s.route, s.supportedRouteMask, wantSpeaker = false) ?: return
        g.corrections++
        setAudioRoute(to)
        CallAudio.lastAction = "incall_service: speaker -> ${CallAudio.routeName(to)} ($why)"
        Log.i("PrimeLink", "call audio ${CallAudio.lastAction}")
    }
}
