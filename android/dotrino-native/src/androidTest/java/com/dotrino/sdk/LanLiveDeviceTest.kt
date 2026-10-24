package com.dotrino.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE LOCAL NETWORK, live (dotrino-proxy-client/docs/lan.md), against another machine on the same
 * network. Skipped without it: `-e LAN_EXPECT <data the other announces>` discovers it, and
 * `-e LAN_ANNOUNCE <data>` announces it as a `pair` for LAN_HOLD_MS.
 */
@RunWith(AndroidJUnit4::class)
class LanLiveDeviceTest {
    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun discoversWhatTheNetworkAnnounces() = runBlocking {
        val want = args.getString("LAN_EXPECT")
        assumeTrue("needs -e LAN_EXPECT", want != null)
        val found = Nearby(ctx, null).discover(BleWire.KIND_PAIR, 8_000)
        assertTrue("found: $found", want in found)
    }

    @Test fun announcesOnTheNetwork() = runBlocking {
        val text = args.getString("LAN_ANNOUNCE")
        assumeTrue("needs -e LAN_ANNOUNCE", text != null)
        val stop = Nearby(ctx, null).announce(BleWire.KIND_PAIR, text!!)
        delay(args.getString("LAN_HOLD_MS")?.toLongOrNull() ?: 20_000)
        stop()
    }
}
