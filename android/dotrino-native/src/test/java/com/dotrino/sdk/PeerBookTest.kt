package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The peer book against what the JS identity makes: cards, contacts, ratings. */
class PeerBookTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vectors.json")!!.readText()).jsonObject
    private val p = v.getValue("peers").jsonObject
    private fun book(storage: PeerBook.MemoryStorage = PeerBook.MemoryStorage()) = PeerBook(storage, Profile.of(TestKeys.fresh()))

    @Test fun adoptsAJsCardAndRefusesAForgedOne() = runBlocking {
        val card = p.getValue("card").jsonObject
        assertTrue(PeerBook.verifyCard(card))
        val b = book()
        assertEquals(PeerBook.CardAdoption(true, "primera-vez", 1), b.adoptPeerCard(card))
        // One key more, same signature: forged.
        val forged = JsonObject(card + ("seq" to JsonPrimitive(99)))
        assertFalse(PeerBook.verifyCard(forged))
        assertEquals("firma-invalida", b.adoptPeerCard(forged).reason)
        // Older than what I have: refused.
        assertEquals("igual", b.adoptPeerCard(card).reason)
    }

    @Test fun aContactAddedByOneDeviceIsFoundThroughTheCard() = runBlocking {
        val card = p.getValue("card").jsonObject
        val dev = p.getValue("cardDevPub").jsonPrimitive.content
        val b = book()
        b.addContact(dev, nickname = "Beto", encryptionPubkey = "ENC-1")
        b.adoptPeerCard(card)
        assertEquals(card, b.cardOf(dev))
        assertEquals(listOf("ENC-1", p.getValue("cardDevEncPub").jsonPrimitive.content), b.encPubsOf(dev))
        assertEquals(1, b.contacts().size)
        b.removeContact(dev)
        assertEquals(0, b.contacts().size)
        assertEquals("Beto", b.get(dev)!!["nickname"]!!.jsonPrimitive.content) // the record stays
    }

    /** Blocking is private and travels with the book: the newer change wins on another device, unblocking too. */
    @Test fun aBlockTravelsAndTheNewerChangeWins() = runBlocking {
        val pk = "{\"kty\":\"EC\",\"x\":\"b\"}"
        val a = book(); val b = book()
        a.addContact(pk, nickname = "Beto"); b.addContact(pk, nickname = "Beto")
        Thread.sleep(5) // distinct milliseconds: the newer change has to be newer
        val blocked = a.setBlocked(pk, true)
        assertEquals(true, a.isBlocked(pk))
        assertEquals(null, blocked["myRating"]) // blocking is not rating: nothing signed
        Thread.sleep(5)
        b.mergeFrom(listOf(a.get(pk)!!))
        assertEquals(true, b.isBlocked(pk))
        Thread.sleep(5)
        b.setBlocked(pk, false)
        a.mergeFrom(listOf(b.get(pk)!!))
        assertEquals(false, a.isBlocked(pk))
    }

    @Test fun mergesAJsEndorsementOnlyIfItsSignatureHolds() = runBlocking {
        val e = p.getValue("endorsement").jsonObject
        val subject = p.getValue("subject").jsonPrimitive.content
        val b = book()
        assertEquals(1, b.mergeEndorsements(subject, listOf(e)))
        assertEquals(0, b.mergeEndorsements(subject, listOf(e))) // not newer: nothing
        val tampered = JsonObject(e + ("rating" to JsonPrimitive(5)) + ("issuedAt" to JsonPrimitive(9_999_999_999_999)))
        assertEquals(0, b.mergeEndorsements(subject, listOf(tampered)))
        assertEquals(e, b.ratingsFor(subject).second.single())
    }

    @Test fun myRatingIsSignedAndOthersCanVerifyIt() = runBlocking {
        val mine = book()
        mine.setRating("SUBJ", 4.0, "ok")
        val env = mine.ratingsFor("SUBJ").first!!
        assertEquals("4", env["rating"].toString())
        // Another phone merges it: the signature holds.
        assertEquals(1, book().mergeEndorsements("SUBJ", listOf(env)))
    }

    @Test fun keepsWhatAnotherAppWroteInBetween() = runBlocking {
        val s = PeerBook.MemoryStorage()
        val a = book(s); val b = book(s)
        a.addContact("A", nickname = "a")
        b.addContact("B", nickname = "b")
        assertEquals(setOf("A", "B"), a.contacts().map { it["publickey"]!!.jsonPrimitive.content }.toSet())
        assertNull(a.get("C"))
    }
}
