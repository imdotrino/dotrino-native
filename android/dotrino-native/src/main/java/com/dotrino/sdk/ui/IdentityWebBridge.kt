package com.dotrino.sdk.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dotrino.sdk.IdentityClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `window.DotrinoIdentityKeys` — the identity of a WebView (the `id.dotrino.com` iframe) uses the
 * keys AND the storage of the IDENTITY APP (`com.dotrino.identity`): one profile for every page
 * and every Dotrino app on the phone. It only relays: each `{ id, method, params }` goes to the
 * identity app as it came, and its answer comes back as it went.
 *
 * ONLY `https://id.dotrino.com` sees the object (`addWebMessageListener` checks the origin of
 * each frame). ONE piece for every app: the Dotrino app and any native app that opens a profile
 * page (CONVENCIONES §16.2). [onCall]: something the app does after a call (the Dotrino app
 * registers its push token under an account just paired).
 */
object IdentityWebBridge {
    private const val TAG = "dotrino-identity-web"
    const val ORIGIN = "https://id.dotrino.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** False when this WebView cannot restrict the bridge by origin: then there is no bridge. */
    fun install(web: WebView, context: Context, onCall: (suspend (method: String, params: JsonObject, reply: JsonObject) -> Unit)? = null): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "this WebView cannot restrict a bridge by origin: no identity bridge"); return false
        }
        val ctx = context.applicationContext
        WebViewCompat.addWebMessageListener(web, "DotrinoIdentityKeys", setOf(ORIGIN)) { _, message, origin, _, reply ->
            // Belt and braces: the rule already filters, but a wrong origin must never get an answer.
            if (origin.toString() != ORIGIN) return@addWebMessageListener
            val req = try { json.parseToJsonElement(message.data ?: "").jsonObject } catch (_: Exception) { return@addWebMessageListener }
            val id = req["id"]?.jsonPrimitive?.content ?: return@addWebMessageListener
            val method = req["method"]?.jsonPrimitive?.content ?: return@addWebMessageListener
            val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
            io.launch {
                val out = try {
                    val r = IdentityClient.shared(ctx).raw(method, params)
                    if (r["error"] == null) onCall?.let { runCatching { it(method, params, r) } }
                    JsonObject(r + ("id" to JsonPrimitive(id)))
                } catch (e: IdentityClient.IdentityError) {
                    Log.w(TAG, "$method: ${e.message}")
                    buildJsonObject { put("id", id); put("error", e.message ?: e.code); put("code", e.code) }
                } catch (e: Exception) {
                    Log.w(TAG, "$method: ${e.message}")
                    buildJsonObject { put("id", id); put("error", e.message ?: e.javaClass.simpleName); put("code", "native-error") }
                }
                // The reply proxy must be used on the thread that created it (the UI thread).
                Handler(Looper.getMainLooper()).post { reply.postMessage(out.toString()) }
            }
        }
        // The storage is the identity app's too (`dotrino-identity/vault/nativeStore.js`).
        WebViewCompat.addDocumentStartJavaScript(web, "if (window.DotrinoIdentityKeys) window.DotrinoIdentityKeys.storage = true", setOf(ORIGIN))
        return true
    }
}
