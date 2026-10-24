package com.dotrino.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyAgreement

/**
 * Las reglas de los amigos, con instancias de VERDAD y sellado de VERDAD sobre un proxio en memoria:
 * una solicitud nunca toca un chat, nadie es amigo hasta que acepta, los desconocidos y los
 * remitentes falsos no llegan a ningún sitio, y a un amigo se le escribe a todos sus aparatos.
 */
class FriendsTest {
    private class SoftKeys : DeviceKeys {
        private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private val s = pair(); private val e = pair()
        override val publickey = Crypto.jwkOf(s.public as ECPublicKey)
        override val encPub = Crypto.jwkOf(e.public as ECPublicKey)
        override suspend fun sign(text: String): String {
            val g = Signature.getInstance("SHA256withECDSA"); g.initSign(s.private as PrivateKey); g.update(text.toByteArray())
            return Crypto.b64(Crypto.derToP1363(g.sign()))
        }
        override suspend fun agree(peer: PublicKey): ByteArray {
            val k = KeyAgreement.getInstance("ECDH"); k.init(e.private); k.doPhase(peer, true); return k.generateSecret()
        }
    }

    /** El proxio, en memoria: tokens, pubkeys, llaves anunciadas y códigos. Todo sellado de verdad. */
    private class Net {
        val byToken = HashMap<String, Phone>()
        val codes = HashMap<String, String>()
        var codeTtl = 60_000L
        var codeCalls = 0
        inner class Phone(val name: String, val keys: SoftKeys = SoftKeys(), acta: JsonObject? = null) : FriendsTransport {
            val profile = Profile.of(keys, acta)
            /** Cada tipo de mensaje que llegó a este aparato. */
            val got = CopyOnWriteArrayList<String>()
            val token = "T-$name"
            val sealing = IdentitySealing(profile, "messenger")
            val listeners = CopyOnWriteArrayList<(SealedSession.Message) -> Unit>()
            init { byToken[token] = this }
            private suspend fun deliverTo(to: Phone, payload: JsonObject, keys: List<String>) {
                val env = sealing.seal(payload, keys.ifEmpty { listOf(to.profile.encPub) })
                val opened = runCatching { to.sealing.open(env) }.getOrNull() ?: return
                to.got.add((opened.payload["type"] as? JsonPrimitive)?.content ?: "?")
                val m = SealedSession.Message(token, profile.publickey, opened.payload, opened.senderEncPub, false, null)
                to.listeners.forEach { it(m) }
            }
            /** Un mensaje que dice venir de [claimed] pero lo sella este teléfono. */
            suspend fun forge(to: Phone, claimed: String, payload: JsonObject) {
                val o = to.sealing.open(sealing.seal(payload, listOf(to.profile.encPub)))
                to.listeners.forEach { it(SealedSession.Message(token, claimed, o.payload, o.senderEncPub, false, null)) }
            }
            suspend fun raw(to: Phone, payload: JsonObject) = deliverTo(to, payload, emptyList())
            override suspend fun sendSealedTo(token: String, payload: JsonObject, recipientEncPubs: List<String>, peerPubkey: String?) = deliverTo(byToken.getValue(token), payload, recipientEncPubs)
            override suspend fun sendSealed(pubkey: String, payload: JsonObject, recipientEncPubs: List<String>, quiet: Boolean) =
                deliverTo(byToken.values.first { it.profile.publickey == pubkey }, payload, recipientEncPubs)
            override suspend fun encPubOf(publickey: String) = byToken.values.first { it.profile.publickey == publickey }.profile.encPub
            override fun pubkeyOfToken(token: String) = byToken[token]?.profile?.publickey
            override suspend fun whoIs(token: String) = pubkeyOfToken(token)
            override suspend fun requestPairingCode(): ProxyConnection.PairingCode {
                codeCalls++
                // Un código del alfabeto del proxio (sin las letras que se confunden), uno por persona.
                val alphabet = "123456789ACDEFHJKMNOPQRTUVWXY"
                val c = (0 until 6).map { alphabet[Math.floorMod(name.hashCode() * 31 + it * 7, alphabet.length)] }.joinToString(""); codes[c] = token
                return ProxyConnection.PairingCode(c, System.currentTimeMillis() + codeTtl)
            }
            override suspend fun redeemPairingCode(code: String) = codes.remove(code) ?: throw ProxyConnection.ProxyError("bad code", "pair-invalid")
            override val isOnline = true
            override fun onMessage(l: (SealedSession.Message) -> Unit): () -> Unit { listeners.add(l); return { listeners.remove(l) } }
            override fun onOnline(l: () -> Unit): () -> Unit = {}
            override fun onPeerGone(l: (String) -> Unit): () -> Unit = {}
        }
    }

