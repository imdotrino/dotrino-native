package com.dotrino.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The subject of a ring: sealed to this phone, opens here and nowhere else. */
class VaultRingTest {
    private suspend fun sealed(subject: String, to: DeviceKeys): String {
        val cek = Crypto.makeContentKey()
        val wrap = Crypto.wrapForMember(cek, to.encPub)
        val envelope = Crypto.encryptWithCek(cek, 0, subject)
        return JsonObject(mapOf("wrap" to wrap, "envelope" to envelope)).toString()
    }

    @Test fun anApprovalSubjectOpensAsTheRequestItAnnounces() = runBlocking<Unit> {
        val me = TestKeys.fresh(); val other = TestKeys.fresh()
        val s = sealed("""{"ev":"approval","id":"p1","ns":"proxy","kind":"read","deviceId":"904C-1002","label":"proxy1","exp":9,"ctx":null}""", me)
        val subject = VaultRing.open(s, me)!!
        val a = VaultRing.approval(subject)!!
        assertEquals(listOf("p1", "proxy", "read", "904C-1002", "proxy1"), listOf(a.id, a.ns, a.kind, a.deviceId, a.label))
        assertNull(VaultRing.notice(subject))
        assertNull("another account's key does not open it", VaultRing.open(s, other))
        assertNull(VaultRing.open("not json", me))
    }

    @Test fun aDeliveredSubjectOpensAsTheNotice() = runBlocking<Unit> {
        val me = TestKeys.fresh()
        val s = sealed("""{"ev":"delivered","id":"n1","ts":5,"kind":"passwords","ns":"passwords","deviceId":"AB12-CD34","label":"servidor"}""", me)
        val n = VaultRing.notice(VaultRing.open(s, me)!!)!!
        assertEquals(listOf("delivered", "passwords", "passwords", "servidor"), listOf(n.ev, n.kind, n.ns, n.device))
    }
}
