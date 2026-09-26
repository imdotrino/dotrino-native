package com.dotrino.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement

/** The real Keystore on a real Android: what JVM tests cannot reach. */
@RunWith(AndroidJUnit4::class)
class KeystoreKeysTest {
    private val id = "test-" + System.nanoTime()

    @After fun cleanup() { KeystoreKeys.delete(id) }

    @Test fun signsWhatThePilarVerifies() {
        val k = KeystoreKeys.create(id)
        val data = JsonObject(mapOf("op" to JsonPrimitive("approvals"), "ts" to JsonPrimitive(1790000000000)))
        val sig = k.sign(Canonical.stringify(data))
        assertEquals(64, Base64.getDecoder().decode(sig).size)
        assertTrue(Crypto.verify(k.publickey, data, sig))
        assertFalse(Crypto.verify(k.publickey, JsonObject(data + ("ts" to JsonPrimitive(1))), sig))
    }

    @Test fun ecdhAgreesWithASoftwarePeer() {
        val k = KeystoreKeys.create(id)
        val peer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val mine = k.agree(peer.public)
        val theirs = KeyAgreement.getInstance("ECDH").apply { init(peer.private); doPhase(Crypto.publicKeyOf(k.encPub), true) }.generateSecret()
        assertEquals(32, mine.size)
        assertArrayEquals(theirs, mine)
        assertEquals(k.publickey, Crypto.jwkOf(Crypto.publicKeyOf(k.publickey) as ECPublicKey))
    }

    @Test fun oneAccountOneKeyAndNoOverwrite() {
        KeystoreKeys.create(id)
        assertTrue(KeystoreKeys.exists(id))
        assertThrows(IllegalStateException::class.java) { KeystoreKeys.create(id) }
        KeystoreKeys.delete(id)
        assertFalse(KeystoreKeys.exists(id))
        assertThrows(IllegalStateException::class.java) { KeystoreKeys.open(id) }
    }
}
