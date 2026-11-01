package com.dotrino.sdk

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LARGE FILES ENCRYPTED IN SEGMENTS: the port of `lib/segments.js` of `@dotrino/content-client`.
 * Each 1 MiB segment is closed on its own with AES-GCM, so a 1 GB recording never has to fit in
 * memory and any point can be read without decrypting what comes before.
 *
 * The key belongs to ONE file, so the iv can be the segment number:
 *   iv = [ last ? 1 : 0, 0, 0, 0, segment number in 8 big-endian bytes ]
 * That stops anyone reordering segments or cutting the file. Golden vector in the tests, the same
 * as `dotrino-content/test/vectors/segments.json`.
 */
object SegmentCipher {
    const val SEGMENT_BYTES = 1024 * 1024

    internal fun ivFor(index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(if (last) 1 else 0).put(ByteArray(3)).putLong(index).array()

    internal fun seal(key: ByteArray, index: Long, last: Boolean, plain: ByteArray, length: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ivFor(index, last)))
        return c.doFinal(plain, 0, length)
    }

    /** Encrypts [src] into [dst] and returns the `cid` (SHA-256 of what was written). */
    fun encryptFile(src: File, dst: File, key: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val total = src.length()
        FileInputStream(src).use { input ->
            dst.outputStream().buffered().use { out ->
                val buf = ByteArray(SEGMENT_BYTES)
                var index = 0L
                var done = 0L
                do {
                    var n = 0
                    while (n < SEGMENT_BYTES) {
                        val r = input.read(buf, n, SEGMENT_BYTES - n)
                        if (r < 0) break
                        n += r
                    }
                    done += n
                    val cipher = seal(key, index, done >= total, buf, n)
                    hash.update(cipher); out.write(cipher)
                    index++
                } while (done < total)
            }
        }
        return "sha256-" + hash.digest().joinToString("") { "%02x".format(it) }
    }
}
