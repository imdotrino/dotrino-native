package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the vault tells its approvers along with the list of requests (`notices`, vaultd ≥ 0.147.0). */
class VaultNoticeTest {
    private fun body(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun readsTheNoticesOfTheAnswer() {
        val list = VaultNotice.listFrom(body("""{"op":"approvals","items":[],"notices":[{"id":"n1","ev":"updated","version":"0.147.0","from":"0.146.0","ts":1790000000000}]}"""))
        assertEquals(listOf(VaultNotice("n1", "updated", "0.147.0", "0.146.0", 1790000000000)), list)
    }

    @Test fun anOlderVaultSendsNoFieldAndThatIsNoNotices() {
        assertTrue(VaultNotice.listFrom(body("""{"op":"approvals","items":[]}""")).isEmpty())
    }

    @Test fun anEntryWithoutIdOrEventIsDropped() {
        val list = VaultNotice.listFrom(body("""{"notices":[{"ev":"updated"},{"id":"n2"},"x",{"id":"n3","ev":"updated"}]}"""))
        assertEquals(listOf(VaultNotice("n3", "updated", "", "", 0)), list)
    }

    @Test fun aDeviceThatIsNotTheVaultIsNamedByItsLabelOrItsId() {
        val list = VaultNotice.listFrom(body("""{"notices":[
            {"id":"a","ev":"updated","version":"0.30.0","from":"0.29.0","ts":1,"product":"@dotrino/terminal-agent","deviceId":"AB12-CD34","label":"laptop"},
            {"id":"b","ev":"updated","version":"0.30.0","from":"0.29.0","ts":2,"product":"@dotrino/terminal-agent","deviceId":"AB12-CD34"},
            {"id":"c","ev":"updated","version":"0.147.0","from":"0.146.0","ts":3,"product":"@dotrino/vaultd","deviceId":"EF56-7890","label":"vault"},
            {"id":"d","ev":"updated","version":"0.147.0","from":"0.146.0","ts":4}
        ]}"""))
        assertEquals(listOf("laptop", "AB12-CD34", null, null), list.map { it.device })
        assertEquals("@dotrino/terminal-agent", list[0].product)
        assertEquals("AB12-CD34", list[0].deviceId)
    }
}
