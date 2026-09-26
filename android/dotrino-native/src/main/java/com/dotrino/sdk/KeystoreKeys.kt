package com.dotrino.sdk

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * The account's two keys in the Android Keystore (the phone's secure hardware when it has
 * one). Created non-exportable: no code in this app — nor anything that gets into it — can
 * read the private halves. They are asked to sign or to agree, and that is all.
 *
 * One pair per account, named after it (`dotrino.<id>.sign` / `.enc`), so accounts never
 * share a key: removing one account deletes exactly its keys.
 */
class KeystoreKeys private constructor(private val id: String) : DeviceKeys {
    companion object {
        private const val STORE = "AndroidKeyStore"
        private fun signAlias(id: String) = "dotrino.$id.sign"
        private fun encAlias(id: String) = "dotrino.$id.enc"
        private fun store() = KeyStore.getInstance(STORE).apply { load(null) }

        /** Creates the pair for [id]. Fails if it already exists: overwriting would orphan an enrolled device. */
        fun create(id: String): KeystoreKeys {
            val ks = store()
            check(!ks.containsAlias(signAlias(id)) && !ks.containsAlias(encAlias(id))) { "keys for $id already exist" }
            gen(signAlias(id), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            gen(encAlias(id), KeyProperties.PURPOSE_AGREE_KEY)
            return KeystoreKeys(id)
        }

        /** The existing pair for [id]; fails loudly if it is not there. */
        fun open(id: String): KeystoreKeys {
            val ks = store()
            check(ks.containsAlias(signAlias(id)) && ks.containsAlias(encAlias(id))) { "no keys for $id on this device" }
            return KeystoreKeys(id)
        }

        fun exists(id: String): Boolean = store().let { it.containsAlias(signAlias(id)) && it.containsAlias(encAlias(id)) }

        fun delete(id: String) {
            val ks = store()
            ks.deleteEntry(signAlias(id)); ks.deleteEntry(encAlias(id))
        }

        private fun gen(alias: String, purposes: Int) {
            val spec = KeyGenParameterSpec.Builder(alias, purposes)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply { initialize(spec) }.generateKeyPair()
        }
    }

    private fun priv(alias: String): PrivateKey = store().getKey(alias, null) as PrivateKey
    private fun pub(alias: String): ECPublicKey = store().getCertificate(alias).publicKey as ECPublicKey

    override val publickey: String by lazy { Crypto.jwkOf(pub(signAlias(id))) }
    override val encPub: String by lazy { Crypto.jwkOf(pub(encAlias(id))) }

    override fun sign(text: String): String = signBytes(text.toByteArray(Charsets.UTF_8))

    /** Signs raw bytes (what the identity of the WebView asks for) → P1363 (r‖s) in base64. */
    fun signBytes(bytes: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv(signAlias(id)))
        s.update(bytes)
        return Crypto.b64(Crypto.derToP1363(s.sign()))
    }

    override fun agree(peer: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH", STORE)
        ka.init(priv(encAlias(id)))
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }
}
