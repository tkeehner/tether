package com.tyler.primelink

import androidx.test.core.app.ApplicationProvider
import com.tyler.primelink.actions.CallCountdown
import com.tyler.primelink.link.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallCountdownTest {
    private val c get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun cancelBeforeDialMeansNoCall() {
        val f = CallCountdown.begin(c, "5555550100", "Mom")
        val p = CallCountdown.current!!
        assertTrue(CallCountdown.cancel("user"))
        assertEquals("cancelled", f.get())
        assertNull("a cancelled countdown can never be claimed for dialing", CallCountdown.claim(p))
    }
    @Test fun claimThenDial() {
        val f = CallCountdown.begin(c, "5555550100", null); val p = CallCountdown.current!!
        assertEquals("5555550100", CallCountdown.claim(p)); CallCountdown.finish(p, "dialed")
        assertEquals("dialed", f.get()); assertFalse(CallCountdown.cancel("late"))
    }
    @Test fun newCallReplacesOldAndStaleScreenCantCancelIt() {
        val f1 = CallCountdown.begin(c, "111", "A"); val p1 = CallCountdown.current!!
        val f2 = CallCountdown.begin(c, "222", "B"); val p2 = CallCountdown.current!!
        assertEquals("cancelled", f1.get())
        CallCountdown.cancelThis(p1)
        assertEquals(p2, CallCountdown.current); assertFalse(f2.isDone)
        CallCountdown.cancel("cleanup")
    }
    @Test fun sessionAlertAfterIdle() {
        assertTrue(LinkState.shouldAlert(1_000_000, 0))
        assertFalse(LinkState.shouldAlert(1_000_000, 1_000_000 - 60_000))
        assertTrue(LinkState.shouldAlert(1_000_000, 1_000_000 - LinkState.IDLE_ALERT_MS))
    }
}
