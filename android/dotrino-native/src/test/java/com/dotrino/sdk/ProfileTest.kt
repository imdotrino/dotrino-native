package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profile, the acta and the broadcast link, against what the JS pilar makes (vectors.json). */
class ProfileTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject

    @Test fun opensWhatAJsIdentityEncrypted() = runBlocking {
        val p = v.getValue("profile").jsonObject
        val profile = Profile.of(TestKeys.withEnc(p.getValue("encPrivateJwk").jsonObject))
        assertEquals(p.getValue("encKeyId").jsonPrimitive.content, Profile.encKeyId(profile.encPub))
        val text = profile.decrypt(p.getValue("senderEncPub").jsonPrimitive.content, p.getValue("envelope").jsonObject)
        assertEquals(p.getValue("plain").jsonPrimitive.content, text)
    }

    @Test fun encryptsWhatAnotherProfileOpens() = runBlocking {
        val a = Profile.of(TestKeys.fresh())
        val b = Profile.of(TestKeys.fresh())
        val env = a.encrypt(listOf(b.encPub), "hola ñandú")
        assertEquals("hola ñandú", b.decrypt(a.encPub, env))
        // Not for me: it says so, it does not return garbage.
        val e = assertThrows(Profile.ProfileError::class.java) { runBlocking { a.decrypt(b.encPub, env) } }
        assertEquals("not-for-me", e.code)
    }

    @Test fun actaMatchesTheJs() {
        val a = v.getValue("acta").jsonObject
        val acta = a.getValue("acta").jsonObject
        for (c in a.getValue("cases").jsonArray.map { it.jsonObject }) {
            val pub = c.getValue("pub").jsonPrimitive.content
            val cap = c.getValue("cap").jsonPrimitive.content
            assertEquals("$pub $cap", c.getValue("can").jsonPrimitive.content.toBoolean(), Acta.memberCan(acta, pub, cap, c.getValue("extra").jsonArray))
        }
    }

    @Test fun broadcastLinkAndChannelMatchTheJs() {
        for (b in v.getValue("broadcast").jsonArray.map { it.jsonObject }) {
            val ref = BroadcastHost.Ref(b.getValue("key").jsonPrimitive.content, b.getValue("secret").jsonPrimitive.content)
            assertEquals(b.getValue("encoded").jsonPrimitive.content, BroadcastHost.encodeRef(ref, b.getValue("hostPubkey").jsonPrimitive.content))
            assertEquals(b.getValue("channel").jsonPrimitive.content, BroadcastHost.channel("padel", ref.key))
        }
        assertTrue(BroadcastHost.newRef("ABCDEFGH1234").key.startsWith("ABCDEFGH1234"))
        assertTrue(BroadcastHost.newRef(null).key.startsWith("_"))
    }

    /** The identity's store as `vault/nativeStore.js` writes it: kv and key records of the active profile. */
    private fun items(keys: DeviceKeys, acta: JsonObject? = null): Map<String, String> {
        fun rec(kind: String, jwk: String) = buildJsonObject { put("external", "kid-1"); put("kind", kind); put("publicJwk", Json.parseToJsonElement(jwk)) }.toString()
        val m = mutableMapOf(
            "kv:dotrino.identity.current" to "p1",
            "key:dotrino.identity.p.p1.keypair" to rec("sign", keys.publickey),
            "key:dotrino.identity.p.p1.enc-keypair" to rec("enc", keys.encPub),
        )
        if (acta != null) m["kv:dotrino.identity.p.p1.acta"] = acta.toString()
        return m
    }

    @Test fun loadsTheActiveProfileAndObeysItsActa() = runBlocking {
        val keys = TestKeys.fresh()
        val free = Profile.load(items(keys)) { keys }
        assertEquals(keys.publickey, free.publickey)
        assertTrue("no acta: one device, which signs", free.canSign)
        val data = buildJsonObject { put("op", "x"); put("n", 1) }
        assertTrue(Crypto.verify(free.publickey, data, free.signData(data)))

        // An acta that does not give this device `sign`: it does not sign (except identify).
        val acta = buildJsonObject { put("members", JsonArray(listOf(buildJsonObject { put("pub", keys.publickey); put("caps", JsonArray(listOf(JsonPrimitive("read")))) }))) }
        val bound = Profile.load(items(keys, acta)) { keys }
        assertFalse(bound.canSign)
        val e = assertThrows(Profile.ProfileError::class.java) { runBlocking { bound.signData(data) } }
        assertEquals("needs-vault-signer", e.code)
        val identify = buildJsonObject { put("op", "identify") }
        assertTrue(Crypto.verify(bound.publickey, identify, bound.signData(identify)))
    }

    @Test fun takesItsNameAndAvatarFromTheIdentityLikeTheWeb() = runBlocking {
        val keys = TestKeys.fresh()
        val plain = Profile.load(items(keys)) { keys }
        assertEquals(null, plain.name); assertEquals(null, plain.avatar); assertEquals(keys.publickey, plain.avatarSeed)

        val m = items(keys).toMutableMap()
        m["kv:dotrino.identity.profiles"] = "[{\"id\":\"p0\",\"name\":\"Otro\",\"pubkey\":\"K0\"},{\"id\":\"p1\",\"name\":\"Lista\",\"pubkey\":\"SEMILLA\"}]"
        m["kv:dotrino.identity.p.p1.me"] = "{\"nickname\":\"Santi\",\"avatar\":\"data:image/png;base64,AAAA\"}"
        val full = Profile.load(m) { keys }
        assertEquals("Santi", full.name)                      // `me` manda sobre la lista
        assertEquals("data:image/png;base64,AAAA", full.avatar)
        assertEquals("SEMILLA", full.avatarSeed)               // la llave de SU entrada en la lista
        m.remove("kv:dotrino.identity.p.p1.me")
        assertEquals("Lista", Profile.load(m) { keys }.name)
    }

    @Test fun noProfileSaysSo() {
        val e = assertThrows(Profile.ProfileError::class.java) { runBlocking { Profile.load(emptyMap()) { error("no keys") } } }
        assertEquals("no-profile", e.code)
        val e2 = assertThrows(Profile.ProfileError::class.java) { runBlocking { Profile.load(mapOf("kv:dotrino.identity.current" to "p1")) { error("no keys") } } }
        assertEquals("no-profile-keys", e2.code)
    }
}
