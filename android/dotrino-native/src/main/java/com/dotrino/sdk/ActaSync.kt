package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * PUT THE PHONE'S RECORD UP TO DATE, from the vault: what the identity does when it lists the
 * vault's devices (`listVaultDevices` → `adoptChain`), for native apps.
 *
 * Without this a native app judged other devices against the record it found in the identity
 * app, and that one only moved when some WebView of the identity ran. The vault changed its
 * record (a new device, a permission), re-issued the agents' papers with the new `seq`, and the
 * phone answered every one of them with `acta-vieja` until someone opened a web page.
 *
 * Nothing is taken on the vault's word: each record of the chain must verify and chain from
 * the one adopted before it ([Acta.canAdopt]), as the identity does. What is adopted is
 * written back to the identity app, so every app of the phone sees it.
 */
object ActaSync {
    class ActaSyncError(message: String, val code: String) : Exception(message)

    private const val DEVICES = "vault.devices"
    private const val DEVICES_RESULT = "vault.devices.result"
    private const val ERROR = "vault.error"

    /**
     * Asks the vault for the records after mine and adopts what chains. Returns the profile with
     * the newest record, or the same one when there was nothing newer. Throws [ActaSyncError]:
     * `no-vault`, `vault-no-reply`, `vault-error`, `no-record` (the vault sent nothing to adopt),
     * `not-adopted` (nothing it sent chains from mine: the reason of the first one).
     *
     * [save] keeps the adopted record where the identity reads it (key, JSON).
     */
    suspend fun update(profile: Profile, conn: ProxyConnection, save: suspend (String, String) -> Unit, timeoutMs: Long = 15_000): Profile {
        val v = profile.vault ?: throw ActaSyncError("this profile is not linked to a vault", "no-vault")
        val res = devices(profile, v, conn, timeoutMs)
        val chain = (res["chain"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val single = res["acta"] as? JsonObject
        val candidates = chain.ifEmpty { listOfNotNull(single) }
        if (candidates.isEmpty()) throw ActaSyncError("the vault sent no record", "no-record")
        val current = profile.acta
        val adopted = Acta.adoptChain(candidates, current)
        if (adopted == null) {
            val why = candidates.maxByOrNull { (it["seq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0 }?.let { Acta.canAdopt(it, current).second }
            // The same record as mine: there is nothing newer, which is not an error.
            if (why == "misma-acta" || why == "seq-menor") return profile
            throw ActaSyncError("the vault's record does not chain from this phone's: $why", "not-adopted")
        }
        profile.pid?.let { save(Profile.actaKey(it), adopted.toString()) }
        return profile.withActa(adopted)
    }

    /** `requestDevices` of the identity: `{ op: 'devices', sinceSeq }`, signed as this device, with its paper. */
    private suspend fun devices(profile: Profile, v: Profile.VaultLink, conn: ProxyConnection, timeoutMs: Long): JsonObject {
        val data = buildJsonObject {
            put("op", "devices"); put("sinceSeq", profile.actaSeq ?: 0)
            put("publickey", profile.publickey); put("ts", System.currentTimeMillis())
        }
        val signature = profile.signAsDevice(data)
        val answer = CompletableDeferred<JsonObject>()
        val off = conn.onMessage { m ->
            when (m.payload["type"]?.jsonPrimitive?.content) {
                DEVICES_RESULT -> answer.complete(m.payload)
                ERROR -> answer.completeExceptionally(ActaSyncError(m.payload["error"]?.jsonPrimitive?.content ?: "vault error", "vault-error"))
            }
        }
        try {
            conn.sendByPubkey(v.master, buildJsonObject {
                put("type", DEVICES); put("data", data); put("signature", signature); put("cert", v.cert)
            })
            return try { withTimeout(timeoutMs) { answer.await() } }
            catch (e: TimeoutCancellationException) { throw ActaSyncError("the vault did not reply (is it running?)", "vault-no-reply") }
        } finally { off() }
    }
}
