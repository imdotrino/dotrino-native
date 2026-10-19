package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone READS AND SENDS BINARY with the real pillar, through a real proxy: a file sealed in
 * binary by token, another by pubkey (the queue road); and the phone's answers — sealed by token,
 * sealed by pubkey, and an unsealed package like the vault's — reach the JS as binary frames.
 *
 *   node test-vectors/e2e-binary.mjs /tmp/bin.json
 *   DOTRINO_E2E_BIN=/tmp/bin.json ./gradlew :dotrino-native:testDebugUnitTest --tests '*BinaryE2eTest*'
 */
class BinaryE2eTest {
    @Test fun aJsClientSendsFilesInBinaryAndThePhoneOpensThem() = runBlocking {
        val path = System.getenv("DOTRINO_E2E_BIN")
        assumeTrue("DOTRINO_E2E_BIN not set: start test-vectors/e2e-binary.mjs to run this", path != null && File(path).exists())
        val f = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val profile = Profile.of(TestKeys.fromJwk(f.getValue("sign").jsonObject, f.getValue("enc").jsonObject))
        val s = SealedSession(listOf(f.getValue("proxyUrl").jsonPrimitive.content), profile, "messenger")
        val got = CopyOnWriteArrayList<SealedSession.Message>()
        val two = CompletableDeferred<Unit>()
        s.onMessage { got.add(it); if (got.size >= 2) two.complete(Unit) }
        try {
            s.start()
            assert(s.awaitOnline()) { "phone offline: ${s.status}" }
            val jsToken = f.getValue("jsToken").jsonPrimitive.content
            // The greeting says `speaks: ['binary']`; that is what lets the JS send bytes.
            val who = s.whoIs(jsToken)
            assert(Delegation.samePubkey(who, f.getValue("jsPubkey").jsonPrimitive.content)) { "the greeting did not say who" }

            val sentFile = File("$path.sent")
            withTimeout(30_000) { while (!sentFile.exists()) delay(100) }
            val sent = Json.parseToJsonElement(sentFile.readText()).jsonObject
            assertNull("the JS could not send: ${sent["error"]}", sent["error"])
            withTimeout(30_000) { two.await() }

            val dm = got.first { it.payload["type"]?.jsonPrimitive?.content == "DM" }
            assertEquals(sent.getValue("file").jsonPrimitive.content,
                dm.payload.getValue("file").jsonObject.getValue("data").jsonPrimitive.content)
            assertEquals("foto.bin", dm.payload.getValue("file").jsonObject.getValue("name").jsonPrimitive.content)
            val q = got.first { it.payload["type"]?.jsonPrimitive?.content == "QUEUED" }
            assertEquals(sent.getValue("blob").jsonPrimitive.content, q.payload.getValue("blob").jsonPrimitive.content)
            assert(Delegation.samePubkey(q.fromPubkey, f.getValue("jsPubkey").jsonPrimitive.content))

            // And the phone answers, in binary: sealed by token, sealed by pubkey, and an unsealed
            // package by pubkey to the `vault` app (what VaultClient sends).
            val jsPub = f.getValue("jsPubkey").jsonPrimitive.content
            s.sendSealedTo(jsToken, buildJsonObject { put("type", "ACK"); put("n", 2) })
            s.sendSealed(jsPub, buildJsonObject { put("type", "ACK_Q") })
            val bare = ProxyConnection(f.getValue("proxyUrl").jsonPrimitive.content, "vault")
            try {
                bare.connect()
                bare.identifyAs(profile.publickey) { profile.signData(it) }
                bare.sendByPubkey(jsPub, buildJsonObject { put("type", "PLAIN"); put("ok", true) }, toApp = "vault")
                val reply = File("$path.reply")
                var r = buildJsonObject {}
                withTimeout(15_000) {
                    while (true) {
                        if (reply.exists()) r = Json.parseToJsonElement(reply.readText()).jsonObject
                        if (r.keys.containsAll(listOf("ACK", "ACK_Q", "PLAIN"))) break
                        delay(100)
                    }
                }
                for ((type, sealed) in listOf("ACK" to "true", "ACK_Q" to "true", "PLAIN" to "false")) {
                    val one = r.getValue(type).jsonObject
                    assertEquals("$type sealed", sealed, one.getValue("sealed").jsonPrimitive.content)
                    assertEquals("$type arrived as a binary frame", "true", one.getValue("binary").jsonPrimitive.content)
                }
                assertEquals("true", r.getValue("PLAIN").jsonObject.getValue("msg").jsonObject.getValue("ok").jsonPrimitive.content)
            } finally { bare.close() }
        } finally {
            s.close()
            File(path).delete()
        }
    }
}
