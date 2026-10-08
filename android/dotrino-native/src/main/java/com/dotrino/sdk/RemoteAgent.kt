package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The CLIENT side of `@dotrino/remote-agent` (`src/client.js`, `e2e.js`, `protocol.js`): talk
 * to an agent — a terminal, an AI agent — running on another device of the SAME account.
 *
 * Both ends are devices the record (acta) names; neither has the master. The handshake is
 * signed by this phone's profile key with its vault paper; the agent answers with an ack
 * signed with ITS key and ITS paper, judged here against the record: whoever issued that
 * paper must be a sealer of this profile, and the paper may not name a record newer than
 * mine. Without a record there is nothing to judge with, and it is refused — never «ok».
 *
 * What travels after that is a domain payload each app defines, inside a session channel
 * (ECDH P-256 → HKDF-SHA256 → AES-256-GCM): the proxy only sees `{ type, sid, env }`.
 */
object RemoteAgent {
    const val HS = "ra.hs"
    const val ACK = "ra.hs.ack"
    const val DATA = "ra.data"
    const val PING = "ra.ping"
    const val PONG = "ra.pong"
    const val ERROR = "ra.error"
    private const val INFO = "dotrino-remote-agent-e2e"

    class RemoteAgentError(message: String, val code: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }
    private val curve by lazy {
        (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
    }

    // ---------- the session channel (e2e.js) ----------

    /** A fresh ECDH key for one handshake: the private half, and the public one as raw base64 (what the JS exports). */
    internal fun makeEphemeral(): Pair<PrivateKey, String> {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return kp.private to Crypto.b64(rawPoint(kp.public as ECPublicKey))
    }

    private fun fixed32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    internal fun rawPoint(pub: ECPublicKey): ByteArray = byteArrayOf(4) + fixed32(pub.w.affineX) + fixed32(pub.w.affineY)

    private fun pointOf(rawB64: String): ECPublicKey {
        val raw = Crypto.fromB64(rawB64)
        if (raw.size != 65 || raw[0] != 4.toByte()) throw RemoteAgentError("bad ephemeral key", "bad-ack")
        val x = BigInteger(1, raw.copyOfRange(1, 33)); val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** `deriveKey`: ECDH with the other end's ephemeral key, then HKDF-SHA256 (salt = the session id) to 32 bytes. */
    internal fun deriveKey(mine: PrivateKey, otherRawB64: String, salt: String): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").apply { init(mine); doPhase(pointOf(otherRawB64), true) }.generateSecret()
        val prk = hmac(salt.toByteArray(Charsets.UTF_8), shared)
        return hmac(prk, INFO.toByteArray(Charsets.UTF_8) + byteArrayOf(1))       // one block is the 32 bytes asked for
    }

    internal fun seal(key: ByteArray, payload: JsonObject): JsonObject {
        val (iv, ct) = Crypto.aesGcmSeal(key, payload.toString().toByteArray(Charsets.UTF_8))
        return buildJsonObject { put("iv", Crypto.b64(iv)); put("ct", Crypto.b64(ct)) }
    }

    internal fun open(key: ByteArray, env: JsonObject): JsonObject {
        val iv = env["iv"]?.jsonPrimitive?.content ?: throw RemoteAgentError("bad envelope", "bad-envelope")
        val ct = env["ct"]?.jsonPrimitive?.content ?: throw RemoteAgentError("bad envelope", "bad-envelope")
        val pt = Crypto.aesGcmOpen(key, Crypto.fromB64(iv), Crypto.fromB64(ct))
        return json.parseToJsonElement(String(pt, Charsets.UTF_8)) as JsonObject
    }

    // ---------- judging the agent (verifyChain) ----------

    /**
     * Is this ack from a device of MY profile? The port of `verifyChain` as the client calls it:
     * the device signed the data, the paper is for that device, a SEALER of my record issued
     * it, and it does not name a record newer than mine. Returns the reason it is not, or null.
     */
    internal fun judge(data: JsonObject, signature: String, cert: JsonObject?, acta: JsonObject?): String? {
        val device = data["publickey"]?.jsonPrimitive?.content ?: return "no-device-pubkey"
        if (!Crypto.verify(device, data, signature)) return "bad-action-signature"
        if (cert == null || cert["sub"]?.jsonPrimitive?.content != device) return "cert-device-mismatch"
        val iss = cert["iss"]?.jsonPrimitive?.content ?: return "shape"
        val sig = cert["sig"]?.jsonPrimitive?.content ?: return "shape"
        // A paper of the old model carried a date and no record number. They are retired here:
        // a native app was never issued one, and accepting it would be a rule with no test.
        val seq = cert["seq"]?.jsonPrimitive?.longOrNull ?: return "legacy-cert-retirado"
        if (!Crypto.verify(iss, Delegation.body(cert), sig)) return "bad-signature"
        val actaSeq = acta?.get("seq")?.jsonPrimitive?.longOrNull ?: return "no-acta"
        if (seq > actaSeq) return "acta-vieja"
        if (iss !in Acta.sealersOf(acta)) return "untrusted-issuer"
        return null
    }

    // ---------- who is there (probeAgents) ----------

    /**
     * Ask each key what it is, WITHOUT opening a session: the ones running an agent answer
     * with their `kind` (`terminal-agent`, `ia-agent`). Ephemeral: who is off is neither queued
     * nor rung. Returns key → kind for those that answered within [timeoutMs].
     */
    suspend fun probe(conn: ProxyConnection, pubkeys: List<String>, timeoutMs: Long = 3000): Map<String, String?> {
        val byNonce = pubkeys.associateBy { Crypto.b64(Crypto.randomBytes(9)) }
        val found = java.util.concurrent.ConcurrentHashMap<String, String>()
        val off = conn.onMessage { m ->
            if (m.payload["type"]?.jsonPrimitive?.content != PONG) return@onMessage
            val pub = byNonce[m.payload["n"]?.jsonPrimitive?.content] ?: return@onMessage
            found[pub] = (m.payload["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        }
        try {
            for ((n, pub) in byNonce) conn.sendByPubkey(pub, buildJsonObject { put("type", PING); put("n", n) }, ephemeral = true)
            val until = System.currentTimeMillis() + timeoutMs
            while (found.size < pubkeys.size && System.currentTimeMillis() < until) delay(60)
        } finally { off() }
        return found.mapValues { it.value.ifEmpty { null } }
    }

    /** The other devices of the profile, from its record: who could be running an agent. */
    fun candidates(profile: Profile): List<Pair<String, String?>> =
        (profile.acta?.get("members") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { m ->
            val pub = (m["pub"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (pub == profile.publickey) null else pub to (m["label"] as? JsonPrimitive)?.content
        }

    // ---------- a session ----------

    /**
     * Open a session with the agent at [agentPubkey] over [conn] (connected and identified as
     * the profile). Throws [RemoteAgentError]: `no-vault` (this profile is not linked to a
     * vault), `no-reply`, `refused` (the agent said no, with its reason), `not-mine` (the ack
     * does not hold against the record, with the reason), `bad-ack`.
     */
    suspend fun open(
        profile: Profile, conn: ProxyConnection, agentPubkey: String, timeoutMs: Long = 20_000,
        /**
         * Puts this phone's record up to date ([PhoneIdentity.catchUp]). Called ONCE, when the
         * agent's paper names a newer record than mine (`acta-vieja`), and the ack is judged
         * again against what it returns. Without it, `acta-vieja` stops the session.
         */
        catchUp: (suspend () -> Profile)? = null,
    ): Session {
        val cert = profile.vault?.cert ?: throw RemoteAgentError("this profile is not linked to a vault", "no-vault")
        val (ephPriv, ephPub) = makeEphemeral()
        val data = buildJsonObject {
            put("op", HS); put("eph", ephPub); put("publickey", profile.publickey); put("ts", System.currentTimeMillis())
        }
        val signature = profile.signData(data)

        // The ack is matched by MY ephemeral key, so two sessions opening at once do not take each other's.
        val acked = CompletableDeferred<JsonObject>()
        // The agent's TOKEN comes with its ack: from then on it is spoken to BY TOKEN, which is the
        // only thing that goes up to WebRTC (the JS client does the same).
        var ackFrom: String? = null
        val off = conn.onMessage { m ->
            val p = m.payload
            when (p["type"]?.jsonPrimitive?.content) {
                ACK -> if (((p["ack"] as? JsonObject)?.get("ceph") as? JsonPrimitive)?.content == ephPub) { ackFrom = m.from; acked.complete(p) }
                ERROR -> acked.completeExceptionally(RemoteAgentError(p["error"]?.jsonPrimitive?.content ?: "the agent refused", "refused"))
            }
        }
        val res = try {
            conn.sendByPubkey(agentPubkey, buildJsonObject {
                put("type", HS); put("data", data); put("signature", signature); put("cert", cert)
            })
            try { withTimeout(timeoutMs) { acked.await() } }
            catch (e: TimeoutCancellationException) { throw RemoteAgentError("the agent did not reply (is it running there?)", "no-reply") }
        } finally { off() }

        val ack = res["ack"] as? JsonObject ?: throw RemoteAgentError("bad ack", "bad-ack")
        val sid = res["sid"]?.jsonPrimitive?.content ?: throw RemoteAgentError("bad ack", "bad-ack")
        val ackSig = res["signature"]?.jsonPrimitive?.content ?: ""
        val ackCert = res["cert"] as? JsonObject
        var why = judge(ack, ackSig, ackCert, profile.acta)
        // The agent's paper is from a record newer than mine: the vault changed it and this phone
        // has not heard. Catch up and judge again — once; what still fails is said as it is.
        if (why == "acta-vieja" && catchUp != null) why = judge(ack, ackSig, ackCert, catchUp().acta)
        why?.let { throw RemoteAgentError("that agent is not certified by your vault: $it", "not-mine") }
        if (!Delegation.samePubkey(ack["machine"]?.jsonPrimitive?.content, agentPubkey)) throw RemoteAgentError("the ack came from another agent", "bad-ack")
        if (ack["ceph"]?.jsonPrimitive?.content != ephPub || ack["sid"]?.jsonPrimitive?.content != sid) throw RemoteAgentError("the ack is not for this handshake", "bad-ack")
        val seph = ack["seph"]?.jsonPrimitive?.content ?: throw RemoteAgentError("bad ack", "bad-ack")
        return Session(conn, agentPubkey, sid, deriveKey(ephPriv, seph, sid), ackFrom)
    }

    /**
     * An agent error as ONE session sees it. One that names another session (`sid`) is not for
     * it: the tabs of a phone share one connection, and each one used to take every error as its
     * own. The agent's `code` travels (`unknown-session`: it restarted and no longer knows this
     * session, remote-agent ≥ 0.14.0); an agent that sends none gives `agent-error`, as before.
     */
    internal fun sessionError(p: JsonObject, sid: String): RemoteAgentError? {
        val other = p["sid"]?.jsonPrimitive?.contentOrNull
        if (other != null && other != sid) return null
        return RemoteAgentError(p["error"]?.jsonPrimitive?.contentOrNull ?: "agent error", p["code"]?.jsonPrimitive?.contentOrNull ?: "agent-error")
    }

    /** An open session: domain payloads both ways, sealed with the session key. */
    class Session internal constructor(
        private val conn: ProxyConnection,
        val agentPubkey: String,
        val sid: String,
        private val key: ByteArray,
        /** The agent's token, from its ack; null = not known (or dead): by pubkey, to the queue. */
        @Volatile var agentToken: String? = null,
    ) {
        private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()
        private val errors = CopyOnWriteArrayList<(RemoteAgentError) -> Unit>()
        // A token dies with its connection (the agent restarted): back to the pubkey until the next ack.
        private val offEvent: () -> Unit = conn.onEvent { e -> if (e is ProxyConnection.Event.PeerGone && e.token == agentToken) agentToken = null }
        private val off: () -> Unit = conn.onMessage { m ->
            val p = m.payload
            when (p["type"]?.jsonPrimitive?.content) {
                DATA -> if (p["sid"]?.jsonPrimitive?.content == sid) {
                    val env = p["env"] as? JsonObject ?: return@onMessage
                    // What does not open with this session's key is not for it: dropped, like the JS does.
                    val msg = try { open(key, env) } catch (_: Exception) { return@onMessage }
                    listeners.forEach { it(msg) }
                }
                // The agent no longer knows this session (it restarted, or it expired), or another error.
                ERROR -> sessionError(p, sid)?.let { e -> errors.forEach { it(e) } }
            }
        }

        fun onMessage(l: (JsonObject) -> Unit): () -> Unit { listeners.add(l); return { listeners.remove(l) } }
        fun onError(l: (RemoteAgentError) -> Unit): () -> Unit { errors.add(l); return { errors.remove(l) } }

        fun send(payload: JsonObject) {
            val msg = buildJsonObject { put("type", DATA); put("sid", sid); put("env", seal(key, payload)) }
            // By token as soon as it is known: it goes up to the direct road, and a dead token falls
            // back to the pubkey by itself. By pubkey only while there is no token.
            val t = agentToken
            if (t != null) conn.sendToOrUpgrade(t, msg, agentPubkey) else conn.sendByPubkey(agentPubkey, msg)
        }

        /** Stop listening. The session on the agent expires by itself; what the app opened there is the app's to close. */
        fun close() { off(); offEvent(); listeners.clear(); errors.clear() }
    }
}
