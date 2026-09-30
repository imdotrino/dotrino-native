package com.dotrino.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A STORE LARGER THAN ONE BINDER MESSAGE, against the REAL identity app: 700 KB in one item (a
 * phone with a few profiles and a photo went past 512 KB), written in one request and read 20
 * times from TWO clients at once. Before the pieces were pulled one at a time, some of these
 * calls ended in `identity-no-reply` — sometimes yes, sometimes no.
 *
 *   ./gradlew :identity-app:installDebug
 *   ./gradlew :dotrino-native:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dotrino.sdk.LargeStoreDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class LargeStoreDeviceTest {
    @Test fun aStoreBiggerThanOneMessageTravelsEveryTime() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("the identity app is not installed", IdentityClient.isInstalled(ctx))
        val a = IdentityClient(ctx); val b = IdentityClient(ctx)
        val big = "ñ".repeat(700_000)
        try {
            a.call("storeSet", buildJsonObject { put("k", "kv:e2e.big"); put("v", big) })
            val reads = (1..20).map { i -> async { (if (i % 2 == 0) a else b).call("storeLoad") } }.awaitAll()
            for (r in reads) assertEquals(big.length, (r["items"] as JsonObject).getValue("kv:e2e.big").jsonPrimitive.content.length)
        } finally {
            runCatching { a.call("storeRemove", buildJsonObject { put("k", "kv:e2e.big") }) }
            a.close(); b.close()
        }
    }
}
