package com.dotrino.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El identicon es el MISMO que el de la web: estos valores salen de `avatarSvg` de
 * `@dotrino/identity/avatar` (tono y casillas llenas "col,fila") para cada semilla.
 */
class AvatarTest {
    private val vectors = listOf(
        Triple("dotrino", 298, "0,0 0,1 0,2 0,3 0,4 1,0 1,2 1,3 3,0 3,2 3,3 4,0 4,1 4,2 4,3 4,4"),
        Triple("{\"crv\":\"P-256\",\"ext\":true,\"key_ops\":[\"verify\"],\"kty\":\"EC\",\"x\":\"abc\",\"y\":\"déf\"}", 136, "0,1 0,2 0,3 1,1 2,0 2,3 3,1 4,1 4,2 4,3"),
        Triple("ñandú 🐦", 72, "0,0 0,1 0,2 0,4 1,0 3,0 4,0 4,1 4,2 4,4"),
    )

    @Test fun theIdenticonIsTheWebOne() {
        for ((seed, hue, cells) in vectors) {
            val p = DotrinoAvatar.pattern(seed)
            assertEquals(seed, hue, p.hue)
            val got = (0 until 5).flatMap { c -> (0 until 5).filter { r -> p.cells[c][r] }.map { r -> "$c,$r" } }.sorted().joinToString(" ")
            assertEquals(seed, cells, got)
        }
    }

    @Test fun hslMatchesCss() {
        assertEquals(0xFFFF0000.toInt(), DotrinoAvatar.hsl(0, 1f, .5f))
        assertEquals(0xFF808080.toInt(), DotrinoAvatar.hsl(200, 0f, .5f))
    }
}
