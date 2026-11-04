package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * THE SUBJECT OF A RING, sealed to this phone (vaultd ≥ 0.190.0, websocket-proxy ≥ 1.11.0).
 *
 * The ring that reaches FCM/APNs carries nothing Google or Apple can read. Next to it the vault
 * now puts a `sealed` subject — who asks for what — encrypted to the approver's key, and the
 * proxy passes it through as is. The phone opens it here, without the network, so the notice
 * says the why even when the vault is slow to answer.
 *
 * It is ONLY the subject. Approving is still done from the list (`approvals`), which is signed:
 * a forged subject approves nothing.
 */
object VaultRing {
    private val json = Json { ignoreUnknownKeys = true }

    /** The subject, or `null` when [sealed] is not for [keys] (another account) or is malformed. */
    suspend fun open(sealed: String, keys: DeviceKeys): JsonObject? = runCatching {
        val o = json.parseToJsonElement(sealed).jsonObject
        val wrap = o["wrap"] as? JsonObject ?: return null
        val envelope = o["envelope"] as? JsonObject ?: return null
        json.parseToJsonElement(Crypto.openSealed(wrap, envelope, keys)).jsonObject
    }.getOrNull()

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** An `approval` subject as the request it announces (the list stays the authority). */
    fun approval(s: JsonObject): Approval? {
        if (s.str("ev") != "approval") return null
        return Approval(
            id = s.str("id") ?: return null, ns = s.str("ns") ?: "", kind = s.str("kind") ?: "read",
            deviceId = s.str("deviceId") ?: "", label = s.str("label") ?: "",
            exp = (s["exp"] as? JsonPrimitive)?.longOrNull ?: 0, ctx = s["ctx"] as? JsonObject, ctxError = null,
        )
    }

    /** A notice subject (`updated`, `update-needs-root`, `delivered`) as the notice it announces. */
    fun notice(s: JsonObject): VaultNotice? {
        val ev = s.str("ev") ?: return null
        if (ev == "approval") return null
        return VaultNotice(
            id = s.str("id") ?: return null, ev = ev, version = s.str("version") ?: "", from = s.str("from") ?: "",
            ts = (s["ts"] as? JsonPrimitive)?.longOrNull ?: 0, product = s.str("product"),
            deviceId = s.str("deviceId"), label = s.str("label"), ns = s.str("ns"), kind = s.str("kind"),
        )
    }
}
