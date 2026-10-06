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

    // ---------- verifying and adopting a record (the port of `verifyActa` / `canAdopt`) ----------

    /** Record versions this reader can verify (`ACTA_LEIBLES`). */
    private val READABLE = 1L..5L
    private const val SEALER_LINK_V = 1L

    private fun str(e: kotlinx.serialization.json.JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    /** `Number.isInteger`: a JSON number with no fraction. */
    private fun int(e: kotlinx.serialization.json.JsonElement?): Long? =
        (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { c -> if (c.contains('.') || c.contains('e') || c.contains('E')) null else c.toLongOrNull() }
    private fun isPub(e: kotlinx.serialization.json.JsonElement?) = !str(e).isNullOrEmpty()
    private fun isNull(e: kotlinx.serialization.json.JsonElement?) = e == null || e is kotlinx.serialization.json.JsonNull
    /** JavaScript's truthiness, for the one rule that reads it (`else if (acta.sealSince)`). */
    private fun truthy(e: kotlinx.serialization.json.JsonElement?): Boolean = when {
        isNull(e) -> false
        e is JsonPrimitive && e.isString -> e.content.isNotEmpty()
        e is JsonPrimitive -> e.content != "false" && e.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: true
        else -> true
    }

    private fun isChainUrl(u: String): Boolean {
        if (u.length > 300) return false
        return try {
            val x = java.net.URI(u)
            x.scheme == "https" && x.host != null && x.rawFragment == null && x.rawUserInfo == null
        } catch (_: Exception) { false }
    }

    private fun isEncPub(v: String): Boolean = try {
        val j = kotlinx.serialization.json.Json.parseToJsonElement(v) as? JsonObject
        j != null && str(j["kty"]) == "EC" && str(j["crv"]) == "P-256" && str(j["x"]) != null && str(j["y"]) != null
    } catch (_: Exception) { false }

    private val CN = Regex("^[a-z0-9-]{1,32}$")

    /** What is sealed and hashed: the record without its signature and its card (`actaBody`). */
    fun body(acta: JsonObject): JsonObject = JsonObject(acta.filterKeys { it != "sig" && it != "card" })

    /** `actaHash`: hex SHA-256 of the canonical body. It is what `prev` points to. */
    fun hash(acta: JsonObject): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(Canonical.stringify(body(acta)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** `canSeal`: may [pub] seal the next record of this profile? */
    fun canSeal(acta: JsonObject?, pub: String?): Boolean {
        if (acta == null || pub == null) return false
        val v = int(acta["v"])
        return memberCan(acta, pub, "sealer") || (v != null && v < V_NO_SEALER_FIELD && str(acta["sealer"]) == pub)
    }

    /** `checkShape`: the reason the record is malformed, or null. */
    fun checkShape(acta: JsonObject?): String? {
        if (acta == null) return "no-acta"
        val v = int(acta["v"])
        if (v == null || v !in READABLE) return "version"
        if (!isPub(acta["profileId"]) || !isPub(acta["sealedBy"])) return "shape"
        if (!isNull(acta["chainUrl"]) && !(str(acta["chainUrl"])?.let(::isChainUrl) ?: false)) return "chainurl"
        val oldField = v < V_NO_SEALER_FIELD
        if (oldField && !isPub(acta["sealer"])) return "shape"
        if (!oldField && !isNull(acta["sealer"])) return "sealer-no-va-en-v3"
        val seq = int(acta["seq"])
        if (seq == null || seq < 1) return "seq"
        if (seq > 1 && str(acta["prev"]) == null) return "prev"
        val members = acta["members"] as? JsonArray
        if (members == null || members.isEmpty()) return "members"
        for (e in members) {
            val m = e as? JsonObject ?: return "member"
            val caps = m["caps"] as? JsonArray
            if (!isPub(m["pub"]) || caps == null) return "member"
            // An unknown permission is ignored, not an invalid record (it grants nothing to this reader).
            if (caps.any { str(it).isNullOrEmpty() }) return "member"
            if (!isNull(m["cn"])) {
                if (!(str(m["cn"])?.let { CN.matches(it) } ?: false)) return "cn-invalido"
            } else if (caps.any { str(it) == "secrets" }) return "secretos-sin-cn"
            if (!isNull(m["encPub"]) && !(str(m["encPub"])?.let(::isEncPub) ?: false)) return "encpub-invalido"
        }
        if (v >= 2) {
            if (!isNull(acta["sealPub"])) {
                if (!isPub(acta["sealPub"])) return "sealpub-invalido"
                val since = int(acta["sealSince"])
                if (since == null || since < 1 || since > seq) return "sealsince"
            } else if (truthy(acta["sealSince"])) return "sealsince"
            val keys = acta["sealKeys"] as? JsonArray ?: return "sealkeys"
            for (e in keys) {
                val k = e as? JsonObject ?: return "sealkey-invalida"
                if (!isPub(k["pub"])) return "sealkey-invalida"
                val from = int(k["from"]); val to = int(k["to"])
                if (from == null || to == null || from < 1 || to < from) return "sealkey-rango"
            }
        }
        val pubs = members.map { str((it as JsonObject)["pub"]) }
        if (pubs.toSet().size != pubs.size) return "miembro-duplicado"
        if (sealersOf(acta).isEmpty()) return "sin-sellador"
        if (!isNull(acta["sealerAnchor"])) {
            val a = acta["sealerAnchor"] as? JsonObject ?: return "sealeranchor"
            val aseq = int(a["seq"])
            if (aseq == null || aseq < 1 || aseq >= seq) return "sealeranchor"
            if (!(str(a["hash"])?.matches(Regex("^[0-9a-f]{64}$")) ?: false)) return "sealeranchor"
        }
        if (members.none { m -> ((m as JsonObject)["caps"] as JsonArray).any { str(it) == "sign" } }) return "sin-firmante"
        return null
    }

    /** `checkSealerLink`: the sealer-chain link inside the record says the same as the record. */
    fun checkSealerLink(acta: JsonObject): String? {
        val v = int(acta["v"]) ?: 0
        val changed = (acta["sealerChanged"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
        val l = acta["sealerLink"] as? JsonObject
        if (isNull(acta["sealerLink"])) return if (changed && v >= 5) "eslabon-ausente" else null
        if (l == null || int(l["v"]) != SEALER_LINK_V) return "eslabon-version"
        if (str(l["sig"]) == null) return "eslabon-sin-firma"
        if (str(l["profileId"]) != str(acta["profileId"])) return "eslabon-otro-perfil"
        val seq = int(acta["seq"]) ?: return "eslabon-otro-seq"
        if (changed) {
            if (int(l["seq"]) != seq) return "eslabon-otro-seq"
            if (str(l["by"]) != str(acta["sealedBy"])) return "eslabon-otro-sellador"
        } else if (!((int(l["seq"]) ?: Long.MAX_VALUE) < seq)) return "eslabon-del-futuro"
        val says = (l["sealers"] as? JsonArray).orEmpty().mapNotNull { str(it) }.sorted().joinToString("|")
        if (says != sealersOf(acta).sorted().joinToString("|")) return "eslabon-no-cuadra"
        return null
    }

    /** `verifyActa`: well formed, signed by who says it sealed it, and its link agrees. The reason it is not, or null. */
    fun verify(acta: JsonObject?, expectedProfileId: String? = null): String? {
        checkShape(acta)?.let { return it }
        acta!!
        val sig = str(acta["sig"]) ?: return "sin-firma"
        if (expectedProfileId != null && str(acta["profileId"]) != expectedProfileId) return "otro-perfil"
        if (!Crypto.verify(str(acta["sealedBy"])!!, body(acta), sig)) return "firma-invalida"
        return checkSealerLink(acta)
    }

    /**
     * `canAdopt` (§2.4.1): may [candidate] replace [current]? A greater `seq` sealed by a sealer
     * of mine (and chained, if it is the next one); the same `seq` only by the hash tie-break;
     * never back. Returns (adopt, reason) with the JS reasons.
     */
    fun canAdopt(candidate: JsonObject, current: JsonObject?): Pair<Boolean, String> {
        verify(candidate, current?.let { str(it["profileId"]) })?.let { return false to it }
        if (current == null) return true to "sin-acta-previa"
        val cs = int(candidate["seq"])!!; val ks = int(current["seq"]) ?: 0
        val by = str(candidate["sealedBy"])
        if (cs > ks) {
            if (!canSeal(current, by)) return false to "sellador-no-autorizado"
            if (cs == ks + 1 && str(candidate["prev"]) != hash(current)) return false to "no-encadena"
            return true to "seq-mayor"
        }
        if (cs == ks) {
            val hc = hash(candidate); val hk = hash(current)
            if (hc == hk) return false to "misma-acta"
            if (by != str(current["sealedBy"]) && !canSeal(current, by)) return false to "otro-sellador"
            return (hc < hk) to "desempate-hash"
        }
        return false to "seq-menor"
    }

    /**
     * `adoptChain`: catch up link by link, each judged against the one adopted before it (never
     * a blind jump). Returns the newest adopted record, or null when none fits.
     */
    fun adoptChain(chain: List<JsonObject>, current: JsonObject?): JsonObject? {
        var cur = current; var adopted: JsonObject? = null
        for (a in chain.sortedBy { int(it["seq"]) ?: 0 }) {
            if (canAdopt(a, cur).first) { cur = a; adopted = a }
        }
        return adopted
    }
}
