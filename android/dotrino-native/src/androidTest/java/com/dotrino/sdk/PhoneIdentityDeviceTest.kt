package com.dotrino.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PhoneIdentity] against the REAL identity app on a phone or emulator: a profile whose keys
 * live in its chip, written the way the identity writes it (`vault/nativeStore.js`), signs and
 * seals as that profile.
 *
 * It only runs on a phone with the identity app and WITHOUT a profile: it never touches a real
 * one. `-e keep true` leaves the test profile in place, to try an app by hand afterwards.
 *
 *   ./gradlew :identity-app:installDebug
 *   ./gradlew :dotrino-native:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.keep=true
 */
@RunWith(AndroidJUnit4::class)
class PhoneIdentityDeviceTest {
    @Test fun speaksAsTheProfileInTheIdentityApp() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("the identity app is not installed", IdentityClient.isInstalled(ctx))
        val client = IdentityClient(ctx)
        val before = client.call("storeLoad")["items"].toString()
        assumeTrue("this phone already has a profile: not touching it", !before.contains("dotrino.identity.current"))

        val k = client.call("create")
        val kid = k.getValue("kid").jsonPrimitive.content
        val pid = "p-e2e"
        fun rec(kind: String, jwk: String) = buildJsonObject { put("external", kid); put("kind", kind); put("publicJwk", Json.parseToJsonElement(jwk)) }.toString()
        suspend fun set(key: String, v: String) = client.call("storeSet", buildJsonObject { put("k", key); put("v", v) })
        set("kv:dotrino.identity.current", pid)
        set("key:dotrino.identity.p.$pid.keypair", rec("sign", k.getValue("publickey").jsonPrimitive.content))
        set("key:dotrino.identity.p.$pid.enc-keypair", rec("enc", k.getValue("encPub").jsonPrimitive.content))

        val phone = PhoneIdentity(ctx)
        try {
            val profile = phone.profile()
            assertTrue(Delegation.samePubkey(profile.publickey, k.getValue("publickey").jsonPrimitive.content))
            assertTrue(profile.canSign)
            val data = buildJsonObject { put("op", "e2e"); put("n", 1) }
            assertTrue("the chip signs as the profile", Crypto.verify(profile.publickey, data, profile.signData(data)))

            // Sealing both ways with another key of this phone (in this test app's own keystore).
            val other = Profile.of(if (KeystoreKeys.exists("peer-e2e")) KeystoreKeys.open("peer-e2e") else KeystoreKeys.create("peer-e2e"))
            assertEquals("hola", other.decrypt(profile.encPub, profile.encrypt(listOf(other.encPub), "hola")))
            assertEquals("chau", profile.decrypt(other.encPub, other.encrypt(listOf(profile.encPub), "chau")))
        } finally {
            phone.close()
            val keep = InstrumentationRegistry.getArguments().getString("keep") == "true"
            if (!keep) {
                for (key in listOf("kv:dotrino.identity.current", "key:dotrino.identity.p.$pid.keypair", "key:dotrino.identity.p.$pid.enc-keypair")) {
                    client.call("storeRemove", buildJsonObject { put("k", key) })
                }
                client.call("remove", buildJsonObject { put("kid", JsonPrimitive(kid)) })
            }
            client.close()
        }
    }
}