    /** Una persona con su libreta y sus amigos, cableada como lo haría una app. */
    private class Person(net: Net, name: String, keys: SoftKeys = SoftKeys(), acta: JsonObject? = null, pinned: List<String> = emptyList()) {
        val phone = net.Phone(name, keys, acta)
        val peers = PeerBook(PeerBook.MemoryStorage(), phone.profile)
        val friends = Friends(phone, phone.profile, peers, MemoryKv(), { name }, Compat.declare("test", "0.1.0", 2, listOf(2)), pinned)
        val requests = CopyOnWriteArrayList<Friends.Request>()
        /** Lo que la app recibe de los amigos (lo que no es del protocolo de amigos). */
        val forApp = CopyOnWriteArrayList<String>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        init {
            friends.onRequest = { requests.add(it) }
            phone.onMessage { m -> scope.launch {
                val r = friends.receive(m) ?: return@launch
                if (!r.handled && r.who.contact != null) forApp.add((m.payload["type"] as? JsonPrimitive)?.content ?: "?")
            } }
            scope.launch { friends.refreshCode() }
        }
        suspend fun people() = peers.contacts().filterNot { friends.isPinned(it["publickey"]!!.jsonPrimitive.content) }
        val pubkey get() = phone.profile.publickey
    }

    private suspend fun until(what: String, f: suspend () -> Boolean) = withTimeout(5_000) { while (!f()) delay(20) }.also { assertTrue(what, f()) }
    private fun key(o: JsonObject) = o["publickey"]!!.jsonPrimitive.content

    @Test fun aRequestIsControlAndAcceptMakesFriendsOnBothSides() = runBlocking {
        val net = Net(); val ana = Person(net, "Ana"); val beto = Person(net, "Beto")
        until("ana has a code") { ana.friends.code != null }
        beto.friends.requestByCode(ana.friends.code!!.lowercase(), "Anita")
        until("ana gets the request") { ana.friends.incoming().any { it.pubkey == beto.pubkey } }
        assertEquals("Beto", ana.friends.incoming().single().nickname)
        assertEquals(1, ana.requests.size)
        assertTrue("nobody is a friend yet", ana.people().isEmpty() && beto.people().isEmpty())
        assertEquals("out", beto.friends.outgoing().single().dir)

        ana.friends.accept(beto.pubkey)
        until("beto sees ana as a friend") { beto.people().any { key(it) == ana.pubkey } }
        assertEquals("Anita", beto.people().single()["nickname"]!!.jsonPrimitive.content)   // el apodo que eligió Beto
        assertTrue(ana.friends.requests().isEmpty()); assertTrue(beto.friends.requests().isEmpty())
        assertTrue("the request reached no app", ana.forApp.isEmpty() && beto.forApp.isEmpty())

        beto.friends.send(ana.pubkey, buildJsonObject { put("type", "DM"); put("text", "hola") })
        until("what is not of the protocol goes to the app") { ana.forApp.contains("DM") }
    }

    @Test fun ownCodeAndBadCodesSayWhichIsWhich() = runBlocking {
        val net = Net(); val ana = Person(net, "Ana")
        until("code") { ana.friends.code != null }
        val own = runCatching { ana.friends.requestByCode(ana.friends.code!!, "") }.exceptionOrNull() as Friends.FriendsError
        assertEquals("own", own.code)
        val bad = runCatching { ana.friends.requestByCode("xx", "") }.exceptionOrNull() as Friends.FriendsError
        assertEquals("invalid", bad.code)
        val gone = runCatching { ana.friends.requestByCode("N2P4QR", "") }.exceptionOrNull() as Friends.FriendsError
        assertEquals("invalid", gone.code)
        assertEquals("K7M2Q9", Friends.codeFromText("https://messenger.dotrino.com/#add=k7m-2q9"))
        assertEquals("5O", Friends.normalizeCode("s0"))
    }

