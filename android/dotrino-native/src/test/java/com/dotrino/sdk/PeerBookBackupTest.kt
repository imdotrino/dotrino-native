package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El libro de contactos en la bóveda: los mismos casos que `test/peerSync.test.mjs` de la identidad. */
class PeerBookBackupTest {
    /** Una bóveda de mentira con las reglas del almacén: por id gana la entrada más nueva (ts). */
    private class FakeVault {
        val t = LinkedHashMap<String, JsonObject>()
        suspend fun call(method: String, args: JsonObject): JsonElement = when (method) {
            "getThreadIndexes" -> buildJsonObject {
                put("indexes", buildJsonObject { put(PeerBookBackup.THREAD, buildJsonObject {
                    put("items", JsonArray(t.values.map { JsonArray(listOf(it["id"]!!, it["ts"]!!)) })); put("tombs", JsonArray(emptyList()))
                }) })
                put("next", kotlinx.serialization.json.JsonNull)
            }
            "getEntries" -> buildJsonObject {
                val ids = ((args["refs"] as JsonObject)[PeerBookBackup.THREAD] as JsonArray).map { it.jsonPrimitive.content }
                put("threads", buildJsonObject { put(PeerBookBackup.THREAD, JsonArray(ids.mapNotNull { t[it] })) })
            }
            "importThreads" -> {
                for (e in ((args["threads"] as JsonObject)[PeerBookBackup.THREAD] as JsonArray).map { it as JsonObject }) {
                    val id = e["id"]!!.jsonPrimitive.content
                    if ((t[id]?.get("ts")?.jsonPrimitive?.long ?: -1) < e["ts"]!!.jsonPrimitive.long) t[id] = e
                }
                buildJsonObject { put("ok", true) }
            }
            else -> error(method)
        }
    }

    private fun book() = PeerBook(PeerBook.MemoryStorage(), Profile.of(TestKeys.fresh())).let { it to PeerBookBackup(Profile.of(TestKeys.fresh()), it) }

    @Test fun aContactReachesTheOtherDeviceAndRemovingItToo() = runBlocking {
        val v = FakeVault()
        val (a, sa) = book(); val (b, sb) = book()
        a.addContact("X", nickname = "Ana")
        sa.reconcile(v::call)
        assertEquals(1, sb.reconcile(v::call))
        assertEquals("Ana", (b.contacts().single()["nickname"] as JsonPrimitive).content)

        Thread.sleep(5)
        b.removeContact("X")
        sb.reconcile(v::call)
        sa.reconcile(v::call)
        assertEquals("the removed contact does not come back", 0, a.contacts().size)
        assertNull(a.get("X")!!["isContact"])

        // Up to date: nothing more changes on either side.
        assertEquals(0, sa.reconcile(v::call)); assertEquals(0, sb.reconcile(v::call))
    }

    @Test fun theEntryIdIsTheWebOne() {
        // The same value `peerIdOf` of dotrino-identity gives for that key.
        assertEquals("988a93c5b4a24186e320c9615b0f85f9", PeerBookBackup.idOf("{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"ñ\"}"))
    }
}
