package com.dotrino.sdk.webrtc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dotrino.sdk.KeystoreKeys
import com.dotrino.sdk.Profile
import com.dotrino.sdk.SealedSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE DIRECT ROAD against the JS pillar (`test-vectors/e2e-direct.mjs`): the phone redeems the
 * JS client's code, writes sealed; the first message goes by the proxy, the channel opens
 * underneath, and the next one goes DIRECT — the JS side says through which road it arrived.
 */
@RunWith(AndroidJUnit4::class)
class DirectE2eTest {
    @Test fun firstByProxyThenDirect() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url"); val code = args.getString("code")
        assumeTrue("run with -e url … -e code … (test-vectors/e2e-direct.mjs)", url != null && code != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val kid = "e2e-direct-" + System.nanoTime()
        val profile = Profile.of(KeystoreKeys.create(kid))
        val direct = WebRtcDirect(ctx)
        val session = SealedSession(listOf(url!!), profile, "messenger")
        session.useDirect(direct)
        val pongs = Channel<JsonObject>(Channel.UNLIMITED)
        session.onMessage { m -> if (m.payload["type"]?.jsonPrimitive?.content == "PONG") pongs.trySend(m.payload) }
        try {
            session.start()
            assertTrue("offline: ${session.status}", session.awaitOnline())
            val token = session.redeemPairingCode(code!!)
            assertNotNull("the JS client did not greet back", session.whoIs(token))

            session.sendSealedTo(token, buildJsonObject { put("type", "PING"); put("n", 1) })
            val first = withTimeout(20_000) { pongs.receive() }
            assertEquals("proxy", first["gotVia"]?.jsonPrimitive?.content)

            // The channel was being negotiated underneath since the first message.
            withTimeout(30_000) { while (!direct.isOpen(token)) delay(200) }
            session.sendSealedTo(token, buildJsonObject { put("type", "PING"); put("n", 2) })
            val second = withTimeout(20_000) { pongs.receive() }
            assertEquals(2, second["n"]?.jsonPrimitive?.content?.toInt())
            assertEquals("the second message did not go direct", "webrtc", second["gotVia"]?.jsonPrimitive?.content)
        } finally {
            session.close()
            KeystoreKeys.delete(kid)
        }
    }
}
