package com.dotrino.sdk

import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * WHERE THIS DEVICE RUNS, so the vault can tell it apart in its device list. Same shape as
 * `@dotrino/identity/machine`: `kind` (`android`), `os` (`Android 14`) and `name` (the phone
 * model, `Google Pixel 7`). It goes inside what the device signs, so nobody else can write it
 * for it; it decides nothing, it only helps the owner recognise the row.
 */
object Machine {
    private fun clip(s: String?, n: Int): String =
        (s ?: "").replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim().take(n)

    /** Pure, for tests: `null` when there is nothing worth saying. */
    fun of(osRelease: String?, manufacturer: String?, model: String?): JsonObject? {
        val major = clip(osRelease, 20).substringBefore('.')
        val os = if (major.isEmpty()) "" else "Android $major"
        val maker = clip(manufacturer, 32).replaceFirstChar { it.uppercase() }
        val mod = clip(model, 64)
        // Many models already carry the maker (`Pixel` does not, `Google Pixel 7` would be nicer).
        val name = clip(if (maker.isNotEmpty() && !mod.startsWith(maker, ignoreCase = true)) "$maker $mod" else mod, 64)
        if (os.isEmpty() && name.isEmpty()) return null
        return buildJsonObject { put("kind", "android"); put("os", clip(os, 40)); put("name", name) }
    }

    /** This phone. Read once: none of it changes while the app runs. */
    val current: JsonObject? by lazy {
        runCatching { of(Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL) }.getOrNull()
    }
}
