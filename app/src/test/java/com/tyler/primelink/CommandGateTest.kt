package com.tyler.primelink

import com.tyler.primelink.link.CommandGate
import com.tyler.primelink.link.LinkCrypto
import com.tyler.primelink.link.b64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGateTest {
    private val key = LinkCrypto.cmdKey(ByteArray(32) { 7 })
    private var now = 1_700_000_000_000L
    private fun req(id: Long, m: String = "status", ps: String = "{}", ts: Long = now, k: ByteArray = key) =
        JSONObject().put("id", id).put("m", m).put("ps", ps).put("ts", ts).put("mac", b64(LinkCrypto.cmdMac(k, id, m, ts, ps)))
    private fun code(r: CommandGate.Result) = (r as? CommandGate.Result.Bad)?.code ?: "ok"

    @Test fun acceptsSignedFreshIncreasing() {
        val g = CommandGate({ key }, { now })
        val r = g.check(req(5, "find_contact", "{\"query\":\"Mom\"}"))
        assertTrue(r is CommandGate.Result.Ok); assertEquals("Mom", (r as CommandGate.Result.Ok).params.getString("query"))
        assertEquals("ok", code(g.check(req(6))))
    }
    @Test fun rejectsWrongKeyTamperReplayStale() {
        val g = CommandGate({ key }, { now })
        assertEquals("auth_fail", code(g.check(req(1, k = LinkCrypto.cmdKey(ByteArray(32))))))
        assertEquals("auth_fail", code(g.check(req(2).put("ps", "{\"x\":1}"))))
        assertEquals("auth_fail", code(g.check(req(3).put("m", "open_url"))))
        assertEquals("auth_fail", code(g.check(req(4).remove("mac").let { req(4).put("mac", "") })))
        assertEquals("ok", code(g.check(req(10))))
        assertEquals("replay", code(g.check(req(10))))
        assertEquals("replay", code(g.check(req(9))))
        assertEquals("stale", code(g.check(req(11, ts = now - 121_000))))
        assertEquals("stale", code(g.check(req(12, ts = now + 121_000))))
    }
    @Test fun noTokenMeansNoCommands() = assertEquals("auth_fail", code(CommandGate({ null }, { now }).check(req(1))))
}
