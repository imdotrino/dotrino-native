package com.dotrino.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que no cabe en un mensaje entre apps (~1 MB, UTF-16) va en trozos que se vuelven a juntar. */
class IdentitySplitTest {
    @Test fun aLargeAnswerGoesInPiecesThatJoinBack() {
        val big = "{\"items\":{\"kv:foto\":\"" + "ñ".repeat(IdentityWire.PART * 3 + 17) + "\"}}"
        val parts = IdentityWire.split(big)
        assertEquals(4, parts.size)
        assertTrue(parts.all { it.length <= IdentityWire.PART })
        assertEquals(big, parts.joinToString(""))
        assertEquals(listOf("{}"), IdentityWire.split("{}"))   // lo pequeño va entero
    }
}
