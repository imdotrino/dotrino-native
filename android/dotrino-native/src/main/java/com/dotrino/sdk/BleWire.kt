package com.dotrino.sdk

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * THE BLUETOOTH WIRE (`dotrino-proxy-client/docs/bluetooth.md`): the same bytes as
 * `src/ble/wire.js`, checked against its golden vector (`vectors.json` → `ble`). Pure: no radio
 * here, only JCA. [BluetoothDirect] carries it over the Android Bluetooth stack.
 */
object BleWire {
    const val SERVICE_UUID = "0be25149-664d-4d28-b496-3ca98a63fb00"
    const val HELLO_UUID = "0be25149-664d-4d28-b496-3ca98a63fb01"
    const val BEACON_UUID = "0be25149-664d-4d28-b496-3ca98a63fb02"
    const val RX_UUID = "0be25149-664d-4d28-b496-3ca98a63fb03"
    const val TX_UUID = "0be25149-664d-4d28-b496-3ca98a63fb04"

    /** Control frame through the proxy, sibling of WebRTC's `__cc_rtc__`. */
    const val TAG = "__cc_ble__"

    const val BID_BYTES = 8
    const val BID_ROTATE_MS = 15 * 60 * 1000L
    const val BEACON_MAX_BYTES = 512
    const val RECORD_MAX_BYTES = 1024 * 1024
    const val ANSWER_TIMEOUT_MS = 10_000L
    const val FIND_TIMEOUT_MS = 20_000L
    const val TAG_BYTES = 4

    const val DIR_TO_PERIPHERAL: Byte = 0
    const val DIR_TO_CENTRAL: Byte = 1

    const val TYPE_TEXT: Byte = 0
    const val TYPE_BINARY: Byte = 1
    const val TYPE_HELLO: Byte = 2

    private const val LAST = 0x01

    class WireException(val code: String, message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }
    fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    fun randomBid(): String = b64u(Crypto.randomBytes(BID_BYTES))

    // ---- read-only characteristics ----

    fun encodeHello(bid: String): ByteArray = """{"v":1,"bid":"$bid"}""".toByteArray(Charsets.UTF_8)

    /** The `bid` of a `hello` read, or null when it is not this version. */
    fun decodeHello(b: ByteArray): String? = try {
        val o = json.parseToJsonElement(String(b, Charsets.UTF_8)) as JsonObject
        if (o["v"]?.jsonPrimitive?.intOrNull == 1) (o["bid"] as? JsonPrimitive)?.takeIf { it.isString }?.content else null
    } catch (_: Exception) { null }

    fun encodeBeacon(kind: String, data: String): ByteArray {
        if (kind.isEmpty()) throw WireException("bad-beacon", "bluetooth: announce needs a kind")
        val b = JsonObject(mapOf("kind" to JsonPrimitive(kind), "data" to JsonPrimitive(data))).toString().toByteArray(Charsets.UTF_8)
        if (b.size > BEACON_MAX_BYTES) throw WireException("beacon-too-big", "bluetooth: what is announced takes ${b.size} bytes; at most $BEACON_MAX_BYTES")
        return b
    }

