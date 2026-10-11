package com.dotrino.sdk

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/** What the identity needs from an account's keys, wherever they live. */
interface PhoneKey : DeviceKeys {
    /** Signs raw bytes → P1363 (r‖s) in base64. */
    fun signBytes(bytes: ByteArray): String
}

/**
 * The keys of an account opened with user and password. That key lives in the VAULT, which
 * hands it over at sign-in: it cannot be put in the chip, so it is kept here in a [SealedFile]
 * (AES-GCM under a key of the Android Keystore) and opened only to sign or to agree.
 *
 * The special case, and the only one: every other key of the phone is born in the chip
 * ([KeystoreKeys]) and never leaves it.
 */
class ImportedKeys private constructor(
    override val publickey: String, override val encPub: String,
    private val signKey: PrivateKey, private val encKey: PrivateKey,
) : PhoneKey {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private fun name(id: String) = "dotrino-imported-$id.bin"
        private fun alias(id: String) = "dotrino.$id.wrap"
        private fun sealed(context: Context, id: String) = SealedFile(context, name(id), alias(id))

        fun exists(context: Context, id: String): Boolean = File(context.filesDir, name(id)).exists()

        /**
         * Keeps the pair for [id]. [sign] and [enc] are the private JWKs; [publickey] and [encPub]
         * are the public halves EXACTLY as the account record writes them. Refused unless each
         * private key really is the pair of its public one.
         */
        fun import(context: Context, id: String, sign: JsonObject, enc: JsonObject, publickey: String, encPub: String): ImportedKeys {
            check(!exists(context, id) && !KeystoreKeys.exists(id)) { "keys for $id already exist" }
            val k = build(sign, enc, publickey, encPub)
            sealed(context, id).write(buildJsonObject {
                put("sign", sign); put("enc", enc); put("publickey", publickey); put("encPub", encPub)
            }.toString().toByteArray(Charsets.UTF_8))
            return k
        }

        /** The existing pair for [id]; fails loudly if it is not there or does not open. */
        fun open(context: Context, id: String): ImportedKeys {
            val raw = sealed(context, id).read() ?: throw IllegalStateException("no imported keys for $id on this device")
            val o = json.parseToJsonElement(String(raw, Charsets.UTF_8)) as JsonObject
            return build(o.getValue("sign") as JsonObject, o.getValue("enc") as JsonObject,
                o.getValue("publickey").jsonPrimitive.content, o.getValue("encPub").jsonPrimitive.content)
        }

        fun delete(context: Context, id: String) {
            File(context.filesDir, name(id)).delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(id))
        }

        private fun d(jwk: JsonObject): String {
            require(jwk["crv"]?.jsonPrimitive?.content == "P-256") { "jwk: only P-256 keys" }
            return jwk["d"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("jwk: not a private key")
        }

        private fun build(sign: JsonObject, enc: JsonObject, publickey: String, encPub: String): ImportedKeys {
            val s = Crypto.privateKeyOf(d(sign)); val e = Crypto.privateKeyOf(d(enc))
            // The private half must be the pair of the public one the record names: a signature
            // it makes has to verify, and an agreement has to match from both sides.
            val probe = "dotrino-imported-key".toByteArray(Charsets.UTF_8)
            val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(s); update(probe) }.sign()
            val ok = Signature.getInstance("SHA256withECDSA").apply { initVerify(Crypto.publicKeyOf(publickey)); update(probe) }.verify(sig)
            require(ok) { "the signing key is not the pair of its public key" }
            val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val mine = agree(e, eph.public); val theirs = agree(eph.private, Crypto.publicKeyOf(encPub))
            require(mine.contentEquals(theirs)) { "the encryption key is not the pair of its public key" }
            return ImportedKeys(publickey, encPub, s, e)
        }

        private fun agree(priv: PrivateKey, peer: PublicKey): ByteArray =
            KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(peer, true) }.generateSecret()
    }

    override suspend fun sign(text: String): String = signBytes(text.toByteArray(Charsets.UTF_8))

    override fun signBytes(bytes: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(signKey)
        s.update(bytes)
        return Crypto.b64(Crypto.derToP1363(s.sign()))
    }

    override suspend fun agree(peer: PublicKey): ByteArray = Companion.agree(encKey, peer)
}

/** An account's keys by id: the chip's, or the ones a vault handed over at sign-in. */
object PhoneKeys {
    fun exists(context: Context, id: String): Boolean = KeystoreKeys.exists(id) || ImportedKeys.exists(context, id)

    fun open(context: Context, id: String): PhoneKey =
        if (ImportedKeys.exists(context, id)) ImportedKeys.open(context, id) else KeystoreKeys.open(id)

    fun delete(context: Context, id: String) {
        KeystoreKeys.delete(id)
        ImportedKeys.delete(context, id)
    }
}
