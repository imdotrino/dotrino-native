package com.dotrino.sdk

/**
 * EL AVATAR del perfil: el identicon de `@dotrino/identity/avatar`, el mismo dibujo que enseña la
 * web para la misma semilla (normalmente la llave del perfil). Es determinista: si cada app
 * llevara su copia, el mismo usuario vería un avatar distinto según dónde lo mire — por eso
 * vive aquí, una vez, y se comprueba contra lo que dibuja la web (vectores en los tests).
 *
 * Esto es solo el patrón (rejilla 5×5 simétrica y el tono); lo pinta `ui.DotrinoAvatarView`.
 */
object DotrinoAvatar {
    /** [hue] 0..359 y las casillas llenas, `cells[col][row]`. */
    class Pattern(val hue: Int, val cells: Array<BooleanArray>)

    /** FNV-1a + xorshift sobre las unidades UTF-16, con la aritmética de 32 bits de JS. */
    fun pattern(seed: String?): Pattern {
        val s = seed?.takeIf { it.isNotEmpty() } ?: "dotrino"
        var h = 2166136261u.toInt()
        for (c in s) { h = h xor c.code; h *= 16777619 }
        var x = h xor 0x9e3779b9u.toInt()
        val bytes = IntArray(16)
        for (i in 0 until 16) { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); bytes[i] = x and 0xff }
        val cells = Array(5) { BooleanArray(5) }
        for (col in 0 until 3) for (row in 0 until 5) {
            if (bytes[col * 5 + row] and 1 == 0) continue
            if (col == 2) cells[2][row] = true else { cells[col][row] = true; cells[4 - col][row] = true }
        }
        return Pattern((h.toUInt() % 360u).toInt(), cells)
    }

    /** `hsl()` de CSS a ARGB. */
    fun hsl(h: Int, s: Float, l: Float): Int {
        val c = (1 - Math.abs(2 * l - 1)) * s
        val hp = h / 60f
        val x = c * (1 - Math.abs(hp % 2 - 1))
        val (r, g, b) = when {
            hp < 1 -> Triple(c, x, 0f); hp < 2 -> Triple(x, c, 0f); hp < 3 -> Triple(0f, c, x)
            hp < 4 -> Triple(0f, x, c); hp < 5 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = l - c / 2
        fun ch(v: Float) = Math.round((v + m) * 255).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