    /** `kind` to `data` of a `beacon` read, or null when it is empty or not understood. */
    fun decodeBeacon(b: ByteArray): Pair<String, String>? {
        if (b.isEmpty()) return null
        return try {
            val o = json.parseToJsonElement(String(b, Charsets.UTF_8)) as JsonObject
            val k = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val d = (o["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            k to d
        } catch (_: Exception) { null }
    }

    // ---- contact card (docs §3) ----

    const val KIND_PAIR = "pair"
    const val KIND_CONTACT = "contact"
    const val CONTACT_NICK_MAX = 40
    const val CONTACT_NICK_MAX_BYTES = 60

    /** A contact card that was announced: the identity's JWK string and the alias. */
    data class Contact(val publickey: String, val nickname: String)

    /** The alias cut to 40 UTF-16 units and 60 UTF-8 bytes, by whole code points, so it fits in the beacon. */
    fun trimNick(nickname: String): String {
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < nickname.length) {
            val cp = nickname.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val b = ch.toByteArray(Charsets.UTF_8).size
            if (out.length + ch.length > CONTACT_NICK_MAX || bytes + b > CONTACT_NICK_MAX_BYTES) break
            out.append(ch); bytes += b; i += ch.length
        }
        return out.toString()
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    /** `encodeURIComponent`, byte for byte (java.net.URLEncoder writes spaces as `+` and escapes `!*'()~`). */
    private fun uriComponent(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff)
            if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    /** The card announced with `kind: contact`: the messenger's `#to=` fragment, `to=<publickey b64url>&nick=<alias>`. */
    fun encodeContact(publickey: String, nickname: String = ""): String {
        if (publickey.isEmpty()) throw WireException("bad-contact", "bluetooth: a contact needs its publickey")
        val nick = trimNick(nickname)
        return "to=" + b64u(publickey.toByteArray(Charsets.UTF_8)) + (if (nick.isNotEmpty()) "&nick=" + uriComponent(nick) else "")
    }

    /** The card of an announced `contact`, or null when it is not understood. */
    fun decodeContact(data: String): Contact? = try {
        fun param(k: String) = Regex("(?:^|&)$k=([^&]*)").find(data)?.groupValues?.get(1)
        val to = param("to")
        if (to.isNullOrEmpty()) null else {
            val publickey = String(unb64u(to), Charsets.UTF_8)
            val jwk = json.parseToJsonElement(publickey) as? JsonObject
            if (jwk == null || (jwk["x"] as? JsonPrimitive)?.isString != true || (jwk["y"] as? JsonPrimitive)?.isString != true) null
            else Contact(publickey, param("nick")?.let { trimNick(java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")) } ?: "")
        }
    } catch (_: Exception) { null }

    // ---- channel key ----

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    class Ephemeral(val privateKey: PrivateKey, val epub: String)

    fun makeEphemeral(): Ephemeral {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = kp.public as ECPublicKey
        return Ephemeral(kp.private, b64u(byteArrayOf(4) + u32(pub.w.affineX) + u32(pub.w.affineY)))
    }

    private fun u32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> throw IllegalArgumentException("ec coordinate longer than 32 bytes")
        }
    }

    private fun publicFromX963(raw: ByteArray): ECPublicKey {
        if (raw.size != 65 || raw[0] != 4.toByte()) throw WireException("bad-epub", "bluetooth: the other key is not an uncompressed P-256 point")
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** The channel's AES key (docs §4), 32 raw bytes. The order of the tokens does not depend on who calls. */
    fun deriveKey(mine: PrivateKey, theirEpub: String, tokenA: String, tokenB: String): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(mine)
        ka.doPhase(publicFromX963(unb64u(theirEpub)), true)
        val secret = ka.generateSecret()
        val (lo, hi) = if (tokenA < tokenB) tokenA to tokenB else tokenB to tokenA
        // HKDF-SHA256, one block: extract with the salt, expand with the info.
        val prk = hmac("$lo|$hi".toByteArray(Charsets.UTF_8), secret)
        return hmac(prk, "dotrino-ble-v1".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
    }

    // ---- records ----

    private fun nonce(dir: Byte, counter: Long): ByteArray {
        val n = ByteArray(12)
        n[0] = dir
        for (i in 0 until 8) n[4 + i] = (counter ushr (8 * (7 - i))).toByte()
        return n
    }

    /** `AES-256-GCM(key, dir‖000‖counter, type‖body)`: ciphertext ‖ tag, as WebCrypto writes it. */
    fun sealRecord(key: ByteArray, dir: Byte, counter: Long, type: Byte, body: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(dir, counter)))
        return c.doFinal(byteArrayOf(type) + body)
    }

    /** `type` to `body`, or throws when that key did not seal it with that counter. */
    fun openRecord(key: ByteArray, dir: Byte, counter: Long, record: ByteArray): Pair<Byte, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(dir, counter)))
        val plain = c.doFinal(record)
        if (plain.isEmpty()) throw WireException("bad-record", "bluetooth: empty record")
        return plain[0] to plain.copyOfRange(1, plain.size)
    }

    // ---- chunks ----

    /** The tag of the chunks towards a central: the first 4 bytes of its `bid` (docs §5). */
    fun tagOf(bid: String): ByteArray = unb64u(bid).copyOfRange(0, TAG_BYTES)

    fun hasTag(chunk: ByteArray, tag: ByteArray): Boolean =
        chunk.size >= 1 + TAG_BYTES && (0 until TAG_BYTES).all { chunk[1 + it] == tag[it] }

    /** Splits a record into GATT values of [chunk] bytes, header included; with [tag] (towards a central) after the flags. */
    fun toChunks(record: ByteArray, chunk: Int, tag: ByteArray? = null): List<ByteArray> {
        val head = 1 + (if (tag == null) 0 else TAG_BYTES)
        val room = maxOf(1, chunk - head)
        val out = ArrayList<ByteArray>()
        var i = 0
        do {
            val end = minOf(i + room, record.size)
            val part = record.copyOfRange(minOf(i, record.size), end)
            i += room
            val c = ByteArray(head + part.size)
            c[0] = if (i >= record.size) LAST.toByte() else 0
            tag?.copyInto(c, 1)
            part.copyInto(c, head)
            out.add(c)
        } while (i < record.size)
        return out
    }

    /** Rebuilds records from chunks that arrive in order; [skip] = header bytes after the flags (the tag, on `tx`). */
    class Reassembler(private val skip: Int = 0) {
        private val buf = java.io.ByteArrayOutputStream()
        fun push(chunk: ByteArray): ByteArray? {
            if (chunk.size < 1 + skip) return null
            buf.write(chunk, 1 + skip, chunk.size - 1 - skip)
            if (buf.size() > RECORD_MAX_BYTES + 64) {
                buf.reset()
                throw WireException("record-too-big", "bluetooth: record larger than 1 MiB")
            }
            if (chunk[0].toInt() and LAST == 0) return null
            return buf.toByteArray().also { buf.reset() }
        }
    }
}
