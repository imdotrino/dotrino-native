package com.dotrino.sdk

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * NEARBY: announce and discover `{kind, data}` by every medium at once, the Bluetooth `beacon`
 * ([BluetoothDirect]) and the local network ([LanDiscovery]). The same as `announceNearby` /
 * `discoverNearby` in `@dotrino/proxy-client`. What is seen by both comes out once.
 * `kind` is [BleWire.KIND_PAIR] (the vault invitation) or [BleWire.KIND_CONTACT] (the contact card).
 */
class Nearby(context: Context, private val bluetooth: BluetoothDirect?) {
    private val lan = LanDiscovery(context)

    /** Announces until the returned function is called. Bluetooth only if it is on; the network always. */
    fun announce(kind: String, data: String): () -> Unit {
        val stops = mutableListOf<() -> Unit>()
        if (bluetooth?.available == true) try { stops.add(bluetooth.announce(kind, data)) } catch (_: BleWire.WireException) {}
        stops.add(lan.announce(kind, data))
        return { stops.forEach { try { it() } catch (_: Exception) {} } }
    }

    /** What is announced nearby with [kind], for [timeoutMs], by both media at once. */
    suspend fun discover(kind: String, timeoutMs: Long = 8000, onFound: ((String) -> Unit)? = null): List<String> {
        val found = mutableListOf<String>()
        val add = { d: String ->
            val fresh = synchronized(found) { if (d in found) false else { found.add(d); true } }
            if (fresh) try { onFound?.invoke(d) } catch (_: Exception) {}
        }
        coroutineScope {
            listOfNotNull(
                bluetooth?.takeIf { it.available }?.let { bt ->
                    async { try { bt.discover(kind, timeoutMs) { add(it) } } catch (_: BleWire.WireException) { emptyList() } }
                },
                async { lan.discover(kind, timeoutMs) { add(it) } },
            ).awaitAll()
        }
        return synchronized(found) { found.toList() }
    }
}
