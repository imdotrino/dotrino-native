package com.dotrino.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * WHICH VERSION I AM AND WHETHER WE CAN TALK (CONVENCIONES §14): `declare` / `check` of
 * `@dotrino/compat`. An incompatibility shows up as SILENCE unless each greeting says what it
 * is; this reads the other side's declaration and says it. It never blocks: it is SHOWN.
 */
object Compat {
    data class Declaration(val product: String, val version: String, val protocol: Int, val speaks: List<Int>) {
        fun toJson() = buildJsonObject {
            put("product", product); put("version", version); put("protocol", protocol)
            put("speaks", buildJsonArray { speaks.forEach { add(JsonPrimitive(it)) } })
        }
    }

    /** A version known broken, by EXACT version (never a range). */
    data class Broken(val product: String, val versions: List<String>, val why: String? = null, val fix: String? = null)

    /** `ok` | `incompatible-protocol` | `broken-peer` | `undeclared`. */
    data class Verdict(val compatible: Boolean, val code: String, val reason: String)

    fun declare(product: String, version: String, protocol: Int, speaks: List<Int> = listOf(protocol)): Declaration {
        val s = speaks.distinct().sorted()
        require(protocol in s) { "compat: speaks must include protocol" }
        require(product.isNotEmpty() && version.isNotEmpty()) { "compat: incomplete declaration" }
        return Declaration(product, version, protocol, s)
    }

    fun parse(o: JsonObject?): Declaration? {
        val product = (o?.get("product") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val version = (o["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val protocol = (o["protocol"] as? JsonPrimitive)?.intOrNull ?: return null
        val speaks = (o["speaks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return null
        if (product.isEmpty() || version.isEmpty() || speaks.isEmpty()) return null
        return Declaration(product, version, protocol, speaks)
    }

    fun check(mine: Declaration, theirs: JsonObject?, broken: List<Broken> = emptyList()): Verdict {
        val t = parse(theirs) ?: return Verdict(false, "undeclared", "the other side does not say what it is or which version it runs")
        broken.firstOrNull { it.product == t.product && t.version in it.versions }?.let { b ->
            return Verdict(false, "broken-peer", "${t.product} ${t.version} is known to be broken: ${b.why ?: "no reason recorded"}" + (b.fix?.let { " — $it" } ?: ""))
        }
        if (t.protocol !in mine.speaks) {
            return Verdict(false, "incompatible-protocol", "${mine.product} ${mine.version} speaks protocol ${mine.speaks.joinToString(", ")} and ${t.product} ${t.version} speaks ${t.protocol}: update ${t.product}")
        }
        return Verdict(true, "ok", "")
    }
}
