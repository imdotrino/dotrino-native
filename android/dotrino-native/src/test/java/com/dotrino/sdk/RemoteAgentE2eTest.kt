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
    private fun harness(): JsonObject {
        val path = System.getenv("DOTRINO_E2E_AGENT")
        assumeTrue("DOTRINO_E2E_AGENT not set: start test-vectors/e2e-remote-agent.mjs to run this", path != null && File(path).exists())
        return Json.parseToJsonElement(File(path!!).readText()).jsonObject
    }

    /** The phone's profile as the identity app keeps it, with the record [actaField] of the harness. */
    private suspend fun profileOf(f: JsonObject, actaField: String = "acta"): Profile {
        val keys = TestKeys.fromJwk(f.getValue("privateJwk").jsonObject, f.getValue("encPrivateJwk").jsonObject)
        val items = mapOf(
            "kv:dotrino.identity.current" to "p1",
            "key:dotrino.identity.p.p1.keypair" to buildJsonObject { put("external", "k"); put("kind", "sign"); put("publicJwk", Json.parseToJsonElement(f.getValue("publickey").jsonPrimitive.content)) }.toString(),
            "key:dotrino.identity.p.p1.enc-keypair" to buildJsonObject { put("external", "k"); put("kind", "enc"); put("publicJwk", Json.parseToJsonElement(f.getValue("encPub").jsonPrimitive.content)) }.toString(),
            "kv:dotrino.identity.p.p1.acta" to f.getValue(actaField).toString(),
            "kv:dotrino.identity.p.p1.vault.cert" to buildJsonObject {
                put("master", f.getValue("master")); put("proxy", f.getValue("proxyUrl")); put("cert", f.getValue("cert")); put("deviceId", "phone")
            }.toString(),
        )
        return Profile.load(items) { keys }
    }

    @Test fun findsTheMachineAndRunsACommandInAConsole() = runBlocking {
        val f = harness()
        val profile = profileOf(f)
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

    /**
     * The phone holds a record OLDER than the agent's paper (the vault changed it after the phone
     * paired): without catching up the handshake stops with `acta-vieja`; with [ActaSync] the
     * phone asks its vault, adopts the chain, keeps it where the identity reads it, and the
     * console opens.
     */
    @Test fun anOlderRecordIsBroughtUpToDateFromTheVault() = runBlocking {
        val f = harness()
        val stale = profileOf(f, "staleActa")
        val newest = f.getValue("acta").jsonObject
        assertTrue("the harness gives an older record", stale.actaSeq!! < newest.getValue("seq").jsonPrimitive.content.toLong())
        val agent = f.getValue("agentPubkey").jsonPrimitive.content
        val conn = ProxyConnection(f.getValue("proxyUrl").jsonPrimitive.content, "terminal")
        conn.connect()
        try {
            conn.identifyAs(stale.publickey) { stale.signData(it) }
            val refused = runCatching { RemoteAgent.open(stale, conn, agent) }.exceptionOrNull()
            assertTrue("without catching up: ${refused?.message}", refused is RemoteAgent.RemoteAgentError && "acta-vieja" in refused.message.orEmpty())

            val saved = HashMap<String, String>()
            var caughtUp: Profile? = null
            val session = RemoteAgent.open(stale, conn, agent, catchUp = {
                ActaSync.update(stale, conn, { k, v -> saved[k] = v }).also { caughtUp = it }
            })
            assertEquals("adopted the vault's record", newest.getValue("seq").jsonPrimitive.content.toLong(), caughtUp?.actaSeq)
            assertEquals("kept where the identity reads it", Acta.hash(newest), Acta.hash(Json.parseToJsonElement(saved.getValue("kv:dotrino.identity.p.p1.acta")).jsonObject))
            session.close()
            // Up to date: asking again adopts nothing and changes nothing.
            assertEquals(caughtUp!!.actaSeq, ActaSync.update(caughtUp!!, conn, { _, _ -> error("nothing to save") }).actaSeq)
        } finally { conn.close() }
    }
}
