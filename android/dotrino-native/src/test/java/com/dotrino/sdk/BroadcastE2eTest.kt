package com.dotrino.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
 * The native HOST of a broadcast against a real proxy, watched by `@dotrino/lobby` itself
 * (test-vectors/e2e-broadcast.mjs): the viewer gets the states the phone signed and sealed,
 * verifies them, and a viewer without the secret is denied.
 *
 *   node test-vectors/e2e-broadcast.mjs /tmp/bcast.json &
 *   DOTRINO_E2E_BCAST=/tmp/bcast.json ./gradlew :dotrino-native:testDebugUnitTest --tests '*BroadcastE2eTest*'
 */
class BroadcastE2eTest {
    @Test fun aLobbyViewerWatchesTheNativeHost() = runBlocking {
        val path = System.getenv("DOTRINO_E2E_BCAST")
        assumeTrue("DOTRINO_E2E_BCAST not set: start test-vectors/e2e-broadcast.mjs to run this", path != null && File(path).exists())
        val f = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val profile = Profile.of(TestKeys.fromJwk(f.getValue("sign").jsonObject, f.getValue("enc").jsonObject))
        val transport = TestKeys.fromJwk(f.getValue("transport").jsonObject, f.getValue("transportEnc").jsonObject)
        val host = BroadcastHost(f.getValue("proxyUrl").jsonPrimitive.content, "padel", profile, transport)
        var viewers = 0
        host.onViewers = { viewers = it }
        host.onWarn = { what, e -> System.err.println("host warn: $what: ${e?.message}") }
        try {
            host.start()
            host.publish(buildJsonObject { put("name", "Torneo ñandú"); put("round", 1) })
            File("$path.ref").writeText(BroadcastHost.encodeRef(host.linkRef, profile.publickey))

            val seen = File("$path.seen")
            withTimeout(60_000) { while (!seen.exists() || seen.readLines().isEmpty()) delay(200) }
            assertTrue("the viewer is counted", viewers >= 1)
            host.publish(buildJsonObject { put("name", "Torneo ñandú"); put("round", 2) })
            withTimeout(30_000) { while (seen.readLines().size < 2) delay(200) }
            val states = seen.readLines().map { Json.parseToJsonElement(it).jsonObject.getValue("state").jsonObject }
            assertEquals("Torneo ñandú", states[0].getValue("name").jsonPrimitive.content)
            assertEquals("2", states.last().getValue("round").jsonPrimitive.content)

            val denied = File("$path.denied")
            withTimeout(30_000) { while (!denied.exists()) delay(200) }
            assertEquals("bad-secret", Json.parseToJsonElement(denied.readText()).jsonObject.getValue("reason").jsonPrimitive.content)
        } finally {
            host.close()
            File(path).delete() // the harness stops when this goes
        }
    }
}
