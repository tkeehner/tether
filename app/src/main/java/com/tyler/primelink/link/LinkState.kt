package com.tyler.primelink.link

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CompletableFuture

/** Process-wide link state the UI observes. */
object LinkState {
    data class Pairing(val openedAt: Long, val code: String? = null, val pcName: String? = null, val awaitingAllow: Boolean = false, val result: String? = null)
    val running = MutableStateFlow(false)
    val address = MutableStateFlow<String?>(null)
    val sessions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pairing = MutableStateFlow<Pairing?>(null)
    val auditTick = MutableStateFlow(0)
    @Volatile var decision: CompletableFuture<Boolean>? = null
    @Volatile var lastCommandAt = 0L

    fun openPairing() { pairing.value = Pairing(System.currentTimeMillis()) }
    fun closePairing() { pairing.value = null; decision?.complete(false) }
    fun pairingOpen(): Boolean = pairing.value?.let { System.currentTimeMillis() - it.openedAt < PAIR_WINDOW_MS } ?: false
    @Synchronized fun sessionOpened(id: String) { sessions.value = sessions.value + (id to ((sessions.value[id] ?: 0) + 1)) }
    @Synchronized fun sessionClosed(id: String) { val n = (sessions.value[id] ?: 1) - 1; sessions.value = if (n <= 0) sessions.value - id else sessions.value + (id to n) }
    const val PAIR_WINDOW_MS = 180_000L
    /** Session alert fires on the first command after this much idle time. */
    const val IDLE_ALERT_MS = 5 * 60_000L
    fun shouldAlert(now: Long, last: Long) = last == 0L || now - last >= IDLE_ALERT_MS
}
