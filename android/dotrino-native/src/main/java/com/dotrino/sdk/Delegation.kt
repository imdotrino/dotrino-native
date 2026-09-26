package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** Key ids and the device's certificate («papel»), as the pilar defines them. */
object Delegation {
    private val json = Json { ignoreUnknownKeys = true }

    /** `pubkeyId` (`vault/keyid.js`): sha-256 hex of `{"crv","kty","x","y"}` in that order. */
    fun pubkeyId(jwk: String): String {
        val o = json.parseToJsonElement(jwk) as JsonObject
        fun f(k: String) = o.getValue(k).jsonPrimitive.content
        val canon = StringBuilder("{")
        listOf("crv", "kty", "x", "y").forEachIndexed { i, k ->
            if (i > 0) canon.append(',')
            Canonical.quote(k, canon); canon.append(':'); Canonical.quote(f(k), canon)
        }
        canon.append('}')
        return MessageDigest.getInstance("SHA-256").digest(canon.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** The label a person reads and compares: `AB12-CD34`. */
    fun keyLabel(jwk: String): String = pubkeyId(jwk).substring(0, 8).uppercase().let { it.substring(0, 4) + "-" + it.substring(4) }

    /** Two JWK strings name the same key when x and y match (field order and extras do not matter). */
    fun samePubkey(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return try {
            val x = json.parseToJsonElement(a) as JsonObject
            val y = json.parseToJsonElement(b) as JsonObject
            x["x"] == y["x"] && x["y"] == y["y"] && x["x"] != null
        } catch (_: Exception) { false }
    }

    /** `delegationBody`: what the issuer signed. Old papers (`exp`) and new ones (`seq`) have different bodies. */
    fun body(cert: JsonObject): JsonObject = buildJsonObject {
        val legacy = cert["seq"]?.jsonPrimitive?.longOrNull == null && cert["exp"]?.jsonPrimitive?.longOrNull != null
        for (k in listOf("v", "iss", "sub", "scope", "iat")) cert[k]?.let { put(k, it) }
        if (legacy) cert["exp"]?.let { put("exp", it) } else cert["seq"]?.let { put("seq", it) }
        cert["nonce"]?.let { put("nonce", it) }
    }

    fun scope(cert: JsonObject): List<String> = when (val s = cert["scope"]) {
        is JsonArray -> s.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOf(s.content)
        else -> emptyList()
    }

    /**
     * Is [cert] a paper for MY key, issued by the vault I enrolled with, and does it allow
     * [expectedScope]? Returns the reason it is not, or null when it is.
     *
     * Narrower than `checkVaultReply` of the pilar (which also verifies the whole record):
     * here the issuer must be the vault pinned at enrollment, not any sealer of the record.
     * A paper from another sealer is refused and said — never taken as good.
     */
    fun check(cert: JsonObject, vault: String, sub: String, expectedScope: String?): String? {
        val iss = cert["iss"]?.jsonPrimitive?.content ?: return "shape"
        val sig = cert["sig"]?.jsonPrimitive?.content ?: return "shape"
        if (!samePubkey(iss, vault)) return "paper-from-another-vault"
        if (!samePubkey(cert["sub"]?.jsonPrimitive?.content, sub)) return "sub"
        if (!Crypto.verify(iss, body(cert), sig)) return "bad-signature"
        if (expectedScope != null && expectedScope !in scope(cert)) return "scope"
        return null
    }
}
