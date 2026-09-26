package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * An account this phone approves for natively: which vault, through which proxy, and the
 * paper (`cert`) the vault gave to this device's key. No secret in here — the keys live in
 * the Keystore under [id].
 */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val profileId: String? = null,
    val vault: String,
    val proxy: String,
    val cert: JsonObject,
    val deviceId: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/** A pending request, as the vault lists it. [ctx] is already opened with my key, when it came sealed. */
data class Approval(
    val id: String,
    val ns: String,
    val kind: String,
    val deviceId: String,
    val label: String,
    val exp: Long,
    val ctx: JsonObject?,
    /** Why the context could not be read, when it came but did not open (or was not sealed to me). */
    val ctxError: String?,
)

data class Grant(val id: String, val ns: String, val deviceId: String, val label: String, val exp: Long, val uses: Long, val ctx: JsonObject?)

class VaultError(message: String, val code: String) : Exception(message)

/**
 * Talks to ONE account's vault over one [ProxyConnection]. Same messages as `vaultRpc` in
 * `@dotrino/identity/vault/remote.js`: `{ type, data: {…, publickey, ts}, signature, cert }`
 * to the vault's key, and the answer comes back to this connection.
 *
 * The vault's answers carry no request id, so calls on one account go ONE AT A TIME (the
 * mutex): with two in flight, the first answer would settle whichever asked first.
 */