    @Test fun aStrangersMessageAndAnUnaskedAcceptGoNowhere() = runBlocking {
        val net = Net(); val ana = Person(net, "Ana"); val carla = Person(net, "Carla")
        carla.phone.raw(ana.phone, buildJsonObject { put("type", "DM"); put("text", "hola") })
        carla.phone.raw(ana.phone, buildJsonObject { put("type", Friends.ACCEPT); put("nickname", "Carla") })
        delay(300)
        assertTrue(ana.forApp.isEmpty()); assertTrue(ana.friends.requests().isEmpty()); assertTrue(ana.people().isEmpty())
    }

    @Test fun aForgedSenderIsDropped() = runBlocking {
        val net = Net(); val ana = Person(net, "Ana"); val beto = Person(net, "Beto"); val eve = Person(net, "Eve")
        until("code") { ana.friends.code != null }
        beto.friends.requestByCode(ana.friends.code!!, "")
        until("request") { ana.friends.incoming().isNotEmpty() }
        ana.friends.accept(beto.pubkey)
        until("friends") { beto.people().isNotEmpty() }
        // Eve sella con SU llave pero dice ser Beto: la llave no es de Beto.
        val m = SealedSession.Message(eve.phone.token, beto.pubkey,
            buildJsonObject { put("type", "DM"); put("text", "soy beto") }, eve.phone.profile.encPub, false, null)
        assertNull(ana.friends.receive(m))
        eve.phone.forge(ana.phone, beto.pubkey, buildJsonObject { put("type", "DM"); put("text", "soy beto") })
        delay(300)
        assertTrue(ana.forApp.isEmpty())
    }

    @Test fun aBlockedSendsNothingIn() = runBlocking {
        val net = Net(); val ana = Person(net, "Ana"); val carla = Person(net, "Carla")
        ana.peers.setBlocked(carla.pubkey, true)
        carla.friends.request(ana.pubkey, "")
        delay(300)
        assertTrue(ana.friends.requests().isEmpty())
    }

    @Test fun theCodeRenewsItselfAndAfterBeingUsed() = runBlocking {
        val net = Net(); net.codeTtl = 10_300   // se renueva 5 s antes de caducar: a los ~5,3 s
        val ana = Person(net, "Ana"); val beto = Person(net, "Beto")
        until("first code") { ana.friends.code != null }
        val before = net.codeCalls
        withTimeout(12_000) { while (net.codeCalls == before) delay(100) }
        delay(300)
        assertTrue("the renewal left the code empty", ana.friends.code != null)
        val used = net.codeCalls
        beto.friends.requestByCode(ana.friends.code!!, "")
        until("a used code is renewed") { net.codeCalls > used && ana.friends.code != null }
    }

    @Test fun bothAskingIsBothAccepting() = runBlocking {
        val net = Net(); val ana = Person(net, "Ana"); val beto = Person(net, "Beto")
        until("codes") { ana.friends.code != null && beto.friends.code != null }
        beto.friends.requestByCode(ana.friends.code!!, "")
        until("ana has the request") { ana.friends.incoming().isNotEmpty() }
        ana.friends.requestByCode(beto.friends.code!!, "")   // en vez de aceptar, lo pide ella: es aceptar
        until("both are friends") { ana.people().isNotEmpty() && beto.people().isNotEmpty() }
    }

    // ---------- por CUENTA ----------

    /** Una cuenta con su llave de perfil [owner] y estos aparatos: su tarjeta, firmada por el dueño. */
    private suspend fun cardFor(owner: SoftKeys, vararg devices: SoftKeys): JsonObject {
        val body = buildJsonObject {
            put("v", 1); put("profileId", owner.publickey); put("seq", 1); put("sealedBy", owner.publickey)
            put("keys", buildJsonArray { devices.forEach { d -> add(buildJsonObject { put("pub", d.publickey); put("encPub", d.encPub) }) } })
            put("iat", 1L)
        }
        return JsonObject(body + ("sig" to JsonPrimitive(owner.sign(Canonical.stringify(body)))))
    }
    private fun acta(card: JsonObject) = buildJsonObject { put("card", card) }

