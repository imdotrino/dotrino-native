package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The store's rules against `@dotrino/store` itself (vectors.json): digest and reconciliation plan. */
class StoreCoreTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject.getValue("store").jsonObject

    @Test fun theDigestIsTheOneTheBrowserAndTheVaultCompute() {
        for (c in v.getValue("digests").jsonArray) {
            val o = c.jsonObject
            assertEquals(o.getValue("digest").jsonPrimitive.content, StoreThreads.digest(o.getValue("entries").jsonArray.map { it.jsonObject }))
        }
    }

    private fun index(o: JsonObject) = StoreThreads.Index(
        o.getValue("items").jsonArray.map { r -> r.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content.toLong() } },
        o.getValue("tombs").jsonArray.map { r -> r.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content.toLong(), it[2].jsonPrimitive.content.toLong()) } },
    )

    @Test fun thePlanIsTheOneTheBrowserMakes() {
        for (c in v.getValue("plans").jsonArray) {
            val o = c.jsonObject
            val p = StoreThreads.plan(index(o.getValue("local").jsonObject), index(o.getValue("remote").jsonObject), o.getValue("max").jsonPrimitive.content.toInt())
            val want = o.getValue("plan").jsonObject
            fun ids(k: String) = (want.getValue(k) as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(ids("pushIds"), p.pushIds); assertEquals(ids("pushTombs"), p.pushTombs); assertEquals(ids("pullIds"), p.pullIds)
            assertEquals((want.getValue("pullTombs") as JsonArray).map { r -> r.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content.toLong(), it[2].jsonPrimitive.content.toLong()) } }, p.pullTombs)
        }
    }
}
