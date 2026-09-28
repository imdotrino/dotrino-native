package com.dotrino.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * Keys in SOFTWARE for the JVM tests (the Keystore and the identity app only exist on a
 * phone): from private JWKs that a JS harness wrote, or fresh.
 */
class TestKeys private constructor(private val s: PrivateKey, private val e: PrivateKey, override val publickey: String, override val encPub: String) : DeviceKeys {
    companion object {
        private fun pubOf(j: JsonObject) = """{"kty":"EC","crv":"P-256","x":"${j["x"]!!.jsonPrimitive.content}","y":"${j["y"]!!.jsonPrimitive.content}"}"""
        private fun priv(j: JsonObject): PrivateKey {
            val d = BigInteger(1, Base64.getUrlDecoder().decode(j["d"]!!.jsonPrimitive.content))
            return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, (Crypto.publicKeyOf(pubOf(j)) as ECPublicKey).params))
        }

        fun fromJwk(sign: JsonObject, enc: JsonObject) = TestKeys(priv(sign), priv(enc), pubOf(sign), pubOf(enc))

        fun fresh(): TestKeys {
            fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val s = pair(); val e = pair()
            return TestKeys(s.private, e.private, Crypto.jwkOf(s.public as ECPublicKey), Crypto.jwkOf(e.public as ECPublicKey))
        }

        /** Only the encryption half, from its private JWK (the sign half is fresh). */
        fun withEnc(enc: JsonObject): TestKeys {
            val f = fresh()
            return TestKeys(f.s, priv(enc), f.publickey, pubOf(enc))
        }
    }

    override suspend fun sign(text: String): String {
        val g = Signature.getInstance("SHA256withECDSA"); g.initSign(s); g.update(text.toByteArray(Charsets.UTF_8))
        return Crypto.b64(Crypto.derToP1363(g.sign()))
    }

    override suspend fun agree(peer: PublicKey): ByteArray {
        val k = KeyAgreement.getInstance("ECDH"); k.init(e); k.doPhase(peer, true); return k.generateSecret()
    }

    @Suppress("unused") private fun unused(x: ECPrivateKey) = x
}
