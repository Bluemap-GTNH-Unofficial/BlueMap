/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.gtnh.legacy

/**
 * Resource-path normalization N of legacy registry names (decision D18 / contract C1).
 *
 * Applied separately to the modid and to the block name of an FML registry name `modid:name`:
 * `[a-z0-9_.-]` stay, an uppercase ASCII letter `X` becomes `~x`, every other code point `c` becomes
 * `!` + two lowercase hex digits (`c <= 0xFF`) or `!u` + four lowercase hex digits (`c > 0xFF`).
 * The mapping is injective, so case-colliding names (`Foo`/`foo`) stay distinct on case-insensitive file systems.
 *
 * Self-contained (Kotlin stdlib only): the exporter mod compiles this file in directly.
 * Golden vectors: `testing/fixtures/resource-key-vectors.json`.
 */
object ResourceKeys {
    /** Normalizes one segment (a modid or a block name). Returns the input instance if nothing changes. */
    @JvmStatic
    fun normalize(segment: String): String {
        var i = 0
        while (i < segment.length && isKept(segment[i])) i++
        if (i == segment.length) return segment

        val out = StringBuilder(segment.length + 8)
        out.append(segment, 0, i)
        while (i < segment.length) {
            val cp = segment.codePointAt(i)
            when {
                cp < 0x80 && isKept(cp.toChar()) -> {
                    out.append(cp.toChar())
                }

                cp in 'A'.code..'Z'.code -> {
                    out.append('~').append((cp + ('a' - 'A')).toChar())
                }

                cp <= 0xFF -> {
                    out.append('!').append(hex(cp, 2))
                }

                else -> {
                    // supplementary code points do not fit 4 hex digits: encode their UTF-16 surrogates one by one
                    for (unit in String(Character.toChars(cp))) out.append("!u").append(hex(unit.code, 4))
                }
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /**
     * Splits an FML registry name at the first `:` (no `:` -> namespace `minecraft`) and normalizes both parts.
     * Returns `namespace to value`.
     */
    @JvmStatic
    fun normalizeRegistryName(registryName: String): Pair<String, String> {
        val colon = registryName.indexOf(':')
        if (colon < 0) return "minecraft" to normalize(registryName)
        return normalize(registryName.substring(0, colon)) to normalize(registryName.substring(colon + 1))
    }

    private fun isKept(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.' || c == '-'

    private fun hex(
        value: Int,
        digits: Int,
    ): String = Integer.toHexString(value).padStart(digits, '0')
}