class VaultClient(
    account: Account,
    private val keys: DeviceKeys,
    private val conn: ProxyConnection,
    /** Called with the renewed account so the caller persists the new paper. */
    private val onRenewed: (Account) -> Unit = {},
) {
    companion object {
        const val SECRETS = "vault.secrets"
        const val SECRETS_RESULT = "vault.secrets.result"
        const val RENEW = "vault.renew"
        const val RENEWED = "vault.renewed"
        const val ERROR = "vault.error"
        const val ADMIN_EVENT = "vault.admin.event"
        const val SCOPE_APPROVE = "vault:approve"
        /** The record does not let this key approve (yet): the owner has to give it `+aprueba`. */
        const val NO_APPROVE = "no-approve"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Volatile var account: Account = account; private set
    private val lock = Mutex()

    private suspend fun rpc(sendType: String, okType: String, data: JsonObject, timeoutMs: Long = 15_000): JsonObject = lock.withLock {
        val signed = JsonObject(data + mapOf("publickey" to JsonPrimitive(keys.publickey), "ts" to JsonPrimitive(System.currentTimeMillis())))
        val answer = CompletableDeferred<JsonObject>()
        val off = conn.onMessage { m ->
            when (m.payload["type"]?.jsonPrimitive?.content) {
                okType -> answer.complete(m.payload)
                ERROR -> {
                    val msg = m.payload["error"]?.jsonPrimitive?.content ?: "vault error"
                    answer.completeExceptionally(VaultError(msg, codeOf(msg)))
                }
            }
        }
        try {
            conn.sendByPubkey(account.vault, buildJsonObject {
                put("type", sendType); put("data", signed)
                put("signature", keys.sign(Canonical.stringify(signed))); put("cert", account.cert)
            })
            try { withTimeout(timeoutMs) { answer.await() } }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw VaultError("the vault did not reply (is it running?)", "vault-no-reply")
            }
        } finally { off() }
    }

    /** `unauthorized: <reason>` → the reason, which is what can be acted on. */
    private fun codeOf(msg: String): String = Regex("^unauthorized: ([\\w-]+)").find(msg)?.groupValues?.get(1) ?: "vault-error"

    /**
     * Once, and only for a paper problem: after `+aprueba` the old paper does not carry
     * `vault:approve`, and after a record change it is behind. A revoked device or one the
     * record no longer lets approve is NOT retried — renewing cannot fix that, and retrying
     * would only hide the reason.
     */
    private suspend fun <T> withPaper(block: suspend () -> T): T = try { block() } catch (e: VaultError) {
        if (e.code in setOf("scope", "acta-vieja", "untrusted-issuer", "expired", "legacy-cert-retirado", "no-acta")) { renew(); block() } else throw e
    }

    /** A new paper for my key. Accepted only if it is from the vault I enrolled with, for my key, and lets me approve. */
    suspend fun renew(): Account {
        val res = rpc(RENEW, RENEWED, buildJsonObject { put("op", "renew") })
        val cert = res["cert"] as? JsonObject ?: throw VaultError("the vault did not send a paper", "no-cert")
        Delegation.check(cert, account.vault, keys.publickey, SCOPE_APPROVE)?.let {
            // A good paper without `vault:approve` is not a broken paper: the record simply
            // does not let this key approve yet (`+aprueba` is missing). Said as such, so the
            // screen can tell the owner what to do instead of «invalid renewed paper: scope».
            if (it == "scope") throw VaultError("the record does not let this device approve", NO_APPROVE)
            throw VaultError("invalid renewed paper: $it", it)
        }
        account = account.copy(cert = cert)
        onRenewed(account)
        return account
    }

    private suspend fun secrets(data: JsonObject): JsonObject = withPaper {
        val res = rpc(SECRETS, SECRETS_RESULT, data)
        res["body"] as? JsonObject ?: throw VaultError("the vault answered without a body", "no-body")
    }

    suspend fun approvals(): List<Approval> {
        val body = secrets(buildJsonObject { put("op", "approvals") })
        return (body["items"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { (it as? JsonObject)?.let(::approvalOf) }
    }

    suspend fun approve(id: String) = answer("approve", id)
    suspend fun deny(id: String) = answer("deny", id)

    private suspend fun answer(op: String, id: String) {
        val body = secrets(buildJsonObject { put("op", op); put("id", id) })
        if (body["ok"]?.jsonPrimitive?.booleanOrNull != true) throw VaultError("the vault did not confirm the $op", "not-confirmed")
    }

    suspend fun grants(): List<Grant> {
        val body = secrets(buildJsonObject { put("op", "grants") })
        return (body["items"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Grant(
                id = o.str("id") ?: return@mapNotNull null, ns = o.str("ns") ?: "", deviceId = o.str("deviceId") ?: "",
                label = o.str("label") ?: "", exp = o.long("exp") ?: 0, uses = o.long("uses") ?: 0, ctx = openCtx(o).first,
            )
        }
    }

    suspend fun revokeGrant(id: String) {
        val body = secrets(buildJsonObject { put("op", "grant-revoke"); put("id", id) })
        if (body["ok"]?.jsonPrimitive?.booleanOrNull != true) throw VaultError("that approval was no longer active", "not-found")
    }

    private fun approvalOf(o: JsonObject): Approval? {
        val (ctx, err) = openCtx(o)
        return Approval(
            id = o.str("id") ?: return null, ns = o.str("ns") ?: "", kind = o.str("kind") ?: "read",
            deviceId = o.str("deviceId") ?: "", label = o.str("label") ?: "", exp = o.long("exp") ?: 0,
            ctx = ctx, ctxError = err,
        )
    }

    /** The context comes sealed to MY encryption key; open it here. Not opening is said, never hidden. */
    private fun openCtx(o: JsonObject): Pair<JsonObject?, String?> {
        (o["ctx"] as? JsonObject)?.let { return it to null }
        val wrap = o["ctxWrap"] as? JsonObject
        val env = o["ctxEnvelope"] as? JsonObject
        if (wrap != null && env != null) {
            return try { (json.parseToJsonElement(Crypto.openSealed(wrap, env, keys)) as JsonObject) to null }
            catch (e: Exception) { null to "cannot-open" }
        }
        if (o["ctxSealed"]?.jsonPrimitive?.booleanOrNull == false) return null to (o.str("ctxReason") ?: "not-sealed")
        return null to null
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
}
