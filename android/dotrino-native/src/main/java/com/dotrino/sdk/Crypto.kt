package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two keys of ONE account on this device. The private halves never leave wherever they
 * live (Android Keystore in production): this interface only asks them to do their job.
 *
 * - `publickey`: signing key (ECDSA P-256) as the ecosystem writes it, `{"kty","crv","x","y"}`.
 * - `encPub`: encryption key (ECDH P-256), same shape. It goes into the record so the vault
 *   can seal things (the command of a request) to this device.
 */
interface DeviceKeys {
    val publickey: String
    val encPub: String
    /** Signs the UTF-8 bytes of [text] and returns the P1363 (r‖s) signature in base64. */
    fun sign(text: String): String
    /** Raw ECDH shared secret (the x coordinate, 32 bytes) with [peer]. */
    fun agree(peer: PublicKey): ByteArray
}

object Crypto {
    private val json = Json { ignoreUnknownKeys = true }

    /** Standard base64 with padding: what `btoa` produces in the JS pilar. */
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun fromB64(s: String): ByteArray = Base64.getDecoder().decode(s)
    private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun fromB64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    /** `{"kty":"EC","crv":"P-256","x":…,"y":…}` — the same field order WebCrypto-derived keys use in the ecosystem. */
    fun jwkOf(key: ECPublicKey): String {
        val x = unsigned32(key.w.affineX)
        val y = unsigned32(key.w.affineY)
        return """{"kty":"EC","crv":"P-256","x":"${b64url(x)}","y":"${b64url(y)}"}"""
    }

    fun publicKeyOf(jwk: String): ECPublicKey {
        val o = json.parseToJsonElement(jwk) as JsonObject
        require(o["crv"]?.jsonPrimitive?.content == "P-256") { "jwk: only P-256 keys" }
        val x = BigInteger(1, fromB64url(o.getValue("x").jsonPrimitive.content))
        val y = BigInteger(1, fromB64url(o.getValue("y").jsonPrimitive.content))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey
    }

    private fun unsigned32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> throw IllegalArgumentException("ec coordinate longer than 32 bytes")
        }
    }

    /** DER (what `SHA256withECDSA` gives) → P1363 r‖s, 64 bytes (what WebCrypto gives). */
    fun derToP1363(der: ByteArray): ByteArray {
        var i = 0
        require(der[i++] == 0x30.toByte()) { "der: not a sequence" }
        var len = der[i++].toInt() and 0xff
        if (len and 0x80 != 0) i += len and 0x7f
        fun int(): ByteArray {
            require(der[i++] == 0x02.toByte()) { "der: not an integer" }
            val l = der[i++].toInt() and 0xff
            val v = der.copyOfRange(i, i + l); i += l
            return unsigned32(BigInteger(1, v))
        }
        return int() + int()
    }

    fun p1363ToDer(sig: ByteArray): ByteArray {
        require(sig.size == 64) { "p1363: expected 64 bytes" }
        fun enc(part: ByteArray): ByteArray {
            var v = BigInteger(1, part).toByteArray()
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = enc(sig.copyOfRange(0, 32)) + enc(sig.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** `verifyDeviceSig` of the pilar: P1363 base64 signature over the canonical text of [data]. */
    fun verify(publickey: String, data: JsonObject, signature: String): Boolean = try {
        val v = Signature.getInstance("SHA256withECDSA")
        v.initVerify(publicKeyOf(publickey))
        v.update(Canonical.stringify(data).toByteArray(Charsets.UTF_8))
        v.verify(p1363ToDer(fromB64(signature)))
    } catch (_: Exception) { false }

    /**
     * `openWrap` of the pilar (`vault/content.js`): ECDH between my encryption key and the
     * ephemeral `epk`, the 32 raw bytes as the AES-GCM key, and out comes the content key
     * (a base64 string).
     */
    fun openWrap(wrap: JsonObject, keys: DeviceKeys): String {
        val epk = wrap["epk"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("invalid wrap")
        val iv = wrap["iv"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("invalid wrap")
        val ct = wrap["ct"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("invalid wrap")
        val secret = keys.agree(publicKeyOf(epk))
        return String(aesGcmOpen(secret, fromB64(iv), fromB64(ct)), Charsets.UTF_8)
    }

    /** `decryptWithCek`: the envelope `{ iv, ct }` with a content key given as base64. */
    fun decryptWithCek(cek: String, envelope: JsonObject): String {
        val iv = envelope["iv"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("invalid envelope")
        val ct = envelope["ct"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("invalid envelope")
        return String(aesGcmOpen(fromB64(cek), fromB64(iv), fromB64(ct)), Charsets.UTF_8)
    }

    /** What the vault seals to the approver (the command of a request): a wrap plus an envelope. */
    fun openSealed(wrap: JsonObject, envelope: JsonObject, keys: DeviceKeys): String =
        decryptWithCek(openWrap(wrap, keys), envelope)

    private fun aesGcmOpen(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }
}
