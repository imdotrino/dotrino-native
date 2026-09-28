package com.dotrino.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The acta policy with REAL actas sealed by a vault (test-vectors/e2e-acta.mjs): the phone's
 * profile, stored the way the identity stores it, signs and broadcasts only while its acta
 * gives it `sign` — and a `@dotrino/lobby` viewer verifies what it broadcast.
 *
 *   node test-vectors/e2e-acta.mjs /tmp/acta.json &
 *   DOTRINO_E2E_ACTA=/tmp/acta.json ./gradlew :dotrino-native:testDebugUnitTest --tests '*ActaE2eTest*'
 */
class ActaE2eTest {
    /** The identity's store with the profile of the vault's device and [acta] (`vault/nativeStore.js`). */
    private fun items(f: JsonObject, acta: JsonObject): Map<String, String> {
        fun rec(kind: String, jwk: String) = buildJsonObject { put("external", "kid-1"); put("kind", kind); put("publicJwk", Json.parseToJsonElement(jwk)) }.toString()
        return mapOf(
            "kv:dotrino.identity.current" to "p1",
            "key:dotrino.identity.p.p1.keypair" to rec("sign", f.getValue("publickey").jsonPrimitive.content),
            "key:dotrino.identity.p.p1.enc-keypair" to rec("enc", f.getValue("encPub").jsonPrimitive.content),
            "kv:dotrino.identity.p.p1.acta" to acta.toString(),
        )
    }

    @Test fun signsAndBroadcastsOnlyWhileTheActaSaysSo() = runBlocking {
        val path = System.getenv("DOTRINO_E2E_ACTA")
        assumeTrue("DOTRINO_E2E_ACTA not set: start test-vectors/e2e-acta.mjs to run this", path != null && File(path).exists())
        val f = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val keys = TestKeys.fromJwk(f.getValue("privateJwk").jsonObject, f.getValue("encPrivateJwk").jsonObject)
        try {
            // The acta names the device by the EXACT string it enrolled with (the JWK with
            // `key_ops`, `ext`…): the profile has to be that same string, or it is nobody.
            val withSign = Profile.load(items(f, f.getValue("actaSign").jsonObject)) { keys }
            assertEquals(f.getValue("publickey").jsonPrimitive.content, withSign.publickey)
            assertTrue("the vault's acta gives this device sign", withSign.canSign)

            val host = BroadcastHost(f.getValue("proxyUrl").jsonPrimitive.content, "padel", withSign, TestKeys.fresh())
            host.start()
            host.publish(buildJsonObject { put("name", "con acta"); put("round", 1) })
            File("$path.ref").writeText(BroadcastHost.encodeRef(host.linkRef, withSign.publickey))
            val seen = File("$path.seen")
            withTimeout(60_000) { while (!seen.exists() || seen.readLines().isEmpty()) delay(200) }
            assertEquals("con acta", Json.parseToJsonElement(seen.readLines().first()).jsonObject.getValue("name").jsonPrimitive.content)
            host.close()

            // The owner took `sign` away: the same profile does not sign nor broadcast any more.
            val without = Profile.load(items(f, f.getValue("actaRead").jsonObject)) { keys }
            assertFalse(without.canSign)
            val e = assertThrows(Profile.ProfileError::class.java) {
                runBlocking { BroadcastHost(f.getValue("proxyUrl").jsonPrimitive.content, "padel", without, TestKeys.fresh()).start() }
            }
            assertEquals("needs-vault-signer", e.code)
        } finally {
            File(path).delete()
        }
    }
}
