package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `identitySealing` of the transport pillar: the wire `{ app, sealed, from }`, both ways. */
class SealingTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject

    @Test fun opensWhatTheJsPillarSealed() = runBlocking {
        val p = v.getValue("profile").jsonObject
        val s = v.getValue("appSealed").jsonObject
        val phone = IdentitySealing(Profile.of(TestKeys.withEnc(p.getValue("encPrivateJwk").jsonObject)), "messenger")
        val opened = phone.open(s.getValue("envelope").jsonObject)
        assertEquals(s.getValue("msg").jsonObject, opened.payload)
        // Who sealed it: the key of the JS identity that wrote it.
        assertEquals(s.getValue("senderEncPub").jsonPrimitive.content, opened.senderEncPub)
    }

    @Test fun anotherAppsEnvelopeIsNotOurs() = runBlocking {
        val s = v.getValue("appSealed").jsonObject
        val lobby = IdentitySealing(Profile.of(TestKeys.fresh()), "dotrino-lobby")
        assertFalse(lobby.isSealed(s.getValue("envelope").jsonObject))
        assertThrows(IdentitySealing.SealingError::class.java) { runBlocking { lobby.open(s.getValue("envelope").jsonObject) } }
        Unit
    }

    @Test fun sealsToEveryDeviceGivenAndSaysWhoSealed() = runBlocking {
        val ana = Profile.of(TestKeys.fresh())
        val beto1 = Profile.of(TestKeys.fresh()); val beto2 = Profile.of(TestKeys.fresh())
        val msg = buildJsonObject { put("type", "HELLO"); put("nickname", "Ana") }
        val env = IdentitySealing(ana, "messenger").seal(msg, listOf(beto1.encPub, beto2.encPub))
        assertFalse("the type must not travel in the clear", env.toString().contains("HELLO"))
        for (b in listOf(beto1, beto2)) {
            val o = IdentitySealing(b, "messenger").open(env)
            assertEquals(msg, o.payload)
            assertEquals(ana.encPub, o.senderEncPub)
        }
        val stranger = IdentitySealing(Profile.of(TestKeys.fresh()), "messenger")
        assertThrows(Profile.ProfileError::class.java) { runBlocking { stranger.open(env) } }
        Unit
    }

    @Test fun nothingToSealToDoesNotGo() = runBlocking {
        val e = assertThrows(IdentitySealing.SealingError::class.java) {
            runBlocking { IdentitySealing(Profile.of(TestKeys.fresh()), "messenger").seal(buildJsonObject { }, emptyList()) }
        }
        assertEquals("unsealed", e.code)
        assertTrue(true)
    }
}
