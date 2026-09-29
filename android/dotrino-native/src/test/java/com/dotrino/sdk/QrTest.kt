package com.dotrino.sdk

import com.dotrino.sdk.ui.DotrinoQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The QR the phone shows, the phone reads (and so does any reader: it is a standard QR). */
class QrTest {
    // Colours as plain ints: android.graphics.Color is a stub in JVM tests.
    private val black = 0xFF000000.toInt(); private val white = 0xFFFFFFFF.toInt()

    @Test fun showsAndReadsTheMessengerPairingLink() {
        val link = "https://messenger.dotrino.com/#add=K7M2Q9"
        val px = DotrinoQr.pixels(link, 300, black, white)
        assertEquals(link, DotrinoQr.decode(300, 300, px))
    }

    @Test fun unicodeSurvives() {
        val t = "ñandú ✓ 🔑"
        assertEquals(t, DotrinoQr.decode(240, 240, DotrinoQr.pixels(t, 240, black, white)))
    }

    @Test fun aBlankImageHasNoQr() {
        assertNull(DotrinoQr.decode(100, 100, IntArray(100 * 100) { white }))
    }
}
