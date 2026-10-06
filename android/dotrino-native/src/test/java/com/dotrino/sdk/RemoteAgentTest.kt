package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import java.util.Base64

/**
 * The client of `@dotrino/remote-agent`: the session channel against a vector the JS wrote
 * (`e2e.js`: one side derived the key and sealed), and the judging of an agent's ack against
 * the record — who may be believed, and why not when not.
 */
class RemoteAgentTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("remote-agent.json")!!.readText()).jsonObject

    private fun priv(j: JsonObject): PrivateKey {
        val pub = """{"kty":"EC","crv":"P-256","x":"${j["x"]!!.jsonPrimitive.content}","y":"${j["y"]!!.jsonPrimitive.content}"}"""
        val d = BigInteger(1, Base64.getUrlDecoder().decode(j["d"]!!.jsonPrimitive.content))
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, (Crypto.publicKeyOf(pub) as ECPublicKey).params))
    }

    @Test fun opensWhatTheJsAgentSealed() {
        val sid = v.getValue("sid").jsonPrimitive.content
        val key = RemoteAgent.deriveKey(priv(v.getValue("clientPrivateJwk").jsonObject), v.getValue("agentRaw").jsonPrimitive.content, sid)
        assertEquals(v.getValue("msg").jsonObject, RemoteAgent.open(key, v.getValue("env").jsonObject))
    }

    @Test fun bothEndsDeriveTheSameKey() {
        val (aPriv, aPub) = RemoteAgent.makeEphemeral(); val (bPriv, bPub) = RemoteAgent.makeEphemeral()
        val msg = buildJsonObject { put("type", "input"); put("data", "ls -la\r") }
        val env = RemoteAgent.seal(RemoteAgent.deriveKey(aPriv, bPub, "s1"), msg)
        assertEquals(msg, RemoteAgent.open(RemoteAgent.deriveKey(bPriv, aPub, "s1"), env))
        // Another session id is another key: what was sealed for one does not open in the other.
        assertEquals(true, runCatching { RemoteAgent.open(RemoteAgent.deriveKey(bPriv, aPub, "s2"), env) }.isFailure)
    }

    /** A record with [sealer] sealing and [device] a plain member, and the device's paper issued by [issuer] naming record [seq]. */
    private suspend fun world(sealer: TestKeys, device: TestKeys, issuer: TestKeys, seq: Long, actaSeq: Long): Triple<JsonObject, JsonObject, JsonObject> {
        val acta = buildJsonObject {
            put("v", 5); put("seq", actaSeq)
            put("members", buildJsonArray {
                add(buildJsonObject { put("pub", sealer.publickey); put("caps", buildJsonArray { add(JsonPrimitive("sealer")); add(JsonPrimitive("sign")) }) })
                add(buildJsonObject { put("pub", device.publickey); put("caps", buildJsonArray { add(JsonPrimitive("sign")) }) })
            })
        }
        val body = buildJsonObject {
            put("v", 1); put("iss", issuer.publickey); put("sub", device.publickey)
            put("scope", buildJsonArray { add(JsonPrimitive("vault:sign")) }); put("iat", 1L); put("seq", seq)
        }
        val cert = JsonObject(body + ("sig" to JsonPrimitive(issuer.sign(Canonical.stringify(Delegation.body(body))))))
        val ack = buildJsonObject { put("op", RemoteAgent.ACK); put("sid", "s"); put("publickey", device.publickey) }
        return Triple(acta, cert, ack)
    }

    @Test fun anAgentOfMyRecordIsBelieved() = runBlocking {
        val sealer = TestKeys.fresh(); val agent = TestKeys.fresh()
        val (acta, cert, ack) = world(sealer, agent, sealer, seq = 40, actaSeq = 44)
        assertNull(RemoteAgent.judge(ack, agent.sign(Canonical.stringify(ack)), cert, acta))
    }

    @Test fun andEachWayItIsNotSaysWhy() = runBlocking {
        val sealer = TestKeys.fresh(); val agent = TestKeys.fresh(); val stranger = TestKeys.fresh()
        val (acta, cert, ack) = world(sealer, agent, sealer, seq = 40, actaSeq = 44)
        val sig = agent.sign(Canonical.stringify(ack))
        assertEquals("no-acta", RemoteAgent.judge(ack, sig, cert, null))
        assertEquals("bad-action-signature", RemoteAgent.judge(ack, stranger.sign(Canonical.stringify(ack)), cert, acta))
        assertEquals("cert-device-mismatch", RemoteAgent.judge(ack, sig, null, acta))
        // A paper that names a record newer than mine: I cannot judge it.
        val (acta2, cert2, ack2) = world(sealer, agent, sealer, seq = 50, actaSeq = 44)
        assertEquals("acta-vieja", RemoteAgent.judge(ack2, agent.sign(Canonical.stringify(ack2)), cert2, acta2))
        // A well-signed paper from someone who does not seal this profile.
        val (acta3, cert3, ack3) = world(sealer, agent, stranger, seq = 40, actaSeq = 44)
        assertEquals("untrusted-issuer", RemoteAgent.judge(ack3, agent.sign(Canonical.stringify(ack3)), cert3, acta3))
    }

    // The tabs of a phone share one connection: an error that names ANOTHER session is not mine.
    @Test fun anErrorIsForTheSessionItNames() {
        val mine = buildJsonObject { put("type", RemoteAgent.ERROR); put("code", "unknown-session"); put("sid", "a"); put("error", "sesión desconocida o expirada") }
        val e = RemoteAgent.sessionError(mine, "a")!!
        assertEquals("unknown-session", e.code)
        assertNull(RemoteAgent.sessionError(mine, "b"))
        // An agent older than remote-agent 0.14.0 sends neither code nor sid: still mine, as before.
        val old = buildJsonObject { put("type", RemoteAgent.ERROR); put("error", "sesión desconocida o expirada") }
        assertEquals("agent-error", RemoteAgent.sessionError(old, "a")!!.code)
    }
}
