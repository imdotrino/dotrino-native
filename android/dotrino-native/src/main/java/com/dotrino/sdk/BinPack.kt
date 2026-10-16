package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer

/**
 * BYTES THAT TRAVEL AS BYTES: `binpack.js` of `@dotrino/proxy-client` (≥ 0.35), the two binary
 * formats of the transport.
 *
 *  · THE PACKAGE: an object with bytes inside.
 *      [0xB1][u32 length of the JSON][JSON][the bytes, one after another]
 *    Each byte array is a `{"__dotrino_bin":[from,length]}` reference in the JSON.
 *
 *  · THE FRAME: what goes through the proxy's WebSocket.
 *      [0x01][u32 length of the header][header JSON][body]
 *    The header is the usual text frame without `message`; the body is the `message`.
 *
 * And the binary sealed envelope (`sealing.js`): a package `{ bv: 1, k, iv, ct }` where `k` is
 * the usual sealed envelope with `{ cek }` inside and `iv`/`ct` are the package of the message,
 * AES-GCM encrypted with that key.
 *
 * The apps of this library speak JSON: the bytes of a package reach them as BASE64 strings in
 * its place ([resolved]), which is how a file travelled before the transport went binary.
 */
object BinPack {
    const val BIN_TAG = "__dotrino_bin"
    const val BIN_SEALED = 1
    private const val PACK_MAGIC = 0xB1.toByte()
    private const val FRAME_VERSION: Byte = 0x01
    private val json = Json { ignoreUnknownKeys = true }

    class BadPack(message: String, val code: String) : Exception(message)

    /** A package, opened: its JSON (with the references) and the area the references point into. */
    class Packed(val json: JsonElement, val area: ByteArray) {
        /** The bytes a `{"__dotrino_bin":[from,length]}` reference points to, or null if [v] is not one. */
        fun bytesOf(v: JsonElement?): ByteArray? {
            val (off, len) = refOf(v) ?: return null
            return area.copyOfRange(off, off + len)
        }

        /** The JSON with each reference replaced by its bytes in base64 (what the apps read). */
        fun resolved(): JsonElement = resolve(json)

        private fun resolve(v: JsonElement): JsonElement = when (v) {
            is JsonArray -> JsonArray(v.map { resolve(it) })
            is JsonObject -> bytesOf(v)?.let { JsonPrimitive(Crypto.b64(it)) } ?: JsonObject(v.mapValues { resolve(it.value) })
            else -> v
        }
    }

    private fun refOf(v: JsonElement?): Pair<Int, Int>? {
        val r = (v as? JsonObject)?.get(BIN_TAG) as? JsonArray ?: return null
        return Pair((r[0] as JsonPrimitive).intOrNull!!, (r[1] as JsonPrimitive).intOrNull!!)
    }

    fun isPacked(b: ByteArray): Boolean = b.size >= 5 && b[0] == PACK_MAGIC

    /** A package → its JSON and its bytes. Throws `bad-pack` if it is not one or a reference leaves it. */
    fun unpack(b: ByteArray): Packed {
        if (!isPacked(b)) throw BadPack("not a packed message", "bad-pack")
        val len = ByteBuffer.wrap(b, 1, 4).int.toLong() and 0xFFFFFFFFL
        if (5 + len > b.size) throw BadPack("packed message is shorter than its header says", "bad-pack")
        val area = b.copyOfRange(5 + len.toInt(), b.size)
        val obj = try { json.parseToJsonElement(String(b, 5, len.toInt(), Charsets.UTF_8)) }
            catch (_: Exception) { throw BadPack("packed message header is not JSON", "bad-pack") }
        checkRefs(obj, area.size)
        return Packed(obj, area)
    }

    private fun checkRefs(v: JsonElement, size: Int) {
        when (v) {
            is JsonArray -> v.forEach { checkRefs(it, size) }
            is JsonObject -> {
                val r = v[BIN_TAG]
                if (r == null) { v.values.forEach { checkRefs(it, size) }; return }
                val a = r as? JsonArray
                val off = (a?.getOrNull(0) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                val len = (a?.getOrNull(1) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                if (a == null || a.size != 2 || off == null || len == null || off < 0 || len < 0 || off.toLong() + len > size) {
                    throw BadPack("packed message points outside itself", "bad-pack")
                }
            }
            else -> {}
        }
    }

    /** An object without bytes → a package (what goes on the wire for it). */
    fun pack(obj: JsonElement): ByteArray {
        val head = obj.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(5 + head.size).put(PACK_MAGIC).putInt(head.size).put(head).array()
    }

    /** The proxy's binary frame → its header and its body. Throws `bad-frame` if it is not one. */
    fun decodeFrame(b: ByteArray): Pair<JsonObject, ByteArray> {
        if (b.size < 5 || b[0] != FRAME_VERSION) throw BadPack("not a binary frame of a known version", "bad-frame")
        val len = ByteBuffer.wrap(b, 1, 4).int.toLong() and 0xFFFFFFFFL
        if (5 + len > b.size) throw BadPack("binary frame is shorter than its header says", "bad-frame")
        val header = try { json.parseToJsonElement(String(b, 5, len.toInt(), Charsets.UTF_8)) as? JsonObject }
            catch (_: Exception) { null } ?: throw BadPack("binary frame header is not a JSON object", "bad-frame")
        return Pair(header, b.copyOfRange(5 + len.toInt(), b.size))
    }

    /** The other way round: a header and a body → the frame. */
    fun encodeFrame(header: JsonObject, body: ByteArray): ByteArray {
        val head = header.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(5 + head.size + body.size).put(FRAME_VERSION).putInt(head.size).put(head).put(body).array()
    }

    /** Is this package a binary sealed envelope (`{ bv: 1, k, iv, ct }`)? */
    fun isBinSealed(p: Packed): Boolean {
        val o = p.json as? JsonObject ?: return false
        return (o["bv"] as? JsonPrimitive)?.intOrNull == BIN_SEALED && o["k"] is JsonObject &&
            refOf(o["iv"]) != null && refOf(o["ct"]) != null
    }
}
