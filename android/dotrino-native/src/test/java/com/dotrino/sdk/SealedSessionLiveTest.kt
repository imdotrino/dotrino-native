package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two phones against a REAL proxy: pair with the short code, find out whose the token is,
 * write sealed by token and by pubkey. Only runs with `DOTRINO_LIVE_PROXY=wss://…`.
 */
class SealedSessionLiveTest {
    private val url: String? = System.getenv("DOTRINO_LIVE_PROXY")

    @Test fun pairAndTalkSealed() = runBlocking {
        assumeTrue("set DOTRINO_LIVE_PROXY to run", url != null)
        val ana = Profile.of(TestKeys.fresh()); val beto = Profile.of(TestKeys.fresh())
        val a = SealedSession(listOf(url!!), ana, "messenger"); val b = SealedSession(listOf(url), beto, "messenger")
        try {
            a.start(); b.start()
            assert(a.awaitOnline()) { "ana offline: ${a.status}" }
            assert(b.awaitOnline()) { "beto offline: ${b.status}" }

            val gotB = CompletableDeferred<SealedSession.Message>()
            b.onMessage { gotB.complete(it) }
            val code = a.requestPairingCode()
            val anaToken = b.redeemPairingCode(code.code)
            val who = b.whoIs(anaToken)
            assertNotNull("the greeting said nothing", who)
            assert(Delegation.samePubkey(who, ana.publickey))

            val gotA = CompletableDeferred<SealedSession.Message>()
            a.onMessage { gotA.complete(it) }
            b.sendSealedTo(anaToken, buildJsonObject { put("type", "CONTACT_REQUEST"); put("nickname", "Beto") })
            val m = withTimeout(15_000) { gotA.await() }
            assertEquals("CONTACT_REQUEST", m.payload["type"]!!.jsonPrimitive.content)
            assertEquals(beto.encPub, m.senderEncPub)

            // Back by PUBKEY (offline queue road), with Beto's key found and verified by the proxy.
            a.sendSealed(beto.publickey, buildJsonObject { put("type", "CONTACT_ACCEPT") }, quiet = true)
            val r = withTimeout(15_000) { gotB.await() }
            assertEquals("CONTACT_ACCEPT", r.payload["type"]!!.jsonPrimitive.content)
            assertEquals(ana.encPub, r.senderEncPub)
            assert(Delegation.samePubkey(r.fromPubkey, ana.publickey)) { "the proxy did not say who routed it" }
        } finally { a.close(); b.close() }
    }
}
