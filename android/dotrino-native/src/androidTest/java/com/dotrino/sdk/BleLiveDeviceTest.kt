package com.dotrino.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * EL BLUETOOTH EN VIVO, contra otro aparato de verdad cerca (dotrino-proxy-client/docs/bluetooth.md).
 * Se salta sin con quién: `-e BLE_EXPECT <invitación que el otro anuncia>` y `-e BLE_PEER <su token
 * en el proxio>`. Con `-e BLE_ANNOUNCE <texto>` es esta tablet la que anuncia, durante BLE_HOLD_MS.
 * Con `-e BLE_CONTACT <apodo que el otro anuncia>`, «Cerca» del messenger: las dos tarjetas de contacto
 * se ven a la vez (esta anuncia la suya y busca en rondas, como la pestaña).
 */
@RunWith(AndroidJUnit4::class)
class BleLiveDeviceTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(
        "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE")

    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun ready(): BluetoothDirect {
        val bt = BluetoothDirect(ctx)
        bt.start()
        repeat(150) { if (!bt.available) delay(100) }
        assertTrue("Bluetooth is off, not allowed, or cannot advertise", bt.available)
        return bt
    }

    @Test fun discoversTheInvitationAnnouncedNearby() = runBlocking {
        val want = args.getString("BLE_EXPECT")
        assumeTrue("needs -e BLE_EXPECT", want != null)
        val bt = ready()
        try {
            val found = bt.discover("pair", 20_000)
            assertTrue("found: $found", want in found)
        } finally { bt.stop() }
    }

    @Test fun contactCardsSeeEachOther() = runBlocking {
        val want = args.getString("BLE_CONTACT")
        assumeTrue("needs -e BLE_CONTACT", want != null)
        val bt = ready()
        try {
            val me = """{"crv":"P-256","kty":"EC","x":"WKn-ZIGevcwGIyyrzFoZNBdaq9_TsqzGl96oc0CWuis","y":"y77t-RvAHRKTsSGdIYUfweuOvwrvDD-Q3Hv5J0fSKbE"}"""
            val stop = bt.announce(BleWire.KIND_CONTACT, BleWire.encodeContact(me, "Tablet de prueba ñ"))
            val seen = mutableSetOf<String>()
            // Rondas seguidas, como la pestaña: buscar una vez y otra no puede fallar.
            repeat(4) { bt.discover(BleWire.KIND_CONTACT, 6_000).mapNotNull(BleWire::decodeContact).forEach { seen.add(it.nickname) } }
            stop()
            assertTrue("seen: $seen", want in seen)
        } finally { bt.stop() }
    }

    @Test fun announcesForAWhile() = runBlocking {
        val text = args.getString("BLE_ANNOUNCE")
        assumeTrue("needs -e BLE_ANNOUNCE", text != null)
        val bt = ready()
        try {
            val stop = bt.announce("pair", text!!)
            delay(args.getString("BLE_HOLD_MS")?.toLongOrNull() ?: 60_000)
            stop()
        } finally { bt.stop() }
    }

    @Test fun channelGoesOverBluetooth() = runBlocking {
        val peer = args.getString("BLE_PEER")
        assumeTrue("needs -e BLE_PEER", peer != null)
        val bt = ready()
        val conn = ProxyConnection("wss://proxy.dotrino.com")
        try {
            conn.connect()
            conn.useDirect(DirectLadder(null, bt))
            val got = Collections.synchronizedList(mutableListOf<JsonObject>())
            conn.onMessage { got.add(it.payload) }
            conn.sendToOrUpgrade(peer!!, JsonObject(mapOf("n" to JsonPrimitive(1))))
            repeat(300) { if (!bt.isOpen(peer)) delay(100) }
            assertTrue("the Bluetooth channel did not open", bt.isOpen(peer))
            assertEquals("bluetooth", conn.routeOf(peer))
            conn.sendToOrUpgrade(peer, JsonObject(mapOf("n" to JsonPrimitive(2))))
            var echo: JsonObject? = null
            repeat(100) { if (echo == null) { echo = synchronized(got) { got.firstOrNull { it["echo"]?.jsonPrimitive?.intOrNull == 2 } }; if (echo == null) delay(100) } }
            assertEquals("the other side said the second message came by: $echo", "bluetooth", echo?.get("via")?.jsonPrimitive?.content)
        } finally { conn.close(); bt.stop() }
    }
}
