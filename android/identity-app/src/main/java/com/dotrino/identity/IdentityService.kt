package com.dotrino.identity

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.dotrino.sdk.IdentityClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * El servicio que usan las demás apps de Dotrino (`IdentityClient`). Protegido en el manifiesto
 * con un permiso de nivel FIRMA: solo se enlazan las apps firmadas con la misma llave.
 *
 * Cada petición se atiende en su propia corrutina (una firma no espera a otra) y la respuesta
 * vuelve al `replyTo` de quien preguntó, con su `id`.
 */
class IdentityService : Service() {
    companion object { private const val TAG = "dotrino-identity" }

    private val thread = HandlerThread("dotrino-identity-service").apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var host: IdentityHost
    private val joiner = IdentityClient.Joiner()   // only touched on [thread]

    private val messenger by lazy {
        Messenger(Handler(thread.looper) { msg ->
            if (msg.what != IdentityClient.MSG_CALL) return@Handler false
            // The Message is recycled when this returns: keep what is needed now.
            val replyTo = msg.replyTo ?: return@Handler true
            // A large request arrives in pieces: it is answered when the last one is in.
            val text = joiner.feed(msg.data) ?: return@Handler true
            scope.launch { answer(text, replyTo) }
            true
        })
    }

    override fun onCreate() { super.onCreate(); host = IdentityHost(this) }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private suspend fun answer(text: String, replyTo: Messenger) {
        val req = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        val id = req["id"]?.jsonPrimitive?.content ?: return
        val method = req["method"]?.jsonPrimitive?.content
        val out = try {
            buildJsonObject { put("id", id); put("result", host.call(method, req["params"] as? JsonObject ?: JsonObject(emptyMap()))) }
        } catch (e: Exception) {
            Log.w(TAG, "$method: ${e.message}")
            buildJsonObject {
                put("id", id); put("error", e.message ?: e.javaClass.simpleName)
                put("code", (e as? IdentityHost.HostError)?.code ?: "native-error")
            }
        }
        // IN PIECES: Android cannot pass more than ~1 MB between apps in one go (the identity's
        // store with a profile photo and a few profiles passed it: FAILED BINDER TRANSACTION, and
        // the pages were left without a profile). `IdentityClient` puts the pieces back together.
        val parts = IdentityClient.split(out.toString())
        runCatching {
            parts.forEachIndexed { i, part ->
                replyTo.send(Message.obtain(null, IdentityClient.MSG_CALL).apply { data = IdentityClient.partBundle(id, i, parts.size, part) })
            }
        }.onFailure { Log.w(TAG, "could not answer $method: ${it.message}") }
    }

    override fun onDestroy() { scope.cancel(); thread.quitSafely(); super.onDestroy() }
}
