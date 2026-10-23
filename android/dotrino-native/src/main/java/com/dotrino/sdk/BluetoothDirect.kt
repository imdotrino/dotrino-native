package com.dotrino.sdk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * THE BLUETOOTH STEP of the transport (`dotrino-proxy-client/docs/bluetooth.md`): after direct
 * WebRTC, before TURN. The `BluetoothManager` of the JS pilar over the Android stack, with the same
 * wire ([BleWire]). Plug it in through [DirectLadder] next to WebRTC, or alone.
 *
 * Everything runs on ONE handler thread. The app asks for BLUETOOTH_SCAN, BLUETOOTH_CONNECT and
 * BLUETOOTH_ADVERTISE, and calls [start] once granted; without them, or with Bluetooth off,
 * [available] is false and the step is simply not there.
 */
@SuppressLint("MissingPermission")
class BluetoothDirect(private val context: Context) : DirectTransport {
    private val thread = HandlerThread("dotrino-ble").apply { start() }
    private val h = Handler(thread.looper)
    /** Arrivals go to the app from HERE, in order: answering from the BLE thread would block it. */
    private val out = Executors.newSingleThreadExecutor()

    private val uService = UUID.fromString(BleWire.SERVICE_UUID)
    private val uHello = UUID.fromString(BleWire.HELLO_UUID)
    private val uBeacon = UUID.fromString(BleWire.BEACON_UUID)
    private val uRx = UUID.fromString(BleWire.RX_UUID)
    private val uTx = UUID.fromString(BleWire.TX_UUID)
    private val uCcc = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var selfToken: () -> String? = { null }
    private var signalSend: (String, JsonObject) -> Unit = { _, _ -> }
    private var deliverText: (String, String) -> Unit = { _, _ -> }
    private var deliverBin: (String, ByteArray) -> Unit = { _, _ -> }

    /** Who may make me negotiate (null = anyone who can reach me, like the JS default). */
    @Volatile var acceptFrom: ((String) -> Boolean)? = null
    @Volatile var onOpen: (String) -> Unit = {}
    @Volatile var onClose: (String) -> Unit = {}

    private var manager: BluetoothManager? = null
    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    private var bid = BleWire.randomBid()
    private var beacon = ByteArray(0)

    private enum class State { NEW, OFFERED, KEYED, OPEN, FAILED, CLOSED }
    private enum class Role { CENTRAL, PERIPHERAL }

    private inner class Peer(val token: String) {
        var state = State.NEW
        var role: Role? = null
        var eph: PrivateKey? = null
        var key: ByteArray? = null
        var theirBid: String? = null
        var myBid: String? = null
        var tag = ByteArray(0)
        var link: Link? = null
        var central: BluetoothDevice? = null
        var chunk = 20
        var sendCtr = 0L
        var recvCtr = 0L
        val rx = BleWire.Reassembler(BleWire.TAG_BYTES)
        var timer: Runnable? = null
        var failedAt = 0L
    }

    private inner class CentralState(val device: BluetoothDevice) {
        val rx = BleWire.Reassembler()
        var peer: Peer? = null
        var pendingHello: ByteArray? = null
        var mtu = 23
    }

    private val peers = HashMap<String, Peer>()
    private val centrals = HashMap<String, CentralState>()   // peripheral role, by address
    private val wanted = HashMap<String, Peer>()             // central role: bid → peer
    private val links = HashMap<String, Link>()              // central role, by address
    private val seen = HashMap<String, Pair<String?, Long>>()
    private val probing = HashSet<String>()
    private val probeQueue = ArrayDeque<BluetoothDevice>()
    private var probeBusy = false
    private val sessions = HashMap<Any, Session>()
    private var scanning = false
    private val notifyQueue = ArrayDeque<Pair<BluetoothDevice, ByteArray>>()
    private var notifying = false

    private class Session(val kind: String, val onFound: ((String) -> Unit)?) {
        val done = HashSet<String>()
        val found = ArrayList<String>()
    }

