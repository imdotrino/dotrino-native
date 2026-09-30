package com.dotrino.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los perfiles del teléfono, leídos del almacén de la identidad como los lista la web. */
class PhoneProfilesTest {
    @Test fun listsThePhonesProfilesLikeTheWeb() {
        val items = mapOf(
            "kv:dotrino.identity.current" to "p2",
            "kv:dotrino.identity.profiles" to """[{"id":"p1","name":"Casa","pubkey":"K1"},{"id":"p2","name":"","pubkey":"K2","login":{"address":"ana@AB12-CD34-EF56"}},{"id":"p3","name":"Sin me","pubkey":"K3"}]""",
            "kv:dotrino.identity.p.p2.me" to """{"nickname":"Ana","avatar":"data:image/png;base64,AAAA"}""",
            "kv:dotrino.identity.p.p1.me" to """{"avatar":"https://no-es-un-data-uri"}""",
        )
        val l = PhoneProfiles.list(items)
        assertEquals(listOf("p1", "p2", "p3"), l.map { it.id })
        assertEquals("Casa", l[0].name); assertEquals(null, l[0].avatar)          // solo data-URI de imagen
        assertEquals("Ana", l[1].name); assertTrue(l[1].current); assertEquals("ana@AB12-CD34-EF56", l[1].login)
        assertEquals("K2", l[1].seed); assertEquals("data:image/png;base64,AAAA", l[1].avatar)
        assertEquals(listOf(false, true, false), l.map { it.current })
        assertTrue(PhoneProfiles.list(emptyMap()).isEmpty())
    }
}
