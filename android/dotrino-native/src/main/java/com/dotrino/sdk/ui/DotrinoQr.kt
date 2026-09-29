package com.dotrino.sdk.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * THE ECOSYSTEM'S QR in native (`@dotrino/qr`: `<dotrino-qr>` shows, `<dotrino-qr-scan>`
 * reads). Everything on the device: what goes in a QR is usually the user's (a pairing
 * link), so it never goes to an image API. [QrScanView] reads with the camera; [decode]
 * reads a photo, for when there is no camera or no permission.
 */
object DotrinoQr {
    /** The QR of [text] as pixels (ARGB, row by row) of [size]×[size], quiet zone included. */
    fun pixels(text: String, size: Int, fg: Int = Color.BLACK, bg: Int = Color.WHITE): IntArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ))
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m[x, y]) fg else bg
        return px
    }

    /** The QR of [text] as a bitmap of [size]×[size] px. */
    fun bitmap(text: String, size: Int, fg: Int = Color.BLACK, bg: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(pixels(text, size, fg, bg), size, size, Bitmap.Config.ARGB_8888)

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** Reads a QR from a luminance source (a camera frame). Null when there is none. */
    fun decode(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: Exception) { null }

    /** Reads a QR from a photo. Null when there is none in it. */
    fun decode(photo: Bitmap): String? {
        val w = photo.width; val h = photo.height
        val px = IntArray(w * h); photo.getPixels(px, 0, w, 0, 0, w, h)
        return decode(w, h, px)
    }

    /** Reads a QR from ARGB pixels. Null when there is none. */
    fun decode(width: Int, height: Int, argb: IntArray): String? = decode(RGBLuminanceSource(width, height, argb))
}
