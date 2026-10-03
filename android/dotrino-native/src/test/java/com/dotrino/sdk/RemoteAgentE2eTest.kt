package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The remote-agent client against a REAL terminal agent enrolled in a real vault
 * (test-vectors/e2e-remote-agent.mjs): the phone finds the machine, the handshake holds
 * against the record both ways, and a command runs in a console over the session channel.
 *
 *   node test-vectors/e2e-remote-agent.mjs /tmp/ra.json &
 *   DOTRINO_E2E_AGENT=/tmp/ra.json ./gradlew :dotrino-native:testDebugUnitTest --tests '*RemoteAgentE2eTest*'
 */
class RemoteAgentE2eTest {
    @Test fun findsTheMachineAndRunsACommandInAConsole() = runBlocking {
        val path = System.getenv("DOTRINO_E2E_AGENT")
        assumeTrue("DOTRINO_E2E_AGENT not set: start test-vectors/e2e-remote-agent.mjs to run this", path != null && File(path).exists())
        val f = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val keys = TestKeys.fromJwk(f.getValue("privateJwk").jsonObject, f.getValue("encPrivateJwk").jsonObject)
        val items = mapOf(
            "kv:dotrino.identity.current" to "p1",
            "key:dotrino.identity.p.p1.keypair" to buildJsonObject { put("external", "k"); put("kind", "sign"); put("publicJwk", Json.parseToJsonElement(f.getValue("publickey").jsonPrimitive.content)) }.toString(),
            "key:dotrino.identity.p.p1.enc-keypair" to buildJsonObject { put("external", "k"); put("kind", "enc"); put("publicJwk", Json.parseToJsonElement(f.getValue("encPub").jsonPrimitive.content)) }.toString(),
            "kv:dotrino.identity.p.p1.acta" to f.getValue("acta").toString(),
            "kv:dotrino.identity.p.p1.vault.cert" to buildJsonObject {
                put("master", f.getValue("master")); put("proxy", f.getValue("proxyUrl")); put("cert", f.getValue("cert")); put("deviceId", "phone")
            }.toString(),
        )
        val profile = Profile.load(items) { keys }
        val agent = f.getValue("agentPubkey").jsonPrimitive.content
        val conn = ProxyConnection(f.getValue("proxyUrl").jsonPrimitive.content, "terminal")
        conn.connect()
        try {
            conn.identifyAs(profile.publickey) { profile.signData(it) }

            // WHO IS THERE: the record names the machine, and it answers what it is.
            val candidates = RemoteAgent.candidates(profile)
            assertTrue("the record names the agent's machine", candidates.any { it.first == agent })
            assertEquals("TerminalDePrueba", candidates.first { it.first == agent }.second)
            assertEquals("terminal-agent", RemoteAgent.probe(conn, candidates.map { it.first })[agent])

            // THE HANDSHAKE, both ways against the record, and a console over the session.
            val session = RemoteAgent.open(profile, conn, agent)
            val attached = CompletableDeferred<JsonObject>(); val seen = CompletableDeferred<Unit>(); val out = StringBuilder()
            session.onMessage { m ->
                when (m["type"]?.jsonPrimitive?.content) {
                    "attached" -> attached.complete(m)
                    "out", "replay" -> { out.append((m["data"] as? JsonPrimitive)?.content.orEmpty()); if ("dotrino-42" in out) seen.complete(Unit) }
                }
            }
            session.send(buildJsonObject { put("type", "open"); put("cols", 80); put("rows", 24) })
            val a = withTimeout(10_000) { attached.await() }
            assertEquals("true", a.getValue("fresh").jsonPrimitive.content)
            assertEquals("remote", a.getValue("console").jsonObject.getValue("origin").jsonPrimitive.content)
            session.send(buildJsonObject { put("type", "input"); put("data", "echo dotrino-\$((40+2))\r") })
            withTimeout(10_000) { seen.await() }
            session.send(buildJsonObject { put("type", "close") })
            session.close()
        } finally { conn.close() }
    }
}
