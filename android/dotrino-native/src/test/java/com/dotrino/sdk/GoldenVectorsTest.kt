package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * Everything the phone signs or opens has to be byte-identical to what the JS pilar does.
 * The vectors come from `@dotrino/identity` itself (`test-vectors/gen.mjs`); a mismatch here
 * is a signature the vault would reject or an envelope the phone could not open.
 */
class GoldenVectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("vectors.json")!!.readText()
    ).jsonObject

    /** A key from its private JWK, in software. Test-only: production keys live in the Keystore. */
    private class SoftwareKeys(signJwk: JsonObject?, encJwk: JsonObject?) : DeviceKeys {
        private fun priv(jwk: JsonObject): PrivateKey {
            val d = BigInteger(1, Base64.getUrlDecoder().decode(jwk["d"]!!.jsonPrimitive.content))
            val params = (Crypto.publicKeyOf(pubOf(jwk)) as ECPublicKey).params
            return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params))
        }
        private fun pubOf(jwk: JsonObject) =
            """{"kty":"EC","crv":"P-256","x":"${jwk["x"]!!.jsonPrimitive.content}","y":"${jwk["y"]!!.jsonPrimitive.content}"}"""
        private val signKey = signJwk?.let(::priv)
        private val encKey = encJwk?.let(::priv)
        override val publickey = signJwk?.let(::pubOf) ?: ""
        override val encPub = encJwk?.let(::pubOf) ?: ""
        override fun sign(text: String): String {
            val s = Signature.getInstance("SHA256withECDSA"); s.initSign(signKey); s.update(text.toByteArray(Charsets.UTF_8))
            return Crypto.b64(Crypto.derToP1363(s.sign()))
        }
        override fun agree(peer: PublicKey): ByteArray {
            val ka = KeyAgreement.getInstance("ECDH"); ka.init(encKey); ka.doPhase(peer, true); return ka.generateSecret()
        }
    }

    @Test fun canonicalMatchesThePilar() {
        for (c in v["canon"] as JsonArray) {
            val o = c.jsonObject
            assertEquals(o["canonical"]!!.jsonPrimitive.content, Canonical.stringify(o["input"]!!))
        }
    }

    @Test fun canonicalRefusesFractions() {
        assertThrows(IllegalArgumentException::class.java) { Canonical.stringify(JsonObject(mapOf("a" to JsonPrimitive(1.5)))) }
    }

    @Test fun aSignatureMadeByThePilarVerifiesHere() {
        val s = v["sign"]!!.jsonObject
        val data = s["data"]!!.jsonObject
        assertTrue(Crypto.verify(s["publickey"]!!.jsonPrimitive.content, data, s["signature"]!!.jsonPrimitive.content))
        val tampered = JsonObject(data + ("ts" to JsonPrimitive(1)))
        assertFalse(Crypto.verify(s["publickey"]!!.jsonPrimitive.content, tampered, s["signature"]!!.jsonPrimitive.content))
    }

    @Test fun whatThePhoneSignsVerifiesAsThePilarWould() {
        val s = v["sign"]!!.jsonObject
        val keys = SoftwareKeys(s["privateJwk"]!!.jsonObject, null)
        assertTrue("same JWK shape as the pilar", Delegation.samePubkey(keys.publickey, s["publickey"]!!.jsonPrimitive.content))
        val data = s["data"]!!.jsonObject
        val sig = keys.sign(Canonical.stringify(data))
        assertEquals(64, Base64.getDecoder().decode(sig).size)
        assertTrue(Crypto.verify(keys.publickey, data, sig))
    }

    @Test fun derAndP1363RoundTrip() {
        repeat(50) {
            val keys = SoftwareKeys(v["sign"]!!.jsonObject["privateJwk"]!!.jsonObject, null)
            val raw = Base64.getDecoder().decode(keys.sign("x$it"))
            assertTrue(raw.contentEquals(Crypto.derToP1363(Crypto.p1363ToDer(raw))))
        }
    }

    @Test fun opensWhatTheVaultSealedToThisDevice() {
        val s = v["sealed"]!!.jsonObject
        val keys = SoftwareKeys(null, s["encPrivateJwk"]!!.jsonObject)
        val plain = Crypto.openSealed(s["ctxWrap"]!!.jsonObject, s["ctxEnvelope"]!!.jsonObject, keys)
        assertEquals(s["ctxPlain"]!!.jsonPrimitive.content, plain)
    }

    @Test fun anEnvelopeForAnotherKeyDoesNotOpen() {
        val s = v["sealed"]!!.jsonObject
        val other = SoftwareKeys(null, v["sign"]!!.jsonObject["privateJwk"]!!.jsonObject)
        assertThrows(Exception::class.java) { Crypto.openSealed(s["ctxWrap"]!!.jsonObject, s["ctxEnvelope"]!!.jsonObject, other) }
    }

    @Test fun thePaperChecksAgainstThePinnedVault() {
        val c = v["cert"]!!.jsonObject
        val cert = c["cert"]!!.jsonObject
        val master = c["master"]!!.jsonPrimitive.content
        val sub = c["sub"]!!.jsonPrimitive.content
        assertNull(Delegation.check(cert, master, sub, "vault:approve"))
        assertEquals("scope", Delegation.check(cert, master, sub, "vault:admin"))
        assertEquals("sub", Delegation.check(cert, master, v["sealed"]!!.jsonObject["encPub"]!!.jsonPrimitive.content, null))
        assertEquals("paper-from-another-vault", Delegation.check(cert, sub, sub, null))
        val widened = JsonObject(cert + ("scope" to JsonArray(listOf(JsonPrimitive("vault:admin")))))
        assertEquals("bad-signature", Delegation.check(widened, master, sub, null))
    }

    @Test fun keyIdAndLabelMatchThePilar() {
        val k = v["keyid"]!!.jsonObject
        assertEquals(k["id"]!!.jsonPrimitive.content, Delegation.pubkeyId(k["publickey"]!!.jsonPrimitive.content))
        assertEquals(k["label"]!!.jsonPrimitive.content, Delegation.keyLabel(k["publickey"]!!.jsonPrimitive.content))
    }
}
