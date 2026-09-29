package com.dotrino.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * THE REPUTATION REGISTRY (`rep.dotrino.com`) for native apps: `createVaultReputation` of
 * `@dotrino/reputation`. Publishing a rating is a SIGNED attestation per axis (`op: 'rate'`,
 * with the signing package: the registry checks this device speaks for the person); reading
 * is public and weighed by MY web of trust ([aggregateTrust], anti-sybil: a million strangers
 * do not move the needle, one person I trust does).
 *
 * `trustOf` comes from the peer book (my own rating of someone, 0..1).
 */
class Reputation(
    private val profile: Profile,
    private val peers: PeerBook,
    baseUrl: String = DEFAULT_BASE,
) {
    companion object {
        const val DEFAULT_BASE = "https://rep.dotrino.com"
        private val json = Json { ignoreUnknownKeys = true }
        private val http by lazy { OkHttpClient() }
        private val CHANNEL = Regex("^[a-z][a-z0-9_]{0,23}$")
        private const val TTL_OK = 30_000L
        private const val TTL_ERR = 5_000L

        /** `pubkeyId` of the package: a stable id of a JWK, for dedup and cycles. */
        fun pubkeyId(jwk: String): String = try {
            val j = json.parseToJsonElement(jwk) as JsonObject
            "${j["crv"]?.jsonPrimitive?.content}:${j["x"]?.jsonPrimitive?.content}:${j["y"]?.jsonPrimitive?.content}"
        } catch (_: Exception) { jwk }

        private fun indicatorsOf(a: JsonObject): Map<String, Double> {
            (a["indicators"] as? JsonObject)?.let { m -> return m.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap() }
            return (a["rating"] as? JsonPrimitive)?.doubleOrNull?.let { mapOf("confianza" to it) } ?: emptyMap()
        }
    }

    class ReputationError(message: String, val code: String) : Exception(message)

    /** Per axis: score 0..1 (null = nobody of my network said anything), confidence and how many of my network. */
    data class Indicator(val score: Double?, val confidence: Double, val trustedCount: Int)
    data class Aggregate(val score: Double?, val confidence: Double, val trustedCount: Int, val rawCount: Int, val indicators: Map<String, Indicator>)

    private val base = baseUrl.trimEnd('/')
    /** For WHOM what we sign is meant: the registry's origin (a self-hosted one is another). */
    private val aud = URI(base).let { "${it.scheme}://${it.authority}" }
    private val cache = ConcurrentHashMap<String, Pair<Long, Result<List<JsonObject>>>>()

    private suspend fun http(req: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            if (!r.isSuccessful) throw ReputationError("reputation: " + (o?.get("error")?.jsonPrimitive?.content ?: "HTTP ${r.code}"), "http-${r.code}")
            o ?: JsonObject(emptyMap())
        }
    }

    /** `firmar`: data + aud + issuer (the PERSON), signed, with signer and chain alongside. */
    private suspend fun signed(data: JsonObject): JsonObject {
        val full = JsonObject(data + mapOf("aud" to JsonPrimitive(aud), "issuer" to JsonPrimitive(profile.profileId)))
        val pkg = profile.signPackage(full)
        return buildJsonObject {
            put("data", full); put("signature", pkg["signature"]!!); put("signer", pkg["publickey"]!!)
            val chain = pkg["chain"] as? JsonArray
            if (!chain.isNullOrEmpty()) put("chain", chain)
        }
    }

    /**
     * `rate`: my rating of [subject], one signed attestation per axis (`{ confianza: 5, afinidad: 3 }`,
     * integers 0..5). The `confianza` axis also goes to my local web of trust (the peer book).
     */
    suspend fun rate(subject: String, indicators: Map<String, Int>, notes: String? = null) {
        require(subject.isNotEmpty()) { "subject required" }
        indicators["confianza"]?.let { peers.setRating(subject, it.toDouble(), notes ?: "") }
        for ((channel, value) in indicators) {
            if (!CHANNEL.matches(channel)) throw ReputationError("invalid channel $channel", "bad-channel")
            if (value !in 0..5) throw ReputationError("value must be an integer 0..5", "bad-value")
            val body = signed(buildJsonObject {
                put("op", "rate"); put("subject", subject); put("channel", channel); put("value", value)
                put("ts", System.currentTimeMillis()); if (notes != null) put("notes", notes.take(280))
            })
            cache.remove(subject)
            http(Request.Builder().url("$base/ratings").put(body.toString().toRequestBody("application/json".toMediaType())).build())
        }
    }

    /** `removeChannel`: withdraw MY attestation of one axis (a zero is a rating; withdrawing is this). */
    suspend fun removeChannel(subject: String, channel: String) {
        if (!CHANNEL.matches(channel)) throw ReputationError("invalid channel $channel", "bad-channel")
        val body = signed(buildJsonObject { put("op", "unrate"); put("subject", subject); put("channel", channel); put("ts", System.currentTimeMillis()) })
        cache.remove(subject)
        http(Request.Builder().url("$base/ratings").delete(body.toString().toRequestBody("application/json".toMediaType())).build())
    }

    /** Every attestation about [subject] (raw). Public read, cached 30 s (5 s after an error). */
    suspend fun ratings(subject: String): List<JsonObject> {
        cache[subject]?.let { (ts, r) -> if (System.currentTimeMillis() - ts < (if (r.isSuccess) TTL_OK else TTL_ERR)) return r.getOrThrow() }
        val r = runCatching {
            val url = "$base/ratings?subject=" + URLEncoder.encode(subject, "UTF-8")
            (http(Request.Builder().url(url).get().build())["attestations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }
        cache[subject] = System.currentTimeMillis() to r
        return r.getOrThrow()
    }

    /** `trustOf`: my direct trust in [pk], 0..1, from my own rating in the peer book; null = no opinion. */
    suspend fun trustOf(pk: String): Double? =
        (peers.ratingsFor(pk).first?.get("rating") as? JsonPrimitive)?.doubleOrNull?.let { (it / 5).coerceIn(0.0, 1.0) }

    /** `myIndicatorsFor`: what I rated [subject], merged across axes (the registry keeps one per axis). */
    suspend fun myIndicatorsFor(subject: String): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        for (a in ratings(subject)) {
            val issuer = (a["issuer"] as? JsonPrimitive)?.content ?: continue
            if (!Delegation.samePubkey(issuer, profile.profileId)) continue
            out.putAll(indicatorsOf(a))
        }
        return out
    }

    /**
     * `aggregateTrust` / `reputationOf`: the reputation of [subject] weighed by the credibility
     * I give to each issuer — direct (my rating) or transitive through my network, with
     * [decay] per hop up to [maxDepth], always anchored on `confianza`.
     */
    suspend fun aggregateTrust(subject: String, maxDepth: Int = 2, decay: Double = 0.5, minCredibility: Double = 0.05): Aggregate {
        val credCache = HashMap<String, Double>()
        suspend fun credibility(pk: String, depth: Int, visited: Set<String>): Double {
            val id = pubkeyId(pk)
            credCache[id]?.let { return it }
            if (Delegation.samePubkey(pk, profile.profileId)) return 1.0.also { credCache[id] = it }
            trustOf(pk)?.takeIf { it > 0 }?.let { credCache[id] = it; return it }
            if (depth >= maxDepth) return 0.0.also { credCache[id] = it }
            var best = 0.0
            for (a in runCatching { ratings(pk) }.getOrDefault(emptyList())) {
                val issuer = (a["issuer"] as? JsonPrimitive)?.content ?: continue
                val iid = pubkeyId(issuer)
                if (iid == id || iid in visited) continue
                val c = credibility(issuer, depth + 1, visited + iid)
                if (c <= 0) continue
                val conf = indicatorsOf(a)["confianza"] ?: 0.0
                best = maxOf(best, c * (conf / 5).coerceIn(0.0, 1.0))
            }
            return (decay * best).also { credCache[id] = it }
        }
        val atts = runCatching { ratings(subject) }.getOrDefault(emptyList())
        data class Acc(var sum: Double = 0.0, var w: Double = 0.0, var n: Int = 0)
        val acc = linkedMapOf<String, Acc>()
        for (a in atts) {
            val issuer = (a["issuer"] as? JsonPrimitive)?.content ?: continue
            if (Delegation.samePubkey(issuer, subject)) continue // nobody rates themselves with weight
            val ind = indicatorsOf(a)
            if (ind.isEmpty()) continue
            val cred = credibility(issuer, 1, setOf(pubkeyId(subject)))
            if (cred >= minCredibility) for ((k, v) in ind) acc.getOrPut(k) { Acc() }.apply { sum += cred * v; w += cred; n++ }
        }
        val round3 = { d: Double -> Math.round(d * 1000) / 1000.0 }
        val indicators = acc.mapValues { (_, a) ->
            Indicator(if (a.w > 0) ((a.sum / a.w) / 5).coerceIn(0.0, 1.0) else null, round3(1 - Math.exp(-a.w)), a.n)
        }
        val c = indicators["confianza"] ?: Indicator(null, 0.0, 0)
        return Aggregate(c.score, c.confidence, c.trustedCount, atts.size, indicators)
    }
}
