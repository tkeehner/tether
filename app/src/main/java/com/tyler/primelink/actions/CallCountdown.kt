package com.tyler.primelink.actions

import android.content.Context
import android.content.Intent
import com.tyler.primelink.ui.CallCountdownActivity
import java.util.concurrent.CompletableFuture

/**
 * One pending "Calling… cancel" countdown at a time. Prime's `call` starts it; the user (CANCEL / Back), Prime
 * (cancel_call / hang_up) or a second call request cancels it. Only after COUNTDOWN_MS does the activity dial.
 */
object CallCountdown {
    const val COUNTDOWN_MS = 3_000L
    class Pending(val number: String, val name: String?, val startedAt: Long, val speaker: Boolean = false, val result: CompletableFuture<String> = CompletableFuture())
    @Volatile var current: Pending? = null; private set

    @Synchronized fun begin(c: Context, number: String, name: String?, speaker: Boolean = false): CompletableFuture<String> {
        current?.result?.complete("cancelled")
        val p = Pending(number, name, System.currentTimeMillis(), speaker); current = p
        c.startActivity(Intent(c, CallCountdownActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        return p.result
    }
    /** Returns true if a countdown was pending. */
    @Synchronized fun cancel(by: String): Boolean {
        val p = current ?: return false
        current = null
        return p.result.complete("cancelled")
    }
    /** Cancel only if [p] is still the pending call (so a stale screen can't cancel a newer call). */
    @Synchronized fun cancelThis(p: Pending): Boolean { if (current !== p) return p.result.complete("cancelled"); current = null; return p.result.complete("cancelled") }
    /** Called by the activity when the countdown completes; returns the number to dial, or null if it was cancelled meanwhile. */
    @Synchronized fun claim(p: Pending): String? {
        if (current !== p || p.result.isDone) return null
        current = null; return p.number
    }
    @Synchronized fun finish(p: Pending, outcome: String) { p.result.complete(outcome) }
    fun pendingName(): String? = current?.let { it.name ?: it.number }
}
