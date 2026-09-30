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
import kotlinx.coroutines.sync.withLock
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

        /**
         * THE ANSWER IN PIECES. Android passes at most ~1 MB between apps per message, and a
         * String travels as UTF-16 (two bytes a character): the identity's store with a profile
         * photo passed it and the call failed. An answer longer than [PART] characters goes in
         * several messages, `{ rid, part, parts, json }`, and [raw] puts them back together.
         */
        const val PART = IdentityWire.PART
        const val KEY_RID = "rid"; const val KEY_PART = "part"; const val KEY_PARTS = "parts"
        /**
         * «Send me the next piece». The pieces of an answer are PULLED one at a time: sent all at
         * once they filled Binder's buffer for one-way messages (~512 KB per process), one was
         * lost, and the call ended in `identity-no-reply` — sometimes yes, sometimes no.
         */
        const val MSG_NEXT = 2
        fun split(text: String): List<String> = IdentityWire.split(text)
        fun partBundle(rid: String, part: Int, parts: Int, text: String) = Bundle().apply {
            putString(KEY, text)
            if (parts > 1) { putString(KEY_RID, rid); putInt(KEY_PART, part); putInt(KEY_PARTS, parts) }
        }

        @Volatile private var sharedClient: IdentityClient? = null
        /** ONE connection to the identity app per process (the web bridge, the profile menu…). */
        fun shared(context: Context): IdentityClient = sharedClient ?: synchronized(this) {
            sharedClient ?: IdentityClient(context.applicationContext).also { sharedClient = it }
        }

        fun isInstalled(context: Context): Boolean = com.dotrino.sdk.ui.DotrinoApps.isInstalled(context, PACKAGE)

        /**
         * Where to install it: the Play app itself (`market://`), one tap from the install
         * button. Android apps go only through Play, so there is no other store to send to.
         */
        val installUri: Uri = com.dotrino.sdk.ui.DotrinoApps.storeUri(PACKAGE)
    }

    class IdentityError(message: String, val code: String) : Exception(message)

    /**
     * Puts split messages back together: feed it each Bundle; it gives the whole text when
     * the last piece arrives (or at once, for a message that came whole). Not thread-safe:
     * one per Handler thread.
     */
    class Joiner {
        private val partial = HashMap<String, Array<String?>>()
        fun feed(data: Bundle?): String? {
            val text = data?.getString(KEY) ?: return null
            val rid = data.getString(KEY_RID) ?: return text
            val got = partial.getOrPut(rid) { arrayOfNulls(data.getInt(KEY_PARTS)) }
            data.getInt(KEY_PART).takeIf { it in got.indices }?.let { got[it] = text }
            if (got.any { it == null }) return null
            partial.remove(rid); return got.joinToString("")
        }
        fun drop(rid: String) { partial.remove(rid) }
        /** The next piece to ask for of [rid], or null when it is complete (or unknown). */
        fun next(rid: String): Int? = partial[rid]?.indexOfFirst { it == null }?.takeIf { it >= 0 }
    }

    private val app = context.applicationContext
    private val thread = HandlerThread("dotrino-identity-client").apply { start() }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicInteger(1)
    private val tag = java.util.UUID.randomUUID().toString().take(8)
    // Answers that come split (only touched on [thread]).
    private val joiner = Joiner()
    // Long requests waiting for the identity app to pull their next piece (by request id).
    private val outgoing = ConcurrentHashMap<String, List<String>>()
    private val replies: Messenger = Messenger(Handler(thread.looper) { msg ->
        val data = msg.data
        if (msg.what == MSG_NEXT) {
            // The identity app asks for the next piece of a long request.
            val rid = data?.getString(KEY_RID) ?: return@Handler true
            val part = data.getInt(KEY_PART)
            val parts = outgoing[rid] ?: return@Handler true
            if (part in parts.indices) runCatching { service?.send(Message.obtain(null, MSG_CALL).apply { this.data = partBundle(rid, part, parts.size, parts[part]); replyTo = replies }) }
            if (part == parts.lastIndex) outgoing.remove(rid)
            return@Handler true
        }
        val whole = joiner.feed(data)
        // A piece of a longer answer: ask for the next one (one in flight at a time).
        if (whole == null) data?.getString(KEY_RID)?.let { rid ->
            joiner.next(rid)?.let { part ->
                runCatching { service?.send(Message.obtain(null, MSG_NEXT).apply {
                    this.data = Bundle().apply { putString(KEY_RID, rid); putInt(KEY_PART, part) }; replyTo = replies
                }) }
            }
        }
        val o = whole?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
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
    suspend fun raw(method: String, params: JsonObject, timeoutMs: Long = 20_000): JsonObject = calls.withLock { rawOne(method, params, timeoutMs) }

    // ONE CALL AT A TIME per client: each call in flight has a piece in Binder's buffer, and
    // twenty storeLoads at once of a big store filled it again even pulling the pieces.
    private val calls = kotlinx.coroutines.sync.Mutex()

    private suspend fun rawOne(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val m = messenger()
        // Unique across every client of the phone: the identity keeps a long answer's pieces by this id.
        val id = "c$tag-" + nextId.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        try {
            val req = JsonObject(mapOf("id" to JsonPrimitive(id), "method" to JsonPrimitive(method), "params" to params))
            // A large request (a photo in `storeSet`) goes in pieces too, PULLED one at a time.
            val parts = split(req.toString())
            if (parts.size > 1) outgoing[id] = parts
            m.send(Message.obtain(null, MSG_CALL).apply { data = partBundle(id, 0, parts.size, parts[0]); replyTo = replies })
            return withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            throw IdentityError("the identity app did not answer $method", "identity-no-reply")
        } finally { pending.remove(id); outgoing.remove(id); Handler(thread.looper).post { joiner.drop(id) } }
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

/** The split, without Android: so it is tested on the JVM. */
object IdentityWire {
    const val PART = 32_000
    fun split(text: String): List<String> = if (text.length <= PART) listOf(text) else text.chunked(PART)
}
