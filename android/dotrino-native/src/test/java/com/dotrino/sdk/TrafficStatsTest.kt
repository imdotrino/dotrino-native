package com.dotrino.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The counters of the network stats: bytes by road and per connection, as `stats.js`. */
class TrafficStatsTest {
    @Test fun utf8LengthCountsBytesNotChars() {
        assertEquals(3, utf8Length("abc"))
        assertEquals(2, utf8Length("ñ"))
        assertEquals(3, utf8Length("€"))
        assertEquals(4, utf8Length("😀"))
    }

    @Test fun bytesGoToTheRoadTheyTookAndToTheirConnection() {
        val t = TrafficStats()
        t.frame(incoming = false, bytes = 50)
        t.peer(incoming = false, path = "proxy", token = "A", pubkey = null, bytes = 30)
        t.peer(incoming = false, path = "direct", token = "A", pubkey = null, bytes = 10)
        t.peer(incoming = true, path = "turn", token = "A", pubkey = null, bytes = 7)
        t.peer(incoming = false, path = "proxy", token = null, pubkey = "PK", bytes = 5)
        val (proxy, peers) = t.snapshot(routeOf = { if (it == "A") "direct" else null }, pubkeyOf = { if (it == "A") "PKA" else null })
        assertEquals(50L, proxy.bytesOut); assertEquals(1, proxy.framesOut)
        val a = peers.first { it.token == "A" }
        assertEquals("direct", a.route); assertEquals("PKA", a.pubkey)
        assertEquals(30L, a.bytesOut.proxy); assertEquals(10L, a.bytesOut.direct); assertEquals(7L, a.bytesIn.turn)
        assertEquals(2, a.msgsOut); assertEquals(1, a.msgsIn)
        val k = peers.first { it.pubkey == "PK" }
        assertEquals(null, k.token); assertEquals("proxy", k.route); assertEquals(5L, k.bytesOut.total)
    }

    @Test fun theRegistryTellsWhenATransportComesAndGoes() {
        var changes = 0
        val off = DotrinoNetwork.onChange { changes++ }
        val s = DotrinoNetwork.Source { throw IllegalStateException("not called") }
        DotrinoNetwork.register(s); DotrinoNetwork.register(s)
        assertTrue(s in DotrinoNetwork.sources())
        DotrinoNetwork.unregister(s)
        off()
        assertEquals(2, changes)
    }
}
