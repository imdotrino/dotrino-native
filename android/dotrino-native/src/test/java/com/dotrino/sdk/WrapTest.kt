package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** `wrapForMember` / `openWrap`: what one device wraps, only the member it was wrapped to opens. */
class WrapTest {
    @Test fun aWrapOpensWithTheMembersKeyAndNoOther() = runBlocking<Unit> {
        val member = TestKeys.fresh(); val other = TestKeys.fresh()
        val cek = Crypto.makeContentKey()
        val wrap = Crypto.wrapForMember(cek, member.encPub)
        assertEquals(cek, Crypto.openWrap(wrap, member))
        assertThrows(Exception::class.java) { runBlocking { Crypto.openWrap(wrap, other) } }
    }

    @Test fun theProfileOpensWhatWasSealedToIt() = runBlocking<Unit> {
        val keys = TestKeys.fresh()
        val cek = Crypto.makeContentKey()
        val envelope = Crypto.encryptWithCek(cek, 3, "hunter2")
        assertEquals("hunter2", Profile.of(keys).openSealed(Crypto.wrapForMember(cek, keys.encPub), envelope))
    }
}
