package com.dotrino.sdk

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the phone runs, in the shape of `@dotrino/identity/machine`. Same as `MachineTests.swift`. */
class MachineTest {
    private fun line(m: kotlinx.serialization.json.JsonObject?) =
        m?.let { listOf("kind", "os", "name").map { k -> it[k]!!.jsonPrimitive.content }.filter { s -> s.isNotEmpty() }.joinToString(" · ") }

    @Test fun androidWithTheModel() {
        assertEquals("android · Android 14 · Google Pixel 7", line(Machine.of("14", "Google", "Pixel 7")))
    }

    @Test fun theMakerIsNotRepeated() {
        assertEquals("android · Android 13 · Samsung SM-S911B", line(Machine.of("13.0.1", "samsung", "SM-S911B")))
        assertEquals("android · Android 15 · OnePlus 12", line(Machine.of("15", "OnePlus", "OnePlus 12")))
    }

    @Test fun controlCharactersAndLengthAreCut() {
        val m = Machine.of("14", "", "a\u0000b" + "x".repeat(200))
        assertEquals(64, m!!["name"]!!.jsonPrimitive.content.length)
        assertEquals("ab", m["name"]!!.jsonPrimitive.content.take(2))
    }

    @Test fun nothingToSayIsNull() {
        assertNull(Machine.of(null, null, null))
    }
}
