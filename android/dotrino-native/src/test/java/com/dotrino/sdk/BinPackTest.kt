package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `binpack.js` and the binary sealed envelope of the transport pillar, from what the JS made. */
class BinPackTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject
    private val b = v.getValue("binSealed").jsonObject
    private fun bytes(k: String) = Crypto.fromB64(b.getValue(k).jsonPrimitive.content)

    @Test fun opensTheBinaryFrameTheJsPillarSealed() = runBlocking {
        val (header, body) = BinPack.decodeFrame(bytes("frame"))
        assertEquals("message", header["type"]?.jsonPrimitive?.content)
        val inc = ProxyConnection.incomingPackage("TOK-A", "PK-A", body)
        assertNotNull(inc)
        val packed = inc!!.packed!!
        assertTrue(BinPack.isBinSealed(packed))
        // The proxy cannot read it: the text of the message is not in the frame.
        assertFalse(String(bytes("frame"), Charsets.ISO_8859_1).contains("archivo"))

        val p = v.getValue("profile").jsonObject
        val phone = IdentitySealing(Profile.of(TestKeys.withEnc(p.getValue("encPrivateJwk").jsonObject)), "messenger")
        val opened = phone.openBinary(packed)
        // The bytes arrive as base64, where the file was.
        assertEquals(b.getValue("resolved").jsonObject, opened.payload)
        assertEquals(b.getValue("senderEncPub").jsonPrimitive.content, opened.senderEncPub)
    }

    @Test fun anUnsealedPackageReachesTheAppWithItsBytesAsBase64() {
        val inc = ProxyConnection.incomingPackage("T", null, bytes("plainPacked"))!!
        assertEquals(b.getValue("plainResolved").jsonObject, inc.payload)
        assertFalse(BinPack.isBinSealed(inc.packed!!))
    }

    @Test fun anotherProfileDoesNotOpenIt() {
        val (_, body) = BinPack.decodeFrame(bytes("frame"))
        val packed = BinPack.unpack(body)
        val stranger = IdentitySealing(Profile.of(TestKeys.fresh()), "messenger")
        assertThrows(Exception::class.java) { runBlocking { stranger.openBinary(packed) } }
        Unit
    }

    @Test fun packAndFrameRoundTrip() {
        val o = buildJsonObject { put("t", "x"); put("ñ", "✓") }
        val frame = BinPack.encodeFrame(buildJsonObject { put("to", "A") }, BinPack.pack(o))
        val (h, body) = BinPack.decodeFrame(frame)
        assertEquals("A", h["to"]?.jsonPrimitive?.content)
        assertEquals(o, BinPack.unpack(body).json)
        assertArrayEquals(BinPack.pack(o), body)
    }

    @Test fun whatIsNotAPackageOrAFrameIsSaidWithItsCode() {
        assertEquals("bad-frame", assertThrows(BinPack.BadPack::class.java) { BinPack.decodeFrame(byteArrayOf(9, 0, 0, 0, 0)) }.code)
        assertEquals("bad-pack", assertThrows(BinPack.BadPack::class.java) { BinPack.unpack(byteArrayOf(1, 2)) }.code)
        // A reference that points outside the package.
        val head = """{"x":{"__dotrino_bin":[0,99]}}""".toByteArray()
        val bad = java.nio.ByteBuffer.allocate(5 + head.size + 3).put(0xB1.toByte()).putInt(head.size).put(head).put(byteArrayOf(1, 2, 3)).array()
        assertEquals("bad-pack", assertThrows(BinPack.BadPack::class.java) { BinPack.unpack(bad) }.code)
    }
}
