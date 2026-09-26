package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * Against a REAL proxy and vault (`test-vectors/e2e-vault.mjs`). Runs only when
 * `DOTRINO_E2E` points at the file the harness writes; otherwise it is skipped and says so.
 *
 *   node test-vectors/e2e-vault.mjs /tmp/e2e.json &
 *   DOTRINO_E2E=/tmp/e2e.json ./gradlew :dotrino-native:testDebugUnitTest
 */
class VaultE2eTest {
    private class SoftKeys(sign: JsonObject, enc: JsonObject) : DeviceKeys {
        private fun pubOf(j: JsonObject) = """{"kty":"EC","crv":"P-256","x":"${j["x"]!!.jsonPrimitive.content}","y":"${j["y"]!!.jsonPrimitive.content}"}"""
        private fun priv(j: JsonObject): PrivateKey {
            val d = BigInteger(1, Base64.getUrlDecoder().decode(j["d"]!!.jsonPrimitive.content))
            return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, (Crypto.publicKeyOf(pubOf(j)) as ECPublicKey).params))
        }
        private val s = priv(sign); private val e = priv(enc)
        // The JS key's JWK carries extra fields (`ext`, `key_ops`): the vault knows it by THAT string.
        override val publickey = pubOf(sign)
        override val encPub = pubOf(enc)
        override suspend fun sign(text: String): String {
            val g = Signature.getInstance("SHA256withECDSA"); g.initSign(s); g.update(text.toByteArray(Charsets.UTF_8))
            return Crypto.b64(Crypto.derToP1363(g.sign()))
        }
        override suspend fun agree(peer: PublicKey): ByteArray { val k = KeyAgreement.getInstance("ECDH"); k.init(e); k.doPhase(peer, true); return k.generateSecret() }
    }

    @Test fun approvesAPendingWriteOnARealVault() = runBlocking {
        val path = System.getenv("DOTRINO_E2E")
        assumeTrue("DOTRINO_E2E not set: start test-vectors/e2e-vault.mjs to run this", path != null && File(path).exists())
        val f = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val keys = SoftKeys(f["privateJwk"]!!.jsonObject, f["encPrivateJwk"]!!.jsonObject)
        val cert = f["cert"]!!.jsonObject
        // The vault names this device by the pubkey string it enrolled with.
        val enrolledPub = cert["sub"]!!.jsonPrimitive.content
        val k = object : DeviceKeys by keys { override val publickey = enrolledPub }

        val account = Account(id = "e2e", name = "E2E", vault = f["vault"]!!.jsonPrimitive.content,
            proxy = f["proxyUrl"]!!.jsonPrimitive.content, cert = cert, deviceId = f["deviceId"]!!.jsonPrimitive.content)
        assertTrue("the enrolled paper does not carry approve yet", "vault:approve" !in Delegation.scope(cert))
        // The account's NAME comes back from the enrolment (identity ≥ 0.102.1): it is what tells accounts apart.
        assertEquals("Cuenta E2E", f["account"]?.jsonPrimitive?.content)

        val conn = ProxyConnection(account.proxy)
        conn.connect()
        conn.identify(k)
        var renewed: Account? = null
        val vc = VaultClient(account, k, conn) { renewed = it }

        val list = vc.approvals()
        assertTrue("it renewed its paper to get approve", renewed != null && "vault:approve" in Delegation.scope(renewed!!.cert))
        val p = list.single { it.id == f["pending"]!!.jsonPrimitive.content }
        assertEquals("write", p.kind)
        assertEquals(f["ns"]!!.jsonPrimitive.content, p.ns)
        assertEquals("API_TOKEN", (p.ctx!!["keys"] as JsonArray).single().jsonPrimitive.content)

        vc.approve(p.id)
        val written = File("$path.written")
        val deadline = System.currentTimeMillis() + 5000
        while (!written.exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("the vault wrote the approved variable", written.exists())
        assertTrue(vc.approvals().none { it.id == p.id })
        conn.close()
    }

    /**
     * A phone the owner has not given `+aprueba` gets a paper without `vault:approve`. That is
     * not a broken paper: it must come out as NO_APPROVE, which the screen turns into «this
     * phone does not approve in this account yet: dotrino-vault caps <ID> +aprueba» — not as
     * «invalid renewed paper: scope», which told the owner nothing (2026-09-25).
     */
    @Test fun aPhoneWithoutApproveIsToldSoNotInvalidPaper() = runBlocking {
        val path = System.getenv("DOTRINO_E2E")
        assumeTrue("DOTRINO_E2E not set: start test-vectors/e2e-vault.mjs to run this", path != null && File(path).exists())
        val f = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        val n = f["noApprove"]!!.jsonObject
        val keys = SoftKeys(n["privateJwk"]!!.jsonObject, n["encPrivateJwk"]!!.jsonObject)
        val cert = n["cert"]!!.jsonObject
        val k = object : DeviceKeys by keys { override val publickey = cert["sub"]!!.jsonPrimitive.content }
        val account = Account(id = "e2e-2", name = "E2E", vault = f["vault"]!!.jsonPrimitive.content,
            proxy = f["proxyUrl"]!!.jsonPrimitive.content, cert = cert, deviceId = n["deviceId"]!!.jsonPrimitive.content)
        val conn = ProxyConnection(account.proxy)
        conn.connect()
        conn.identify(k)
        val vc = VaultClient(account, k, conn) { }
        val e = try { vc.approvals(); null } catch (x: VaultError) { x }
        assertTrue("it must fail: this phone cannot approve", e != null)
        assertEquals(VaultClient.NO_APPROVE, e!!.code)
        conn.close()
    }
}
