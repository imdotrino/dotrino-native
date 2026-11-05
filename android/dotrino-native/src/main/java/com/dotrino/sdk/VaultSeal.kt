package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * WHAT A DEVICE ASKS ITS VAULT GOES SEALED, AND THE ANSWER COMES BACK SEALED
 * (`dotrino-vault/docs/sealed-requests.md`; same piece as `VaultSeal.swift` and
 * `@dotrino/identity/vault-seal`).
 *
 * The WHOLE request `{ type, data, signature, cert }` is sealed with the pilar's envelope
 * (`{ v: 1, sealed: wrapForMember(…) }`) to the transport key of each vault of the account (the
 * record's members with `cn: "vault"`), and routed to the account. Inside what is signed goes
 * a ONE-USE key (`rek`); the vault seals its answer to it. Outside the envelope: only where it goes.
 *
 * MIGRATION — EXPIRES 2026-12-15 ([PLAIN_UNTIL_MS]). Until then, with nobody to seal to, the
 * request goes in the clear and an unsealed answer of an old vault is taken; both are said
 * through `onWarn`. From that date, neither.
 */
object VaultSeal {
    const val PLAIN_UNTIL_MS = 1_797_292_800_000L   // 2026-12-15T00:00:00Z
    fun plainAllowed(now: Long = System.currentTimeMillis()) = now < PLAIN_UNTIL_MS

    /** The communication keys of the account's vaults, from a record. */
    fun vaultKeys(acta: JsonObject?): List<String> =
        (acta?.get("members") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["cn"] as? JsonPrimitive)?.content == "vault" }
            .mapNotNull { (it["pub"] as? JsonPrimitive)?.content?.takeIf { p -> p.isNotEmpty() } }
            .distinct()

    private fun seqOf(a: JsonObject?) = (a?.get("seq") as? JsonPrimitive)?.longOrNull ?: 0L

    /**
     * The account's newest record the proxy keeps (`acta-get`) that verifies as sealed by the
     * account; null without one, or with a proxy that keeps none (`no-acta-cache`, not an error).
     */
    suspend fun accountRecord(conn: ProxyConnection, profileId: String): JsonObject? {
        val actas = try { conn.getActas(profileId) } catch (e: ProxyConnection.ProxyError) {
            if (e.code == "no-acta-cache") return null else throw e
        }
        return actas.filter { Acta.verify(it, profileId) == null }.maxByOrNull { seqOf(it) }
    }

    /** The newer of two records. */
    fun newest(a: JsonObject?, b: JsonObject?): JsonObject? = if (seqOf(b) > seqOf(a)) b else a ?: b

    /** The pilar's envelope: `{ v: 1, sealed: wrapForMember(JSON text, encPub) }`. */
    fun seal(payload: JsonObject, encPub: String): JsonObject = buildJsonObject {
        put("v", 1); put("sealed", Crypto.wrapForMember(payload.toString(), encPub))
    }

    fun isSealed(m: JsonObject): Boolean {
        val s = m["sealed"] as? JsonObject ?: return false
        return (m["v"] as? JsonPrimitive)?.content == "1" && s["ct"] != null && s["epk"] != null
    }

    /** The one-use key the answer is sealed to. */
    class ReplyKey {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val encPub: String = Crypto.jwkOf(pair.public as ECPublicKey)

        /** Opens an envelope sealed to this key; null when it is not for it. */
        fun open(envelope: JsonObject): JsonObject? = try {
            val w = envelope["sealed"] as JsonObject
            val secret = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(Crypto.publicKeyOf(w["epk"]!!.jsonPrimitive.content), true); generateSecret() }
            val plain = Crypto.aesGcmOpen(secret, Crypto.fromB64(w["iv"]!!.jsonPrimitive.content), Crypto.fromB64(w["ct"]!!.jsonPrimitive.content))
            Json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) { null }
    }

    /**
     * Sends [payload] sealed to every vault of [acta], routed to [dest]. Returns how many
     * envelopes went; 0 = in the clear (migration, said through [onWarn]).
     */
    suspend fun send(conn: ProxyConnection, dest: String, acta: JsonObject?, payload: JsonObject,
                     onWarn: (String) -> Unit = {}, now: Long = System.currentTimeMillis()): Int {
        val keys = vaultKeys(acta)
        var lastCode = if (keys.isEmpty()) "no-vault-key" else "no-encpub"
        val encPubs = keys.mapNotNull { k ->
            try { conn.encPubOf(k) } catch (e: ProxyConnection.ProxyError) { lastCode = e.code; null }
        }
        if (encPubs.isNotEmpty()) {
            encPubs.forEach { conn.sendByPubkey(dest, seal(payload, it)) }
            return encPubs.size
        }
        if (!plainAllowed(now)) throw VaultError("cannot seal the request to the vault ($lastCode): update the vault", "vault-unsealable")
        val type = (payload["type"] as? JsonPrimitive)?.content ?: ""
        onWarn("[vault] request $type sent UNSEALED ($lastCode): tolerated only until 2026-12-15 — update the vault")
        conn.sendByPubkey(dest, payload)
        return 0
    }
}
