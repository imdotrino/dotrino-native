package com.dotrino.sdk.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.Base64
import android.view.View
import com.dotrino.sdk.DotrinoAvatar

/**
 * El avatar del perfil, redondo: la FOTO que subió la persona (`me.avatar`) o, sin foto, el
 * identicon de [seed] — el mismo que dibuja `avatarSvg` en la web (moneda con degradado y la
 * rejilla 5×5 al 76 %, con un margen del 12 %).
 */
class DotrinoAvatarView(context: Context, private val seed: String?, photo: String? = null) : View(context) {
    private val pattern = DotrinoAvatar.pattern(seed)
    private val bitmap: Bitmap? = photo?.let(::decode)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun decode(uri: String): Bitmap? = runCatching {
        if (!uri.startsWith("data:image/") || !uri.contains(";base64,")) return null
        val bytes = Base64.decode(uri.substringAfter(";base64,"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()   // un SVG o algo que no se puede leer: se queda el identicon

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val r = size / 2
        val b = bitmap
        if (b != null) {
            val scale = size / minOf(b.width, b.height)
            val m = Matrix().apply { setScale(scale, scale); postTranslate((size - b.width * scale) / 2, (size - b.height * scale) / 2) }
            paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            canvas.drawCircle(r, r, r, paint)
            paint.shader = null
            return
        }
        val hue = pattern.hue
        paint.shader = LinearGradient(0f, 0f, size, size,
            DotrinoAvatar.hsl(hue, .48f, .95f), DotrinoAvatar.hsl((hue + 40) % 360, .48f, .90f), Shader.TileMode.CLAMP)
        canvas.drawCircle(r, r, r, paint)
        paint.shader = null
        paint.color = DotrinoAvatar.hsl(hue, .62f, .46f)
        val unit = size * 0.76f / 5
        val off = size * 0.12f
        for (col in 0 until 5) for (row in 0 until 5) if (pattern.cells[col][row]) {
            canvas.drawRect(off + col * unit, off + row * unit, off + (col + 1) * unit, off + (row + 1) * unit, paint)
        }
    }
}