    @Test fun aFriendIsTheirAccountAndEveryDeviceGetsTheMessage() = runBlocking {
        val net = Net(); val owner = SoftKeys(); val k1 = SoftKeys(); val k2 = SoftKeys()
        val card = cardFor(owner, k1, k2)
        val ana = Person(net, "Ana"); val b1 = Person(net, "Beto1", k1, acta(card)); val b2 = Person(net, "Beto2", k2, acta(card))
        until("ana has a code") { ana.friends.code != null }
        b1.friends.requestByCode(ana.friends.code!!, "")
        until("ana gets the request") { ana.friends.incoming().isNotEmpty() }
        assertEquals("kept by the account, not the device", owner.publickey, ana.friends.incoming().single().pubkey)
        ana.friends.accept(owner.publickey)
        until("beto sees ana") { b1.people().isNotEmpty() }
        assertEquals(listOf(owner.publickey), ana.people().map(::key))
        ana.friends.send(owner.publickey, buildJsonObject { put("type", "DM"); put("text", "a los dos") })
        until("both devices got it") { b1.phone.got.contains("DM") && b2.phone.got.contains("DM") }
    }

    @Test fun aLinkWithTheCardReachesEveryDevice() = runBlocking {
        val net = Net(); val owner = SoftKeys(); val k1 = SoftKeys(); val k2 = SoftKeys()
        val card = cardFor(owner, k1, k2)
        val ana = Person(net, "Ana"); val b1 = Person(net, "Beto1", k1, acta(card)); val b2 = Person(net, "Beto2", k2, acta(card))
        ana.friends.request(k1.publickey, "Beto", card)
        until("both devices got the request") { b1.friends.incoming().isNotEmpty() && b2.friends.incoming().isNotEmpty() }
        assertEquals(owner.publickey, ana.friends.outgoing().single().pubkey)
        b2.friends.accept(ana.pubkey)                          // aceptada en el OTRO aparato
        until("ana has beto by his account") { ana.people().map(::key) == listOf(owner.publickey) }
        assertEquals("Beto", ana.people().single()["nickname"]!!.jsonPrimitive.content)
    }

    @Test fun aFriendKeptByADeviceMovesToItsAccount() = runBlocking {
        val net = Net(); val owner = SoftKeys(); val k1 = SoftKeys()
        val ana = Person(net, "Ana"); val b1 = Person(net, "Beto", k1, acta(cardFor(owner, k1)))
        val moved = CopyOnWriteArrayList<Pair<String, String>>()
        ana.friends.onMoved = { from, to -> moved.add(from to to) }
        ana.peers.addContact(b1.pubkey, nickname = "Beto de antes")     // un amigo de antes de las cuentas
        b1.peers.addContact(ana.pubkey, nickname = "Ana")
        // Cualquier mensaje que traiga su tarjeta lo mueve (el messenger lo hace en su saludo).
        val m = SealedSession.Message(b1.phone.token, b1.pubkey, buildJsonObject { put("type", "HELLO"); put("card", cardFor(owner, k1)) }, k1.encPub, false, null)
        val r = ana.friends.receive(m)!!
        assertFalse(r.handled)
        ana.friends.noteProfile(r.who.pubkey, m.payload)
        ana.friends.upgradeToAccount(r.who, m.payload)
        assertEquals(listOf(owner.publickey), ana.people().map(::key))
        assertEquals("Beto de antes", ana.people().single()["nickname"]!!.jsonPrimitive.content)
        assertEquals(listOf(b1.pubkey to owner.publickey), moved.toList())
    }

    @Test fun aPinnedAccountHasNoDevicesUntilItsCardArrivesAndCannotBeBlocked() = runBlocking {
        val net = Net(); val owner = SoftKeys()
        val ana = Person(net, "Ana", pinned = listOf(owner.publickey))
        ana.peers.addContact(owner.publickey, nickname = "Dotrino")
        assertTrue(ana.friends.recipientsOf(owner.publickey).isEmpty())
        val e = runCatching { ana.friends.setBlocked(owner.publickey, true) }.exceptionOrNull() as Friends.FriendsError
        assertEquals("pinned-contact", e.code)
        assertFalse(ana.friends.isBlocked(owner.publickey))
    }
}
