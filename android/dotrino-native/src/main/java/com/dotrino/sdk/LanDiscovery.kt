package com.dotrino.sdk

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * THE LOCAL-NETWORK WIRE (`dotrino-proxy-client/docs/lan.md`): `{kind, data}` in the TXT of a
 * `_dotrino._tcp` service, the same strings as `src/lan/mdns.js` (golden vector `lan`). Pure.
 */
object LanWire {
    const val SERVICE_TYPE = "_dotrino._tcp"
    const val FAKE_PORT = 9
    const val MAX_DATA = 512
    private const val CHUNK = 200

    /** The TXT strings, in order: `v=1`, `k=…`, `n=…`, `d0=…`. Chunks never split a character. */
    fun txtEntries(kind: String, data: String): List<Pair<String, ByteArray>> {
        val bytes = data.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_DATA) throw BleWire.WireException("too-big", "lan: at most $MAX_DATA bytes")
        val chunks = mutableListOf<ByteArray>()
        var i = 0
        while (i < bytes.size) {
            var end = minOf(i + CHUNK, bytes.size)
            while (end < bytes.size && (bytes[end].toInt() and 0xc0) == 0x80) end--
            chunks.add(bytes.copyOfRange(i, end))
            i = end
        }
        return listOf("v" to "1".toByteArray(), "k" to kind.toByteArray(), "n" to chunks.size.toString().toByteArray()) +
            chunks.mapIndexed { n, c -> "d$n" to c }
    }

    /** `{kind, data}` back from the TXT attributes, or null when something is missing. */
    fun fromTxt(attrs: Map<String, ByteArray?>): Pair<String, String>? {
        val get = { k: String -> attrs[k]?.toString(Charsets.UTF_8) }
        if (get("v") != "1") return null
        val kind = get("k")?.takeIf { it.isNotEmpty() } ?: return null
        val n = get("n")?.toIntOrNull() ?: return null
        if (n < 0 || n > 3) return null
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until n) out.write(attrs["d$i"] ?: return null)
        return kind to out.toString(Charsets.UTF_8.name())
    }
}

/**
 * ANNOUNCE AND DISCOVER ON THE LOCAL NETWORK, by NSD (mDNS / DNS-SD). Only `{kind, data}`, like
 * the Bluetooth `beacon`; no channel goes here and no port opens (the SRV port is a dummy).
 * [Nearby] uses it together with [BluetoothDirect].
 */
class LanDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    // Before Android 14, NSD resolves one service at a time (FAILURE_ALREADY_ACTIVE otherwise).
    private val resolving = Mutex()

    /** Announces `{kind, data}` until the returned function is called. */
    fun announce(kind: String, data: String): () -> Unit {
        val info = NsdServiceInfo().apply {
            serviceName = "dotrino-" + ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            serviceType = LanWire.SERVICE_TYPE
            port = LanWire.FAKE_PORT
            for ((k, v) in LanWire.txtEntries(kind, data)) setAttribute(k, v.toString(Charsets.UTF_8))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        var done = false
        return { if (!done) { done = true; try { nsd.unregisterService(listener) } catch (_: Exception) {} } }
    }

    /** What devices on the network announce with [kind], for [timeoutMs]; once per `data`. */
    suspend fun discover(kind: String, timeoutMs: Long = 8000, onFound: ((String) -> Unit)? = null): List<String> {
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (!seen.add(i.serviceName)) return
                scope.launch {
                    val r = resolve(i) ?: return@launch
                    val got = LanWire.fromTxt(r.attributes) ?: return@launch
                    if (got.first != kind) return@launch
                    synchronized(found) { if (got.second in found) return@launch; found.add(got.second) }
                    try { onFound?.invoke(got.second) } catch (_: Exception) {}
                }
            }
        }
        nsd.discoverServices(LanWire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try { delay(timeoutMs) } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            scope.cancel()
        }
        return synchronized(found) { found.toList() }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(i: NsdServiceInfo): NsdServiceInfo? = resolving.withLock {
        withTimeoutOrNull(4000) {
            suspendCancellableCoroutine { k ->
                nsd.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, code: Int) { if (k.isActive) k.resume(null) }
                    override fun onServiceResolved(s: NsdServiceInfo) { if (k.isActive) k.resume(s) }
                })
            }
        }
    }
}
