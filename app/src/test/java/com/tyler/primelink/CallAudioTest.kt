package com.tyler.primelink

import com.tyler.primelink.actions.CallAudio
import com.tyler.primelink.actions.CallAudio.ROUTE_BLUETOOTH as BT
import com.tyler.primelink.actions.CallAudio.ROUTE_EARPIECE as EAR
import com.tyler.primelink.actions.CallAudio.ROUTE_SPEAKER as SPK
import com.tyler.primelink.actions.CallAudio.ROUTE_WIRED_HEADSET as WIRED
import com.tyler.primelink.actions.Policy
import com.tyler.primelink.actions.RpcError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioTest {
    @Test fun speakerIsMovedToEarpiece() = assertEquals(EAR, CallAudio.correction(SPK, EAR or SPK, wantSpeaker = false))
    @Test fun headsetsBeatEarpiece() {
        assertEquals(WIRED, CallAudio.correction(SPK, EAR or SPK or WIRED or BT, false))
        assertEquals(BT, CallAudio.correction(SPK, EAR or SPK or BT, false))
    }
    @Test fun nonSpeakerRoutesAreLeftAlone() {
        for (r in listOf(EAR, BT, WIRED)) assertNull(CallAudio.correction(r, EAR or SPK or BT or WIRED, false))
    }
    @Test fun explicitSpeakerRequestIsHonoured() = assertNull(CallAudio.correction(SPK, EAR or SPK, wantSpeaker = true))
    @Test fun noAlternativeMeansNoChange() = assertNull(CallAudio.correction(SPK, SPK, false))

    @Test fun guardWindowCoversDialingAndFirstSecondsAfterConnect() {
        val dial = 1_000_000L
        assertTrue("ringing, not connected yet", CallAudio.inGuard(dial + 25_000, dial, null, 0))
        assertTrue("just connected", CallAudio.inGuard(dial + 30_000, dial, dial + 29_000, 0))
        assertFalse("well after connect: the user's own speaker tap is respected", CallAudio.inGuard(dial + 60_000, dial, dial + 30_000, 0))
        assertFalse("hard cap", CallAudio.inGuard(dial + CallAudio.MAX_GUARD_MS + 1, dial, null, 0))
        assertFalse("never fight forever", CallAudio.inGuard(dial + 1, dial, null, CallAudio.MAX_CORRECTIONS))
    }

    @Test fun onlyPrimesOwnRecentCallIsGuarded() {
        val t = 5_000_000L
        CallAudio.notePlaced("+15555550123", speaker = false, now = t)
        assertNull("different number = the user's own call", CallAudio.matchPrimeCall("tel-ish 5555550999", t + 1_000))
        assertNull("too late", CallAudio.matchPrimeCall("5555550123", t + CallAudio.MATCH_MS + 1))
        val p = CallAudio.matchPrimeCall("(555) 555-0123", t + 2_000)
        assertSame(CallAudio.lastPlaced, p)
        CallAudio.consume(p!!)
        assertNull("one dial guards one call", CallAudio.matchPrimeCall("5555550123", t + 3_000))
    }

    @Test fun speakerFlagIsStrictBoolean() {
        assertFalse(Policy.bool(JSONObject(), "speaker", false))
        assertTrue(Policy.bool(JSONObject().put("speaker", true), "speaker", false))
        try { Policy.bool(JSONObject().put("speaker", "yes"), "speaker", false); throw AssertionError("should refuse") } catch (e: RpcError) {}
    }
}
