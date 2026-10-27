package com.tyler.primelink.actions

/**
 * Keeps calls that PRIME placed off the loudspeaker. Pure decision logic + a little shared state, so it is unit-tested.
 *
 * Two enforcers use it:
 *  1. [CallWatchService] — a non-UI InCallService. Telecom only binds it when the MANAGE_ONGOING_CALLS app-op is
 *     allowed for Tether (one adb command). It switches routes through Telecom's own InCallService.setAudioRoute,
 *     which is the supported way and works no matter which dialer UI is showing.
 *  2. [CallAudioFallback] — used when the service is not bound. Tries AudioManager.setCommunicationDevice(earpiece).
 *     Android gives the audio-mode owner (the phone process during a cellular call) priority, so the platform may
 *     silently ignore it. It reports what actually happened instead of pretending.
 *
 * Rules: only calls Prime placed (never the user's own calls); only during dialing + the first few seconds after the
 * call connects (when Samsung/Telecom auto-picks a route). After that a speaker switch is treated as the user's own tap
 * and left alone. `call` with speaker=true opts out.
 */
object CallAudio {
    // Mirror android.telecom.CallAudioState route bits (kept literal so this file stays pure JVM).
    const val ROUTE_EARPIECE = 0x1
    const val ROUTE_BLUETOOTH = 0x2
    const val ROUTE_WIRED_HEADSET = 0x4
    const val ROUTE_SPEAKER = 0x8

    /** An outgoing call that starts within this long of Prime's dial is Prime's call. */
    const val MATCH_MS = 30_000L
    /** Keep correcting speaker for this long after the call connects (auto-route window), then respect taps. */
    const val GUARD_AFTER_ACTIVE_MS = 8_000L
    /** Never guard longer than this from the dial (covers long ringing when "active" is never seen). */
    const val MAX_GUARD_MS = 120_000L
    /** Never fight more than this many times per call. */
    const val MAX_CORRECTIONS = 4

    class Placed(val number: String, val at: Long, val speaker: Boolean)

    @Volatile var lastPlaced: Placed? = null; private set
    @Volatile var serviceBound: Boolean = false
    @Volatile var lastRoute: String? = null
    @Volatile var lastAction: String? = null

    fun notePlaced(number: String, speaker: Boolean, now: Long = System.currentTimeMillis()) { lastPlaced = Placed(number, now, speaker) }

    fun routeName(route: Int): String = when (route) {
        ROUTE_EARPIECE -> "earpiece"; ROUTE_BLUETOOTH -> "bluetooth"; ROUTE_WIRED_HEADSET -> "wired_headset"; ROUTE_SPEAKER -> "speaker"
        else -> "unknown($route)"
    }

    /** The route to switch to, or null to leave the call alone. Wired headset > Bluetooth > earpiece. */
    fun correction(route: Int, supportedMask: Int, wantSpeaker: Boolean): Int? {
        if (wantSpeaker || route != ROUTE_SPEAKER) return null
        return listOf(ROUTE_WIRED_HEADSET, ROUTE_BLUETOOTH, ROUTE_EARPIECE).firstOrNull { supportedMask and it != 0 }
    }

    /** Is [now] still inside the window where an automatic speaker switch should be undone? */
    fun inGuard(now: Long, dialedAt: Long, activeAt: Long?, corrections: Int): Boolean {
        if (corrections >= MAX_CORRECTIONS) return false
        if (now - dialedAt > MAX_GUARD_MS) return false
        return activeAt == null || now - activeAt <= GUARD_AFTER_ACTIVE_MS
    }

    private fun digits(s: String?) = s.orEmpty().filter { it.isDigit() }.takeLast(10)

    /** The pending Prime dial this outgoing call belongs to, or null if it is someone else's call. */
    fun matchPrimeCall(handleNumber: String?, now: Long = System.currentTimeMillis()): Placed? {
        val p = lastPlaced ?: return null
        if (now - p.at !in 0..MATCH_MS) return null
        val a = digits(handleNumber); val b = digits(p.number)
        return if (a.isNotEmpty() && a == b) p else null
    }
    /** One dial guards one call. */
    @Synchronized fun consume(p: Placed) { if (lastPlaced === p) lastPlaced = null }
}
