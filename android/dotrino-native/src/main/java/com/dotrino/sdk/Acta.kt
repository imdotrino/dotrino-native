package com.dotrino.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a member of a profile may do, read from its acta: the port of `memberCan` /
 * `effectiveCaps` of `@dotrino/identity` (`vault/acta.js`). Only the reading: the acta is
 * sealed elsewhere. A member is found by its exact `pub` string, like the JS does.
 */
object Acta {
    /** The capabilities this reader knows. One it does not know is not a capability FOR IT. */
    val CAPS = listOf("sign", "store", "read", "secrets", "admin", "approve", "passwords", "passkeys", "sealer", "unattended", "replica")

    private fun member(acta: JsonObject?, pub: String): JsonObject? =
        (acta?.get("members") as? JsonArray)?.firstOrNull { (it as? JsonObject)?.get("pub")?.let { p -> (p as? JsonPrimitive)?.content } == pub } as? JsonObject

    fun effectiveCaps(acta: JsonObject?, pub: String, extraRenounces: JsonArray = JsonArray(emptyList())): List<String> {
        val m = member(acta, pub) ?: return emptyList()
        val removed = HashSet<String>()
        for (r in ((acta?.get("renounced") as? JsonArray).orEmpty() + extraRenounces)) {
            val o = r as? JsonObject ?: continue
            if ((o["member"] as? JsonPrimitive)?.content == pub) {
                for (c in (o["caps"] as? JsonArray).orEmpty()) (c as? JsonPrimitive)?.content?.let { removed.add(it) }
            }
        }
        return (m["caps"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            .filter { it in CAPS && it !in removed }
    }

    fun memberCan(acta: JsonObject?, pub: String, cap: String, extraRenounces: JsonArray = JsonArray(emptyList())) =
        cap in effectiveCaps(acta, pub, extraRenounces)

    /** Records before this version named their sealer in a field, and it sealed WITHOUT the permission. */
    private const val V_NO_SEALER_FIELD = 3

    /**
     * `sealersOf`: who may seal this profile's record — and so, whose papers count. The members
     * with `sealer`; in an old record, also the one in its `sealer` field.
     */
    fun sealersOf(acta: JsonObject?, extraRenounces: JsonArray = JsonArray(emptyList())): List<String> {
        if (acta == null) return emptyList()
        val byCap = (acta["members"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("pub") as? JsonPrimitive)?.content }
            .filter { memberCan(acta, it, "sealer", extraRenounces) }
        val v = (acta["v"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val field = (acta["sealer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (v != null && v < V_NO_SEALER_FIELD && field != null && field !in byCap) listOf(field) + byCap else byCap
    }
}
