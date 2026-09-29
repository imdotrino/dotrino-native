package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against the REAL registry (`rep.dotrino.com`): a phone rates someone, reads it back merged,
 * sees it weighed by its own trust, and withdraws it. Only with `DOTRINO_LIVE_REP=1`.
 */
class ReputationLiveTest {
    @Test fun rateReadAndWithdraw() = runBlocking {
        assumeTrue("set DOTRINO_LIVE_REP=1 to run", System.getenv("DOTRINO_LIVE_REP") == "1")
        // A profile WITH an acta (the registry refuses a signature without a chain).
        val v = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject
        val ap = v.getValue("actaProfile").jsonObject
        val acta = ap.getValue("acta").jsonObject
        // The member is found by the EXACT string of its key (as the identity writes it).
        val pub = acta.getValue("members").jsonArray[0].jsonObject.getValue("pub").jsonPrimitive.content
        val keys = TestKeys.fromJwk(ap.getValue("signPrivateJwk").jsonObject, ap.getValue("encPrivateJwk").jsonObject)
        val me = Profile.of(object : DeviceKeys by keys { override val publickey = pub }, acta)
        val subject = TestKeys.fresh().publickey
        val rep = Reputation(me, PeerBook(PeerBook.MemoryStorage(), me))
        rep.rate(subject, mapOf("confianza" to 4, "afinidad" to 2), notes = "prueba nativa")
        try {
            assertEquals(mapOf("confianza" to 4.0, "afinidad" to 2.0), rep.myIndicatorsFor(subject))
            val agg = rep.aggregateTrust(subject)
            assertEquals(1, agg.trustedCount)      // me, with credibility 1
            assertEquals(0.8, agg.score!!, 1e-9)   // 4 / 5
        } finally {
            rep.removeChannel(subject, "confianza"); rep.removeChannel(subject, "afinidad")
        }
        assertEquals(emptyMap<String, Double>(), Reputation(me, PeerBook(PeerBook.MemoryStorage(), me)).myIndicatorsFor(subject))
    }
}
