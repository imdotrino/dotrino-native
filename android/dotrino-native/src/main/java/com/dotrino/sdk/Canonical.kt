package com.dotrino.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Canonical JSON, byte-identical to `canonicalStringify` in `@dotrino/identity`
 * (`vault/core.js`): keys sorted, no whitespace, strings escaped as `JSON.stringify` does.
 *
 * Everything a vault signs or checks goes through this, so a single differing byte means a
 * signature that never verifies — seen from outside as «the vault does not answer».
 *
 * Numbers: only integers. Everything this library signs (`ts`, `seq`, `iat`, `v`) is an
 * integer, and reproducing JS float formatting is exactly the kind of «almost the same» that
 * breaks a signature silently. A fraction throws instead.
 */
object Canonical {
    fun stringify(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                // JS `Object.keys(v).sort()` compares UTF-16 code units, and so does String.compareTo.
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    quote(k, out); out.append(':'); write(e.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonPrimitive -> when {
                e.isString -> quote(e.content, out)
                e.booleanOrNull != null -> out.append(e.content)
                e.longOrNull != null -> out.append(e.longOrNull.toString())
                else -> throw IllegalArgumentException("canonical: non-integer number ${e.content} is not supported")
            }
        }
    }

    /** `JSON.stringify` for a string: `\" \\ \b \f \n \r \t`, other controls as `\u00xx`, the rest raw. */
    fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u").append(String.format("%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
