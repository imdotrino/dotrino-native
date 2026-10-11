package com.dotrino.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement

/** The key a vault hands over at sign-in (user and password): kept sealed, used like any other. */
@RunWith(AndroidJUnit4::class)
class ImportedKeysTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "test-" + System.nanoTime()

    @After fun cleanup() { PhoneKeys.delete(ctx, id) }

    private fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun b64u(n: BigInteger): String {
        val b = n.toByteArray().let { if (it.size == 33) it.copyOfRange(1, 33) else ByteArray(32 - it.size) + it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
    private fun privJwk(k: KeyPair): JsonObject = buildJsonObject {
        val pub = k.public as ECPublicKey
        put("kty", "EC"); put("crv", "P-256"); put("x", b64u(pub.w.affineX)); put("y", b64u(pub.w.affineY))
        put("d", b64u((k.private as ECPrivateKey).s))
    }

    @Test fun signsAndAgreesLikeTheKeyTheVaultHolds() = runBlocking<Unit> {
        val s = pair(); val e = pair()
        val publickey = Crypto.jwkOf(s.public as ECPublicKey); val encPub = Crypto.jwkOf(e.public as ECPublicKey)
        ImportedKeys.import(ctx, id, privJwk(s), privJwk(e), publickey, encPub)
        assertTrue(PhoneKeys.exists(ctx, id))

        // Reopened from disk, as after the app restarts.
        val k = PhoneKeys.open(ctx, id)
        assertEquals(publickey, k.publickey); assertEquals(encPub, k.encPub)
        val data = JsonObject(mapOf("op" to JsonPrimitive("approvals"), "ts" to JsonPrimitive(1790000000000)))
        assertTrue(Crypto.verify(publickey, data, k.sign(Canonical.stringify(data))))

        val peer = pair()
        val theirs = KeyAgreement.getInstance("ECDH").apply { init(peer.private); doPhase(e.public, true) }.generateSecret()
        assertArrayEquals(theirs, k.agree(peer.public))
    }

    @Test fun nothingReadableOnDisk() {
        val s = pair(); val e = pair()
        val jwk = privJwk(s)
        ImportedKeys.import(ctx, id, jwk, privJwk(e), Crypto.jwkOf(s.public as ECPublicKey), Crypto.jwkOf(e.public as ECPublicKey))
        val raw = String(File(ctx.filesDir, "dotrino-imported-$id.bin").readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains((jwk.getValue("d") as JsonPrimitive).content))
    }

    @Test fun aPrivateKeyThatIsNotThePairIsRefused() {
        val s = pair(); val e = pair(); val other = pair()
        assertThrows(IllegalArgumentException::class.java) {
            ImportedKeys.import(ctx, id, privJwk(s), privJwk(e), Crypto.jwkOf(other.public as ECPublicKey), Crypto.jwkOf(e.public as ECPublicKey))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportedKeys.import(ctx, id, privJwk(s), privJwk(e), Crypto.jwkOf(s.public as ECPublicKey), Crypto.jwkOf(other.public as ECPublicKey))
        }
        assertFalse(PhoneKeys.exists(ctx, id))
    }

    @Test fun deletingTheAccountDeletesItsKey() {
        val s = pair(); val e = pair()
        ImportedKeys.import(ctx, id, privJwk(s), privJwk(e), Crypto.jwkOf(s.public as ECPublicKey), Crypto.jwkOf(e.public as ECPublicKey))
        PhoneKeys.delete(ctx, id)
        assertFalse(PhoneKeys.exists(ctx, id))
    }
}
