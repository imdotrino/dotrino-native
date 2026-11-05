package com.dotrino.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes to the vault goes sealed ([VaultSeal]); the answer opens only with the one-use key. */
class VaultSealTest {
    private val payload = buildJsonObject {
        put("type", "vault.secrets")
        put("data", buildJsonObject { put("op", "approvals"); put("machine", "Pixel-XYZ") })
    }

    @Test fun theWholeRequestIsInsideTheEnvelope() {
        val vault = VaultSeal.ReplyKey()   // stands in for the vault's transport key
        val env = VaultSeal.seal(payload, vault.encPub)
        val wire = env.toString()
        listOf("vault.secrets", "approvals", "Pixel-XYZ").forEach { assertFalse("in the clear: $it", wire.contains(it)) }
        assertTrue(VaultSeal.isSealed(env))
        assertEquals(payload, vault.open(env))
        assertNull("sealed to someone else does not open", VaultSeal.ReplyKey().open(env))
    }

    @Test fun onlyTheVaultsOfTheRecord() {
        val acta = buildJsonObject {
            put("members", buildJsonArray {
                add(buildJsonObject { put("pub", "A"); put("cn", "vault") })
                add(buildJsonObject { put("pub", "PHONE") })
                add(buildJsonObject { put("pub", "A"); put("cn", "vault") })
            })
        }
        assertEquals(listOf("A"), VaultSeal.vaultKeys(acta))
        assertEquals(emptyList<String>(), VaultSeal.vaultKeys(null))
    }

    @Test fun theMigrationWindowCloses() {
        assertTrue(VaultSeal.plainAllowed(VaultSeal.PLAIN_UNTIL_MS - 1))
        assertFalse(VaultSeal.plainAllowed(VaultSeal.PLAIN_UNTIL_MS))
        assertEquals(1_797_292_800_000L, VaultSeal.PLAIN_UNTIL_MS)
    }

    @Test fun newestRecord() {
        val a = JsonObject(mapOf("seq" to JsonPrimitive(3)))
        val b = JsonObject(mapOf("seq" to JsonPrimitive(5)))
        assertEquals(b, VaultSeal.newest(a, b))
        assertEquals(b, VaultSeal.newest(b, a))
        assertEquals(a, VaultSeal.newest(a, null))
        assertEquals(b, VaultSeal.newest(null, b))
    }
}
