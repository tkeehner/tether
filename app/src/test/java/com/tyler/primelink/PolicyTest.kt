package com.tyler.primelink

import com.tyler.primelink.actions.Policy
import com.tyler.primelink.actions.RpcError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    private fun code(f: () -> Unit) = try { f(); "ok" } catch (e: RpcError) { e.code }

    @Test fun onlyNativeToolsExist() {
        for (bad in listOf("tap", "swipe", "screenshot", "ui_tree", "type_text", "shell", "factory_reset", "uninstall", "send_sms"))
            assertEquals("forbidden", code { Policy.gate(bad, locked = false) })
        for (t in listOf("status", "find_contact", "call", "hang_up", "call_state", "set_alarm", "set_timer", "read_notifications",
                "dismiss_notification", "launch_app", "open_url")) assertTrue(t in Policy.TOOLS)
    }

    @Test fun userLockRules() {
        // allowed while locked: status, notifications, alarms/timers, media, flashlight, calls
        for (t in listOf("status", "read_notifications", "dismiss_notification", "set_alarm", "set_timer", "media", "flashlight", "call", "hang_up", "call_state", "find_contact"))
            assertEquals(t, "ok", code { Policy.gate(t, locked = true) })
        for (t in listOf("launch_app", "open_url")) { assertEquals("locked", code { Policy.gate(t, locked = true) }); assertEquals("ok", code { Policy.gate(t, locked = false) }) }
    }

    @Test fun numbers() {
        assertEquals("5555550100", Policy.normalizeNumber(" (555) 555-0100 "))
        assertEquals("+15555550100", Policy.normalizeNumber("+1 555.555.0100"))
        for (bad in listOf("*#06#", "*#*#4636#*#*", "##002#", "555,1234", "12", "abc", "1234567890123456")) assertEquals(bad, "bad_request", code { Policy.normalizeNumber(bad) })
        for (e in listOf("911", "112", "999")) assertEquals("forbidden", code { Policy.normalizeNumber(e) })
    }

    @Test fun urlsAndPackages() {
        assertEquals("https://example.com/x", Policy.url(JSONObject().put("url", "https://example.com/x")))
        for (bad in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a", "content://x", "https://user:pw@example.com", "http:///nohost"))
            assertFalse(bad, code { Policy.url(JSONObject().put("url", bad)) } == "ok")
        assertEquals("com.android.chrome", Policy.pkg(JSONObject().put("package", "com.android.chrome")))
        assertThrows(RpcError::class.java) { Policy.pkg(JSONObject().put("package", "../etc")) }
    }

    @Test fun argRanges() {
        assertEquals("bad_request", code { Policy.int(JSONObject().put("hour", 24), "hour", 0, 23) })
        assertEquals("bad_request", code { Policy.int(JSONObject().put("hour", 7.5), "hour", 0, 23) })
        assertEquals(7, Policy.int(JSONObject().put("hour", 7), "hour", 0, 23))
        assertEquals(arrayListOf(2, 4), Policy.days(JSONObject().put("days", org.json.JSONArray(listOf(2, 4, 2)))))
        assertEquals("bad_request", code { Policy.days(JSONObject().put("days", org.json.JSONArray(listOf(0)))) })
        assertEquals("bad_request", code { Policy.choice(JSONObject().put("action", "rm"), "action", Policy.MEDIA) })
    }

    @Test fun auditNeverHoldsContent() {
        val a = Policy.auditArgs("call", JSONObject().put("number", "5555550100"))
        assertFalse(a.contains("555555")); assertEquals("host=example.com", Policy.auditArgs("open_url", JSONObject().put("url", "https://example.com/secret/path?q=1")))
        assertEquals("query_len=3", Policy.auditArgs("find_contact", JSONObject().put("query", "Mom")))
    }
}
