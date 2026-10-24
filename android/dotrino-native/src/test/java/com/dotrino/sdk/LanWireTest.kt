package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** The local-network TXT, string by string as `@dotrino/proxy-client` writes it (`src/lan/mdns.js`). */
class LanWireTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject["lan"]!!.jsonObject

    @Test fun txtIsTheSameStrings() {
        assertEquals(v["service"]!!.jsonPrimitive.content, LanWire.SERVICE_TYPE + ".local")
        for (e in v["cases"]!!.jsonArray.map { it.jsonObject }) {
            val kind = e["kind"]!!.jsonPrimitive.content
            val data = e["data"]!!.jsonPrimitive.content
            val want = e["txt"]!!.jsonArray.map { Base64.getDecoder().decode(it.jsonPrimitive.content).toString(Charsets.UTF_8) }
            val got = LanWire.txtEntries(kind, data)
            assertEquals(want, got.map { (k, b) -> k + "=" + b.toString(Charsets.UTF_8) })
            assertEquals(kind to data, LanWire.fromTxt(got.toMap()))
        }
    }

    @Test fun incompleteTxtIsIgnored() {
        val m = LanWire.txtEntries("pair", "x".repeat(300)).toMap().toMutableMap<String, ByteArray?>()
        m.remove("d1")
        assertNull(LanWire.fromTxt(m))
        assertNull(LanWire.fromTxt(mapOf("k" to "pair".toByteArray(), "n" to "0".toByteArray())))
    }
}
