package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approver's side of the log anchor: same cases as `test/log-anchor.test.mjs` of the vault. */
class LogAnchorTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun theApproverSeesTheCut() {
        assertEquals(LogAnchor.FIRST, LogAnchor.compare(null, null, 5, a))
        assertEquals(LogAnchor.ADVANCED, LogAnchor.compare(5, a, 9, b))
        assertEquals(LogAnchor.SAME, LogAnchor.compare(5, a, 5, a))
        assertEquals(LogAnchor.TRUNCATED, LogAnchor.compare(9, b, 5, a))
        assertEquals(LogAnchor.FORKED, LogAnchor.compare(9, b, 9, a))
        assertTrue(LogAnchor.isAlarm(LogAnchor.TRUNCATED))
        assertTrue(LogAnchor.isAlarm(LogAnchor.FORKED))
        assertFalse(LogAnchor.isAlarm(LogAnchor.ADVANCED))
    }

    @Test fun theAnchorIsReadFromTheNoticesAndTheNewestWins() {
        val body = Json.parseToJsonElement("""{"notices":[
            {"id":"u","ev":"updated","version":"0.1.0","from":"0.0.9","ts":1},
            {"id":"x","ev":"log-anchor","logSeq":7,"head":"$a","ts":2,"tsa":{"signature":"s"}},
            {"id":"y","ev":"log-anchor","logSeq":9,"head":"$b","ts":3,"tsa":{"signature":"s"}}
        ]}""").jsonObject
        val last = LogAnchor.latest(VaultNotice.listFrom(body))
        assertEquals(9L, last?.logSeq)
        assertEquals(b, last?.head)
    }
}
