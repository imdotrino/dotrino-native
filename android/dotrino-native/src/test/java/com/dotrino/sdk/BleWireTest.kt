package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Bluetooth wire, byte-identical to `@dotrino/proxy-client` (`src/ble/wire.js`): the vector is made by the JS pilar. */
class BleWireTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject["ble"]!!.jsonObject

    private fun s(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content
    private fun side(k: String) = v[k]!!.jsonObject
    private fun priv(k: String) = Crypto.privateKeyOf(s(side(k)["privateJwk"]!!.jsonObject, "d"))

    @Test fun uuidsMatch() {
        val u = v["uuids"]!!.jsonObject
        assertEquals(s(u, "service"), BleWire.SERVICE_UUID)
        assertEquals(s(u, "hello"), BleWire.HELLO_UUID)
        assertEquals(s(u, "beacon"), BleWire.BEACON_UUID)
        assertEquals(s(u, "rx"), BleWire.RX_UUID)
        assertEquals(s(u, "tx"), BleWire.TX_UUID)
    }

    @Test fun bothSidesDeriveTheJsKey() {
        val a = side("a"); val b = side("b")
        val want = Crypto.fromB64(s(v, "key"))
        assertArrayEquals(want, BleWire.deriveKey(priv("a"), s(b, "epub"), s(a, "token"), s(b, "token")))
        assertArrayEquals(want, BleWire.deriveKey(priv("b"), s(a, "epub"), s(b, "token"), s(a, "token")))
    }

    @Test fun recordIsTheSameBytes() {
        val key = Crypto.fromB64(s(v, "key"))
        val r = v["record"]!!.jsonObject
        val dir = r["dir"]!!.jsonPrimitive.int.toByte()
        val ctr = r["counter"]!!.jsonPrimitive.int.toLong()
        val sealed = BleWire.sealRecord(key, dir, ctr, r["type"]!!.jsonPrimitive.int.toByte(), s(r, "plain").toByteArray(Charsets.UTF_8))
        assertArrayEquals(Crypto.fromB64(s(r, "sealed")), sealed)
        val (type, body) = BleWire.openRecord(key, dir, ctr, sealed)
        assertEquals(BleWire.TYPE_TEXT, type)
        assertEquals(s(r, "plain"), String(body, Charsets.UTF_8))
        try { BleWire.openRecord(key, dir, ctr + 1, sealed); fail("opened with the wrong counter") } catch (_: Exception) {}
    }

    @Test fun chunksAndTagAreTheSameBytes() {
        val sealed = Crypto.fromB64(s(v["record"]!!.jsonObject, "sealed"))
        val tag = BleWire.tagOf(s(v, "bid"))
        assertArrayEquals(Crypto.fromB64(s(v, "tag")), tag)
        val tx = BleWire.toChunks(sealed, 20, tag)
        assertEquals(v["chunks"]!!.jsonArray.map { it.jsonPrimitive.content }, tx.map(Crypto::b64))
        assertEquals(v["rxChunks"]!!.jsonArray.map { it.jsonPrimitive.content }, BleWire.toChunks(sealed, 20).map(Crypto::b64))
        val r = BleWire.Reassembler(BleWire.TAG_BYTES)
        var out: ByteArray? = null
        for (c in tx) { assertTrue(BleWire.hasTag(c, tag)); out = r.push(c) ?: out }
        assertArrayEquals(sealed, out)
    }

    @Test fun helloAndBeacon() {
        assertArrayEquals(Crypto.fromB64(s(v, "hello")), BleWire.encodeHello(s(v, "bid")))
        assertEquals("abc", BleWire.decodeHello(BleWire.encodeHello("abc")))
        assertEquals("pair" to "cINVITE", BleWire.decodeBeacon(Crypto.fromB64(s(v, "beacon"))))
        assertNull(BleWire.decodeBeacon(ByteArray(0)))
        try { BleWire.encodeBeacon("pair", "x".repeat(600)); fail("too big") } catch (e: BleWire.WireException) { assertEquals("beacon-too-big", e.code) }
    }

    @Test fun ephemeralKeysAgree() {
        val a = BleWire.makeEphemeral(); val b = BleWire.makeEphemeral()
        assertArrayEquals(BleWire.deriveKey(a.privateKey, b.epub, "x", "y"), BleWire.deriveKey(b.privateKey, a.epub, "y", "x"))
    }
}
