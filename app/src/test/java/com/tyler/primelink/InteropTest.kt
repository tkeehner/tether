package com.tyler.primelink

import com.tyler.primelink.link.CommandGate
import com.tyler.primelink.link.FrameIO
import com.tyler.primelink.link.LinkCrypto
import com.tyler.primelink.link.PhoneProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The real Kotlin phone protocol (pairing + session + CommandGate) against the real Python plink client.
 * Needs PLINK_PY (python with cryptography) and PLINK_PC (pc/ dir); skipped otherwise.
 */
class InteropTest {
    @Test fun kotlinPhoneTalksToPythonPc() {
        val py = System.getenv("PLINK_PY"); val pcDir = System.getenv("PLINK_PC")
        assumeTrue(py != null && pcDir != null)
        val sk = LinkCrypto.genSecret(); val token = LinkCrypto.random(32); val pcs = HashMap<String, ByteArray>()
        val tmp = File.createTempFile("plink", "").apply { delete(); mkdirs() }; val codeFile = File(tmp, "code")
        val ss = ServerSocket(0)
        val server = Thread {
            repeat(4) {
                val s = runCatching { ss.accept() }.getOrNull() ?: return@Thread
                Thread {
                    s.use {
                        val io = FrameIO(s.getInputStream(), s.getOutputStream()); val first = io.read()
                        if (JSONObject(String(first)).optString("t") == "pair1") {
                            val pc = PhoneProtocol.pairResponder(io, first, "ktphone", "KtPhone", sk, token, { code, _ -> codeFile.writeText(code) }, { true })
                            pcs[pc.pcId] = pc.pub; return@use
                        }
                        val (ch, _) = PhoneProtocol.sessionResponder(io, first, "ktphone", sk, { pcs[it] })
                        val gate = CommandGate({ LinkCrypto.cmdKey(token) })
                        runCatching { while (true) {
                            when (val g = gate.check(ch.recv())) {
                                is CommandGate.Result.Ok -> ch.send(JSONObject().put("id", g.id).put("r", JSONObject().put("echo", g.method).put("params", g.params)))
                                is CommandGate.Result.Bad -> { ch.send(JSONObject().put("id", g.id).put("e", JSONObject().put("code", g.code).put("msg", g.msg))); if (g.code == "auth_fail") break }
                            }
                        } }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
        val p = ProcessBuilder(py, "$pcDir/tests/interop_client.py", ss.localPort.toString(), codeFile.path, File(tmp, "home").apply { mkdirs() }.path)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(60, TimeUnit.SECONDS)
        ss.close(); server.interrupt()
        val r = JSONObject(out.trim().lines().last())
        assertEquals("ktphone", r.getString("paired"))
        assertEquals("status", r.getJSONObject("status").getString("echo"))
        assertEquals("Mom", r.getJSONObject("find").getJSONObject("params").getString("query"))
        assertEquals("auth_fail", r.getString("forged"))
        tmp.deleteRecursively()
    }
}
