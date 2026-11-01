package com.dotrino.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SegmentCipherTest {
    /** The same vector as `dotrino-content/test/vectors/segments.json`: both ends write the same bytes. */
    @Test fun goldenVector() {
        val key = ByteArray(32) { it.toByte() }
        val src = File.createTempFile("seg", ".plain"); val dst = File.createTempFile("seg", ".enc")
        try {
            src.writeBytes(ByteArray(2097159) { 0x61 })
            val cid = SegmentCipher.encryptFile(src, dst, key)
            assertEquals(2097207L, dst.length())
            assertEquals("sha256-e1aa4cfcb3d123b2cc08e134f90c4cce0f263db9d7c2fac3d1e230b14afec68e", cid)
        } finally { src.delete(); dst.delete() }
    }

    @Test fun ivSaysWhichAndWhetherLast() {
        assertEquals(listOf<Byte>(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2), SegmentCipher.ivFor(258, true).toList())
    }

    @Test fun wholeSegmentsDoNotLeaveAnEmptyTail() {
        val src = File.createTempFile("seg", ".plain"); val dst = File.createTempFile("seg", ".enc")
        try {
            src.writeBytes(ByteArray(SegmentCipher.SEGMENT_BYTES))
            SegmentCipher.encryptFile(src, dst, ByteArray(32))
            assertEquals(SegmentCipher.SEGMENT_BYTES + 16L, dst.length())
        } finally { src.delete(); dst.delete() }
    }

    @Test fun whoseNetwork() {
        val dotrino = "wss://proxy.dotrino.com"; val own = "wss://proxy.mi-casa.ec"
        assertFalse(ContentNodeClient.isDotrinoNetwork("wss://notdotrino.com"))
        assertEquals(ContentNodeClient.Path.YES, ContentNodeClient.bytesAllowed("direct", dotrino))
        assertEquals(ContentNodeClient.Path.NO, ContentNodeClient.bytesAllowed("turn", dotrino))
        assertEquals(ContentNodeClient.Path.NO, ContentNodeClient.bytesAllowed("proxy", dotrino))
        assertEquals(ContentNodeClient.Path.ASK, ContentNodeClient.bytesAllowed("turn", own))
        assertEquals(ContentNodeClient.Path.ASK, ContentNodeClient.bytesAllowed("proxy", own))
    }
}
