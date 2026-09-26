package com.dotrino.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * EL CLIENTE DE LA APP DE IDENTIDAD (docs/DISENO.md §2.2). En Android una llave del Keystore es
 * de UNA app, así que las llaves, el almacén de la identidad y las cuentas viven en
 * `com.dotrino.identity`, y el resto de apps de Dotrino se lo piden aquí. Así un teléfono es UN
 * aparato del acta, se empareje desde la app que se empareje.
 *
 * El protocolo es el MISMO que el puente del WebView (`create`, `open`, `sign`, `deriveBits`,
 * `save`, `remove`, `storeLoad`, `storeSet`, `storeRemove`) más `accounts` y `accountSave`:
 * `{ id, method, params }` → `{ id, result }` o `{ id, error, code }`, en JSON, por un Messenger.
 * El servicio está protegido con un permiso de nivel FIRMA: solo lo usan apps firmadas igual.
 *
 * Se enlaza una vez y se queda enlazado hasta [close]: una firma es una llamada, no un arranque.
 */
class IdentityClient(context: Context) {
    companion object {
        const val PACKAGE = "com.dotrino.identity"
        const val SERVICE = "com.dotrino.identity.IdentityService"
        const val PERMISSION = "com.dotrino.permission.IDENTITY"
        const val MSG_CALL = 1
        const val KEY = "json"
        /** The identity app is not on this phone: the caller says so and offers to install it. */
        const val MISSING = "identity-missing"
        /** The identity app went away mid-call (updated, killed): the call can be retried. */
        const val GONE = "identity-gone"
        private val json = Json { ignoreUnknownKeys = true }

        fun isInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(PACKAGE, 0); true
        } catch (_: Exception) { false }

        /** Where to install it: Google Play (memory: Android apps go only through Play). */
        val installUri: Uri = Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE")
    }

    class IdentityError(message: String, val code: String) : Exception(message)

    private val app = context.applicationContext
    private val thread = HandlerThread("dotrino-identity-client").apply { start() }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicInteger(1)
    private val replies = Messenger(Handler(thread.looper) { msg ->
        val o = msg.data?.getString(KEY)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val id = o?.get("id")?.jsonPrimitive?.content
        if (o != null && id != null) pending.remove(id)?.complete(o)
        true
    })
    @Volatile private var service: Messenger? = null
    @Volatile private var bound: CompletableDeferred<Messenger>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val m = Messenger(binder)
            service = m
            bound?.complete(m)
        }
        override fun onServiceDisconnected(name: ComponentName) = gone("the identity app disconnected")
        override fun onBindingDied(name: ComponentName) = gone("the identity app went away")
        override fun onNullBinding(name: ComponentName) = gone("the identity app refused the connection")
    }

    private fun gone(why: String) {
        service = null
        val e = IdentityError(why, GONE)
        bound?.completeExceptionally(e); bound = null
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
    }

    private suspend fun messenger(): Messenger {
        service?.let { return it }
        val d = synchronized(this) {
            bound ?: CompletableDeferred<Messenger>().also { nd ->
                bound = nd
                val intent = Intent().setComponent(ComponentName(PACKAGE, SERVICE))
                val ok = try { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) } catch (e: SecurityException) {
                    nd.completeExceptionally(IdentityError("this app is not allowed to use the identity app (signature)", "identity-forbidden")); bound = null; return@also
                }
                if (!ok) {
                    app.unbindService(connection)
                    nd.completeExceptionally(IdentityError("the Dotrino identity app is not installed", MISSING)); bound = null
                }
            }
        }
        return try { withTimeout(10_000) { d.await() } } catch (e: TimeoutCancellationException) {
            throw IdentityError("the identity app did not answer the connection", "identity-no-reply")
        }
    }

    /** The whole answer `{ id, result }` / `{ id, error, code }`, as the WebView bridge forwards it. */
    suspend fun raw(method: String, params: JsonObject, timeoutMs: Long = 20_000): JsonObject {
        val m = messenger()
        val id = "c" + nextId.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        try {
            val req = JsonObject(mapOf("id" to JsonPrimitive(id), "method" to JsonPrimitive(method), "params" to params))
            m.send(Message.obtain(null, MSG_CALL).apply { data = Bundle().apply { putString(KEY, req.toString()) }; replyTo = replies })
            return withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            throw IdentityError("the identity app did not answer $method", "identity-no-reply")
        } finally { pending.remove(id) }
    }

    /** The `result`, or the error the identity app gave, with its code. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val o = raw(method, params)
        o["error"]?.let { throw IdentityError(it.jsonPrimitive.content, o["code"]?.jsonPrimitive?.content ?: "identity-error") }
        return o["result"] as? JsonObject ?: throw IdentityError("the identity app answered $method without a result", "identity-no-result")
    }

    fun close() {
        if (service != null || bound != null) runCatching { app.unbindService(connection) }
        gone("closed")
        thread.quitSafely()
    }
}
