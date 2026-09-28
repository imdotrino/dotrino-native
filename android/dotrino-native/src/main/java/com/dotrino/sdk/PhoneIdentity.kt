package com.dotrino.sdk

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The profile of THIS phone for a native app: it lives in the identity app
 * (`com.dotrino.identity`, docs/DISENO.md §2.2), which keeps the identity's store and the chip
 * keys. Without it installed there is no profile to speak as, and that is said
 * (`no-identity-app`) so the app can offer to install it.
 *
 * Keep it while the profile is in use: the keys are asked through its bound service.
 */
class PhoneIdentity(context: Context) {
    private val app = context.applicationContext
    private var client: IdentityClient? = null

    suspend fun profile(): Profile {
        if (!IdentityClient.isInstalled(app)) {
            throw Profile.ProfileError("the Dotrino identity app is not installed", "no-identity-app")
        }
        val c = client ?: IdentityClient(app).also { client = it }
        val items = (c.call("storeLoad")["items"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        return Profile.load(items) { kid -> RemoteKeys.open(c, kid) }
    }

    fun close() { client?.close(); client = null }

    companion object {
        /**
         * The app's TRANSPORT key: it signs this app's channel entries (what `proxy-client`
         * keeps as its own keypair). It is the app's, not the person's: no profile needed.
         */
        fun transportKey(): DeviceKeys = if (KeystoreKeys.exists("transport")) KeystoreKeys.open("transport") else KeystoreKeys.create("transport")
    }
}