    /** A connection we made (central role). Android runs ONE GATT operation at a time: [ops] queues them. */
    private inner class Link(val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        val chars = HashMap<UUID, BluetoothGattCharacteristic>()
        var onReady: ((Boolean) -> Unit)? = null
        val ops = ArrayDeque<() -> Boolean>()
        var busy = false
        var onRead: ((ByteArray?) -> Unit)? = null
        var onWrote: (() -> Unit)? = null
        var onValue: ((ByteArray) -> Unit)? = null
        var onNotify: ((Boolean) -> Unit)? = null
        var onClose: (() -> Unit)? = null
        var closed = false
        var mtu = 23
        var owner: Peer? = null

        fun enqueue(op: () -> Boolean) { ops.addLast(op); pump() }
        fun pump() {
            while (!busy && !closed && ops.isNotEmpty()) {
                busy = ops.removeFirst()()
            }
        }
        fun done() { busy = false; pump() }
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Starts serving and advertising. Call after the three Bluetooth permissions are granted. */
    fun start() = h.post {
        if (server != null) return@post
        if (!granted("android.permission.BLUETOOTH_CONNECT") || !granted("android.permission.BLUETOOTH_ADVERTISE") || !granted("android.permission.BLUETOOTH_SCAN")) return@post
        val m = context.getSystemService(BluetoothManager::class.java) ?: return@post
        if (m.adapter?.isEnabled != true) return@post
        manager = m
        val s = m.openGattServer(context, serverCb) ?: return@post
        server = s
        val svc = BluetoothGattService(uService, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(BluetoothGattCharacteristic(uHello, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        svc.addCharacteristic(BluetoothGattCharacteristic(uBeacon, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        svc.addCharacteristic(BluetoothGattCharacteristic(uRx,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        val tx = BluetoothGattCharacteristic(uTx, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        tx.addDescriptor(BluetoothGattDescriptor(uCcc, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(tx)
        txChar = tx
        s.addService(svc)
        h.postDelayed(object : Runnable { override fun run() { bid = BleWire.randomBid(); h.postDelayed(this, BleWire.BID_ROTATE_MS) } }, BleWire.BID_ROTATE_MS)
    }

    /** Bluetooth is on, allowed, and serving. */
    val available: Boolean get() = ready

    private fun <T> onThread(block: () -> T): T {
        if (android.os.Looper.myLooper() == thread.looper) return block()
        var r: Result<T>? = null
        val latch = CountDownLatch(1)
        h.post { r = runCatching(block); latch.countDown() }
        latch.await()
        return r!!.getOrThrow()
    }

    // ---- DirectTransport ----

    override fun bind(selfToken: () -> String?, signalSend: (String, JsonObject) -> Unit, deliver: (String, String) -> Unit, deliverBinary: (String, ByteArray) -> Unit) {
        this.selfToken = selfToken; this.signalSend = signalSend; this.deliverText = deliver; this.deliverBin = deliverBinary
    }

    override fun setIceServers(servers: List<DirectTransport.IceServer>) {}

    override fun isOpen(token: String): Boolean = onThread { peers[token]?.state == State.OPEN }

    override fun route(token: String): String? = onThread {
        when (peers[token]?.state) {
            null, State.CLOSED -> null
            State.OPEN -> "bluetooth"
            State.FAILED -> "failed"
            else -> "connecting"
        }
    }

    override fun sendBinary(token: String, bytes: ByteArray): Boolean = onThread {
        val p = peers[token]
        if (p == null || p.state != State.OPEN) false else { send(p, BleWire.TYPE_BINARY, bytes); true }
    }

    override fun upgrade(token: String) {
        h.post {
            if (!ready || selfToken() == null) return@post
            peers[token]?.let { if (it.state != State.FAILED || System.currentTimeMillis() - it.failedAt < 60_000) return@post }
            val p = Peer(token)
            peers[token] = p
            val eph = BleWire.makeEphemeral()
            p.eph = eph.privateKey
            p.state = State.OFFERED
            p.myBid = bid
            timer(p, BleWire.ANSWER_TIMEOUT_MS)
            signalSend(token, signal("offer", bid, eph.epub))
        }
    }

    private fun signal(kind: String, bid: String, epub: String) = JsonObject(mapOf(
        "t" to JsonPrimitive(BleWire.TAG), "kind" to JsonPrimitive(kind), "bid" to JsonPrimitive(bid), "epub" to JsonPrimitive(epub)))

    override fun handleSignal(from: String, msg: JsonObject) {
        acceptFrom?.let { if (!it(from)) return }
        fun str(k: String) = (msg[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = str("kind") ?: return
        val theirBid = str("bid") ?: return
        val epub = str("epub") ?: return
        h.post {
            if (!ready) return@post
            var p = peers[from]
            when (kind) {
                "offer" -> if (p == null || p.state != State.OFFERED) {
                    p?.let { release(it); it.state = State.CLOSED }
                    val np = Peer(from)
                    peers[from] = np
                    val eph = BleWire.makeEphemeral()
                    np.eph = eph.privateKey
                    np.myBid = bid
                    signalSend(from, signal("answer", bid, eph.epub))
                    p = np
                } // crossed offers: theirs works as the answer (ECDH is symmetric)
                "answer" -> if (p == null || p.state != State.OFFERED) return@post
                else -> return@post
            }
            val peer = p!!
            val me = selfToken()
            val eph = peer.eph
            val myBid = peer.myBid
            val key = if (me != null && eph != null) runCatching { BleWire.deriveKey(eph, epub, me, from) }.getOrNull() else null
            if (me == null || key == null || myBid == null) { fail(peer); return@post }
            peer.key = key
            peer.eph = null
            peer.theirBid = theirBid
            peer.role = if (me > from) Role.CENTRAL else Role.PERIPHERAL
            peer.tag = BleWire.tagOf(if (peer.role == Role.CENTRAL) myBid else theirBid)
            peer.state = State.KEYED
            timer(peer, BleWire.FIND_TIMEOUT_MS)
            if (peer.role == Role.PERIPHERAL) retryHellos() else { wanted[theirBid] = peer; updateScan() }
        }
    }

    override fun close(token: String) { h.post { peers[token]?.let { closed(it) } } }
    override fun closeAll() { h.post { peers.values.toList().forEach { closed(it) } } }

    // ---- announce and discover ----

    /** Puts `{kind, data}` in `beacon` (docs §3) until the returned function is called. Throws `no-bluetooth` without Bluetooth. */
    fun announce(kind: String, data: String): () -> Unit {
        val b = BleWire.encodeBeacon(kind, data)
        onThread {
            if (!ready) throw BleWire.WireException("no-bluetooth", "bluetooth: not available here")
            beacon = b
        }
        return { h.post { if (beacon === b) beacon = ByteArray(0) } }
    }

    /** What devices around announce with [kind], for [timeoutMs]. Needs no proxy. */
    suspend fun discover(kind: String, timeoutMs: Long = 8000, onFound: ((String) -> Unit)? = null): List<String> {
        val id = Any()
        onThread {
            if (!ready) throw BleWire.WireException("no-bluetooth", "bluetooth: not available here")
            sessions[id] = Session(kind, onFound)
            updateScan()
        }
        delay(timeoutMs)
        return onThread {
            val s = sessions.remove(id)
            updateScan()
            s?.found?.toList() ?: emptyList()
        }
    }

    // ---- central role ----

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { h.post { found(result.device) } }
    }

    private fun updateScan() {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        val want = ready && (wanted.isNotEmpty() || sessions.isNotEmpty())
        try {
            if (want && !scanning) {
                scanning = true
                scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(uService)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
            } else if (!want && scanning) {
                scanning = false
                scanner.stopScan(scanCb)
            }
        } catch (_: SecurityException) { scanning = false }
    }

    private fun found(d: BluetoothDevice) {
        val id = d.address
        if (id in probing || links.containsKey(id)) return
        val needsSession = sessions.values.any { id !in it.done }
        var needsPeer = wanted.isNotEmpty()
        seen[id]?.let { (b, at) -> if (System.currentTimeMillis() - at < 60_000 && (b == null || !wanted.containsKey(b))) needsPeer = false }
        if (!needsSession && !needsPeer) return
        probing.add(id)
        probeQueue.addLast(d)
        pumpProbes()
    }

    /** One connection attempt at a time: read `hello` and `beacon`, keep the link if it is a peer we look for. */
    private fun pumpProbes() {
        if (probeBusy || probeQueue.isEmpty()) return
        probeBusy = true
        val d = probeQueue.removeFirst()
        val link = Link(d)
        links[d.address] = link
        val finish: (Boolean) -> Unit = { keep ->
            probing.remove(d.address)
            if (!keep) dropLink(link)
            probeBusy = false
            pumpProbes()
        }
        val deadline = Runnable { link.onReady?.invoke(false) }
        h.postDelayed(deadline, 15_000)
        link.onReady = ready@{ ok ->
            h.removeCallbacks(deadline)
            link.onReady = null
            if (!ok) { finish(false); return@ready }
            read(link, uHello) { helloData ->
                read(link, uBeacon) { beaconData ->
                    val id = d.address
                    val theirBid = helloData?.let(BleWire::decodeHello)
                    seen[id] = theirBid to System.currentTimeMillis()
                    beaconData?.let(BleWire::decodeBeacon)?.let { (k, v) ->
                        for (s in sessions.values) if (id !in s.done && s.kind == k && v !in s.found) { s.found.add(v); s.onFound?.invoke(v) }
                    }
                    sessions.values.forEach { it.done.add(id) }
                    val peer = theirBid?.let { wanted[it] }
                    if (peer != null && peer.state == State.KEYED) {
                        wanted.remove(theirBid)
                        updateScan()
                        attach(peer, link)
                        finish(true)
                    } else finish(false)
                }
            }
        }
        link.gatt = try { d.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE, 0, h) } catch (_: SecurityException) { null }
        if (link.gatt == null) link.onReady?.invoke(false)
    }

    @Suppress("DEPRECATION")
    private fun read(link: Link, uuid: UUID, done: (ByteArray?) -> Unit) {
        val c = link.chars[uuid]
        if (c == null || link.closed) { done(null); return }
        link.enqueue {
            link.onRead = { v -> link.onRead = null; done(v); link.done() }
            link.gatt?.readCharacteristic(c) == true || run { link.onRead = null; done(null); false }
        }
    }

    private fun attach(peer: Peer, link: Link) {
        peer.link = link
        link.owner = peer
        peer.chunk = minOf(link.mtu - 3, 512)
        link.onClose = { closed(peer) }
        link.onValue = { chunk -> if (BleWire.hasTag(chunk, peer.tag)) onChunk(peer, chunk) }
        link.onNotify = { ok -> if (ok) send(peer, BleWire.TYPE_HELLO, (selfToken() ?: "").toByteArray(Charsets.UTF_8)) else closed(peer) }
        val tx = link.chars[uTx]
        if (tx == null) { closed(peer); return }
        link.enqueue {
            val g = link.gatt ?: return@enqueue false
            g.setCharacteristicNotification(tx, true)
            val ccc = tx.getDescriptor(uCcc) ?: run { link.onNotify?.invoke(false); return@enqueue false }
            writeDescriptor(g, ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        else { d.value = v; g.writeDescriptor(d) }

    @Suppress("DEPRECATION")
    private fun writeChunk(link: Link, chunk: ByteArray) {
        val rx = link.chars[uRx] ?: return
        link.enqueue {
            val g = link.gatt ?: return@enqueue false
            link.onWrote = { link.onWrote = null; link.done() }
            val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            else { rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; rx.value = chunk; g.writeCharacteristic(rx) }
            if (!ok) { link.onWrote = null; link.owner?.let { closed(it) } }
            ok
        }
    }

    private fun onChunk(peer: Peer, chunk: ByteArray) {
        try { peer.rx.push(chunk)?.let { onRecord(peer, it) } } catch (_: Exception) { closed(peer) }
    }

    private fun dropLink(link: Link) {
        if (link.closed) return
        link.closed = true
        links.remove(link.device.address)
        try { link.gatt?.disconnect(); link.gatt?.close() } catch (_: SecurityException) {}
        val cb = link.onClose
        link.onClose = null
        cb?.invoke()
    }

    private val gattCb = object : BluetoothGattCallback() {
        private fun link(g: BluetoothGatt) = links[g.device.address]

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val l = link(g) ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(517)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                l.onReady?.let { it(false); return }
                val pending = l.onRead
                dropLink(l)
                pending?.invoke(null)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            link(g)?.let { if (status == BluetoothGatt.GATT_SUCCESS) it.mtu = mtu }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val l = link(g) ?: return
            val svc = g.getService(uService)
            if (status != BluetoothGatt.GATT_SUCCESS || svc == null) { l.onReady?.invoke(false); return }
            for (c in svc.characteristics) l.chars[c.uuid] = c
            l.onReady?.invoke(l.chars.containsKey(uHello) && l.chars.containsKey(uRx) && l.chars.containsKey(uTx))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            link(g)?.onRead?.invoke(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) link(g)?.onRead?.invoke(if (status == BluetoothGatt.GATT_SUCCESS) c.value ?: ByteArray(0) else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val l = link(g) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) { l.owner?.let { closed(it) }; return }
            l.onWrote?.invoke()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val l = link(g) ?: return
            val cb = l.onNotify
            l.onNotify = null
            l.done()
            cb?.invoke(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == uTx) link(g)?.onValue?.invoke(value)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == uTx) c.value?.let { v -> link(g)?.onValue?.invoke(v) }
        }
    }

    // ---- peripheral role ----

    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { h.post { ready = true } }
        override fun onStartFailure(errorCode: Int) { h.post { ready = false } }
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            h.post {
                if (status != BluetoothGatt.GATT_SUCCESS) return@post
                val adv = manager?.adapter?.bluetoothLeAdvertiser ?: return@post
                try {
                    adv.startAdvertising(
                        AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(),
                        AdvertiseData.Builder().addServiceUuid(ParcelUuid(uService)).setIncludeDeviceName(false).build(),
                        advCb)
                } catch (_: SecurityException) {}
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) h.post {
                centrals.remove(device.address)?.peer?.let { closed(it) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            h.post { centrals.getOrPut(device.address) { CentralState(device) }.mtu = mtu }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            h.post {
                val all = when (c.uuid) { uHello -> BleWire.encodeHello(bid); uBeacon -> beacon; else -> ByteArray(0) }
                if (offset > all.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, all.copyOfRange(offset, all.size))
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            h.post {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (c.uuid == uRx) onWrite(device, value)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            h.post {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                centrals.getOrPut(device.address) { CentralState(device) }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) { h.post { notifying = false; pumpNotify() } }
    }

    private fun onWrite(device: BluetoothDevice, bytes: ByteArray) {
        val st = centrals.getOrPut(device.address) { CentralState(device) }
        val rec = try { st.rx.push(bytes) } catch (_: Exception) { centrals.remove(device.address); st.peer?.let { closed(it) }; return } ?: return
        val p = st.peer
        if (p != null) onRecord(p, rec) else identify(st, rec)
    }

    /** Who connected: the key that opens their greeting says which negotiation it is. */
    private fun identify(st: CentralState, rec: ByteArray) {
        for (p in peers.values) {
            if (p.role != Role.PERIPHERAL || p.state != State.KEYED || p.central != null) continue
            val key = p.key ?: continue
            val r = runCatching { BleWire.openRecord(key, BleWire.DIR_TO_PERIPHERAL, 0, rec) }.getOrNull() ?: continue
            if (r.first != BleWire.TYPE_HELLO || String(r.second, Charsets.UTF_8) != p.token) continue
            p.central = st.device
            p.chunk = minOf(st.mtu - 3, 512)
            p.recvCtr = 1
            st.peer = p
            st.pendingHello = null
            send(p, BleWire.TYPE_HELLO, (selfToken() ?: "").toByteArray(Charsets.UTF_8))
            open(p)
            return
        }
        // Nobody expected it YET: the central can write before our key is ready. Kept for later.
        st.pendingHello = rec
    }

    private fun retryHellos() {
        for (st in centrals.values.toList()) if (st.peer == null) st.pendingHello?.let { identify(st, it) }
    }

    @Suppress("DEPRECATION")
    private fun pumpNotify() {
        val s = server ?: return
        val tx = txChar ?: return
        if (notifying) return
        val (device, chunk) = notifyQueue.removeFirstOrNull() ?: return
        notifying = true
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(device, tx, false, chunk) == BluetoothGatt.GATT_SUCCESS
            else { tx.value = chunk; s.notifyCharacteristicChanged(device, tx, false) }
        } catch (_: SecurityException) { false }
        if (!ok) { notifying = false; centrals[device.address]?.peer?.let { closed(it) } }
    }

    // ---- records ----

    private fun onRecord(p: Peer, rec: ByteArray) {
        if (p.state == State.CLOSED || p.state == State.FAILED) return
        val key = p.key ?: return
        val dir = if (p.role == Role.CENTRAL) BleWire.DIR_TO_CENTRAL else BleWire.DIR_TO_PERIPHERAL
        val (type, body) = runCatching { BleWire.openRecord(key, dir, p.recvCtr, rec) }.getOrNull() ?: run { closed(p); return }
        p.recvCtr++
        when (type) {
            BleWire.TYPE_HELLO -> {
                if (String(body, Charsets.UTF_8) != p.token) { closed(p); return }
                if (p.role == Role.CENTRAL) open(p)
            }
            BleWire.TYPE_TEXT -> {
                if (p.state != State.OPEN) { closed(p); return }
                val t = p.token; val text = String(body, Charsets.UTF_8); val f = deliverText
                out.execute { f(t, text) }
            }
            BleWire.TYPE_BINARY -> {
                if (p.state != State.OPEN) { closed(p); return }
                val t = p.token; val f = deliverBin
                out.execute { f(t, body) }
            }
        }
    }

    private fun send(p: Peer, type: Byte, body: ByteArray) {
        val key = p.key ?: return
        val dir = if (p.role == Role.CENTRAL) BleWire.DIR_TO_PERIPHERAL else BleWire.DIR_TO_CENTRAL
        val rec = BleWire.sealRecord(key, dir, p.sendCtr++, type, body)
        val link = p.link
        val c = p.central
        if (p.role == Role.CENTRAL && link != null) BleWire.toChunks(rec, p.chunk).forEach { writeChunk(link, it) }
        else if (c != null) { BleWire.toChunks(rec, p.chunk, p.tag).forEach { notifyQueue.addLast(c to it) }; pumpNotify() }
    }

    // ---- state ----

    private fun timer(p: Peer, ms: Long) {
        p.timer?.let { h.removeCallbacks(it) }
        val r = Runnable { if (p.state != State.OPEN) fail(p) }
        p.timer = r
        h.postDelayed(r, ms)
    }

    private fun open(p: Peer) {
        p.timer?.let { h.removeCallbacks(it) }; p.timer = null
        p.state = State.OPEN
        val t = p.token
        out.execute { onOpen(t) }
    }

    private fun fail(p: Peer) {
        if (p.state == State.CLOSED) return
        release(p)
        p.state = State.FAILED
        p.failedAt = System.currentTimeMillis()
    }

    private fun closed(p: Peer) {
        if (p.state == State.CLOSED) return
        val wasOpen = p.state == State.OPEN
        release(p)
        p.state = State.CLOSED
        if (peers[p.token] === p) peers.remove(p.token)
        if (wasOpen) { val t = p.token; out.execute { onClose(t) } }
    }

    private fun release(p: Peer) {
        p.timer?.let { h.removeCallbacks(it) }; p.timer = null
        p.theirBid?.let { if (wanted[it] === p) { wanted.remove(it); updateScan() } }
        p.link?.let { l -> p.link = null; l.owner = null; l.onClose = null; dropLink(l) }
        p.central?.let { c ->
            p.central = null
            centrals[c.address]?.let { if (it.peer === p) it.peer = null }
            notifyQueue.removeAll { it.first.address == c.address }
            try { server?.cancelConnection(c) } catch (_: SecurityException) {}
        }
    }
}
