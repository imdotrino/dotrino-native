package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Catching up with the vault's record: the port of `verifyActa`, `canAdopt` and `actaHash`
 * against a REAL chain sealed by `@dotrino/identity` (`test-vectors/gen.mjs`, block 8). The
 * reasons must be the pilar's, one by one: if they differ, the phone would adopt what the web
 * refuses, or the other way round.
 */
class ActaSyncTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject["actaSync"]!!.jsonObject
    private val actas = v["actas"]!!.jsonObject.mapValues { it.value.jsonObject }
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test fun theHashIsThePilars() {
        for ((name, h) in v["hashes"]!!.jsonObject) assertEquals(name, (h as JsonPrimitive).content, Acta.hash(actas.getValue(name)))
    }

    @Test fun verifyGivesThePilarsReasons() {
        for (c in v["verify"] as JsonArray) {
            val o = c.jsonObject
            val name = s(o, "name")!!
            val expected = o["reason"].takeIf { it != null && it !is JsonNull }?.let { (it as JsonPrimitive).content }
            assertEquals(name, expected, Acta.verify(actas.getValue(name)))
        }
    }

    @Test fun adoptGivesThePilarsDecisionAndReason() {
        for (c in v["adopt"] as JsonArray) {
            val o = c.jsonObject
            val cand = actas.getValue(s(o, "candidate")!!)
            val cur = s(o, "current")?.let { actas.getValue(it) }
            val (adopt, reason) = Acta.canAdopt(cand, cur)
            val what = "${s(o, "candidate")} over ${s(o, "current")}"
            assertEquals(what, (o["adopt"] as JsonPrimitive).content.toBoolean(), adopt)
            assertEquals(what, s(o, "reason"), reason)
        }
    }

    @Test fun aChainIsAdoptedLinkByLinkAsThePilarDoes() {
        // The vault sends what came after mine, in any order and with junk mixed in; each link is
        // judged against the last one adopted. Which one wins is what the pilar says.
        val c = v["chain"]!!.jsonObject
        val order = (c["order"] as JsonArray).map { (it as JsonPrimitive).content }
        val won = Acta.adoptChain(order.map { actas.getValue(it) }, actas.getValue(s(c, "from")!!))
        assertEquals(Acta.hash(actas.getValue(s(c, "won")!!)), won?.let(Acta::hash))
        // Nothing newer than what I have: nothing to adopt.
        assertNull(Acta.adoptChain(listOf(actas.getValue("g2"), actas.getValue("g1")), actas.getValue("g3")))
    }
}
