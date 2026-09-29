package com.dotrino.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The store's backup against a REAL vault (`test-vectors/e2e-store.mjs`): the phone pulls what
 * the PWA had saved (only ITS threads, not another app's), writes and deletes, pushes, and a
 * second, empty phone ends up with exactly the same. Only with `DOTRINO_E2E_STORE`.
 */
class VaultBackupE2eTest {
    @Test fun pullPushAndAgreeWithTheVault() = runBlocking {
        val path = System.getenv("DOTRINO_E2E_STORE")
        assumeTrue("run test-vectors/e2e-store.mjs and set DOTRINO_E2E_STORE", path != null)
        val f = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val acta = f.getValue("acta").jsonObject
        val base = TestKeys.fromJwk(f.getValue("privateJwk").jsonObject, f.getValue("encPrivateJwk").jsonObject)
        val pub = f.getValue("publickey").jsonPrimitive.content
        val keys = object : DeviceKeys by base { override val publickey = pub }
        val link = Profile.VaultLink(f.getValue("vault").jsonPrimitive.content, f.getValue("proxyUrl").jsonPrimitive.content, f.getValue("cert").jsonObject, f.getValue("deviceId").jsonPrimitive.content)
        val contact = f.getValue("contact").jsonPrimitive.content
        val owns = { k: String -> k.startsWith("{") }   // the messenger's threads: contacts' keys

        // 1) The phone pulls what the PWA left: its contact's thread, NOT padel's.
        val phone = VaultBackup.MemoryThreads()
        val backup = VaultBackup(Profile.of(keys, acta, link), phone, owns)
        val r1 = backup.sync()
        assertEquals(setOf(contact), r1.changed)
        assertEquals(listOf("w1", "w2"), phone.t.list(contact).map { it["id"]!!.jsonPrimitive.content })
        assertTrue("another app's thread came in", phone.t.list("padel.results").isEmpty())

        // 2) It writes one and deletes another; the next sync pushes both (the delete as a tombstone).
        phone.change { it.append(contact, buildJsonObject { put("id", "n1"); put("ts", 2000); put("dir", "out"); put("text", "desde el teléfono") }) }
        phone.change { it.remove(contact, "w2") }
        backup.sync()
        withTimeout(10_000) { while (!File("$path.pushed").exists()) delay(100) }
        val onDisk = Json.parseToJsonElement(File("$path.pushed").readText()).jsonObject
        assertEquals(1, onDisk.getValue("padel").toString().split("\"p1\"").size - 1)   // padel untouched

        // 3) A second phone, empty, ends up with exactly the same thread — deleted one included.
        val other = VaultBackup.MemoryThreads()
        VaultBackup(Profile.of(keys, acta, link), other, owns).sync()
        assertEquals(phone.t.list(contact).map { it["id"]!!.jsonPrimitive.content }.toSet(), other.t.list(contact).map { it["id"]!!.jsonPrimitive.content }.toSet())
        assertEquals(phone.t.digestOf(contact), other.t.digestOf(contact))

        // 4) And nothing left to do: digests agree, nothing changes.
        assertEquals(emptySet<String>(), backup.sync().changed)
    }
}
