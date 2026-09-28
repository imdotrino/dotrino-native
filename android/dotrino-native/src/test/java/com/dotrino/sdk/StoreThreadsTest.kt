package com.dotrino.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreThreadsTest {
    private fun entry(id: String, v: String) = JsonObject(mapOf("id" to JsonPrimitive(id), "v" to JsonPrimitive(v)))

    @Test fun appendReplacesBySameIdInPlace() {
        val t = StoreThreads.empty()
        t.append("a", entry("1", "x"))
        t.append("a", entry("2", "y"))
        t.append("a", entry("1", "z"))
        assertEquals(listOf("z", "y"), t.list("a").map { it["v"]!!.jsonPrimitive.content })
    }

    @Test fun roundTripsThroughText() {
        val t = StoreThreads.empty()
        t.append("a", entry("1", "x"))
        t.append("b", entry("2", "y"))
        val back = StoreThreads.decode(t.encode())
        assertEquals(t.encode(), back.encode())
    }

    @Test fun removeSaysWhetherSomethingWent() {
        val t = StoreThreads.empty()
        t.append("a", entry("1", "x"))
        assertFalse(t.remove("a", "nope"))
        assertTrue(t.remove("a", "1"))
        assertEquals(0, t.list("a").size)
    }

    @Test fun badShapeIsAnErrorNotEmpty() {
        assertThrows(IllegalStateException::class.java) { StoreThreads.decode("[]") }
        assertThrows(IllegalStateException::class.java) { StoreThreads.decode("""{"order":["a"],"threads":{"a":{}}}""") }
        assertThrows(IllegalArgumentException::class.java) { StoreThreads.decode("""{"order":["a"],"threads":{"a":[{"v":1}]}}""") }
    }

    @Test fun copyIsIndependent() {
        val t = StoreThreads.empty()
        t.append("a", entry("1", "x"))
        val c = t.copy()
        c.append("a", entry("2", "y"))
        assertEquals(1, t.list("a").size)
    }
}
