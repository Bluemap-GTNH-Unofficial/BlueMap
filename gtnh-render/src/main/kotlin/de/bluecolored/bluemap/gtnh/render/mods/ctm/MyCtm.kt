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
package de.bluecolored.bluemap.gtnh.render.mods.ctm

import com.google.gson.JsonObject
import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.render.mods.Faces

/** One MyCTMLib connected texture (`myctmlib` section of the base texture's `.mcmeta`). Only `compact` is drawn. */
class MyCtmTexture(
    val base: Key,
    val method: String,
    /** The 4×4 quarter sheet (`compact`). */
    val connection: Key,
    /** Alternative unconnected quarters, used once any quarter of the face connects. */
    val alt: Key?,
    /** Textures that connect with this one besides itself. */
    val equivalents: Set<Key>,
) {
    val isCompact: Boolean get() = method == "compact"
}

/** `gtnh/mods/myctmlib.json`: base texture key -> connected texture. */
class MyCtmData(
    private val textures: Map<Key, MyCtmTexture>,
) {
    val size: Int get() = textures.size

    operator fun get(texture: Key): MyCtmTexture? = textures[texture]

    /** Whether [candidate] (a neighbour's texture on the same side) connects with [target]. */
    fun matches(
        target: MyCtmTexture,
        candidate: Key?,
    ): Boolean {
        if (candidate == null) return false
        if (candidate == target.base || candidate in target.equivalents) return true
        return textures[candidate]?.equivalents?.contains(target.base) == true
    }

    fun textureKeys(): Set<Key> = textures.values.filter { it.isCompact }.flatMapTo(HashSet()) { listOfNotNull(it.connection, it.alt) }

    companion object {
        fun parse(json: JsonObject): MyCtmData {
            val textures = HashMap<Key, MyCtmTexture>()
            json.getAsJsonObject("textures")?.entrySet()?.forEach { (key, value) ->
                val entry = value.asJsonObject
                val connection = entry.get("connection")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                val base = Key.parse(key)
                textures[base] =
                    MyCtmTexture(
                        base = base,
                        method = entry.get("method")?.takeIf { it.isJsonPrimitive }?.asString ?: "compact",
                        connection = Key.parse(connection),
                        alt =
                            entry
                                .get("alt")
                                ?.takeIf { it.isJsonPrimitive }
                                ?.asString
                                ?.let(Key::parse),
                        equivalents =
                            entry.getAsJsonArray("equivalents")?.mapNotNullTo(HashSet()) {
                                it.takeIf { e -> e.isJsonPrimitive }?.asString?.let(Key::parse)
                            } ?: emptySet(),
                    )
            }
            return MyCtmData(textures)
        }
    }
}

/**
 * MyCTMLib's `compact` quadrant selection (`Textures.buildConnect`). Faces use the 1.7 `RenderBlocks` basis
 * ([Faces.right] / [Faces.down] with the legacy bottom face), which is MyCTMLib's `forgeDirections` table
 * (up, right, down, left per side).
 */
object MyCtm {
    /** Quadrant order of [indices]: top-left, top-right, bottom-left, bottom-right (screen sx1, sy1, sx2, sy2). */
    val QUADRANTS: Array<FloatArray> =
        arrayOf(
            floatArrayOf(0f, 0f, 0.5f, 0.5f),
            floatArrayOf(0.5f, 0f, 1f, 0.5f),
            floatArrayOf(0f, 0.5f, 0.5f, 1f),
            floatArrayOf(0.5f, 0.5f, 1f, 1f),
        )

    /** Whether the in-plane neighbour at screen offset ([h] right, [v] up) of a face has a matching texture. */
    fun interface FaceNeighbours {
        fun matches(
            dx: Int,
            dy: Int,
            dz: Int,
        ): Boolean
    }

    /**
     * The four sub-icon indices (1..16 sheet cell + 1, 17..20 base quarters, 21..24 alt quarters) of [face] in
     * [QUADRANTS] order. Diagonals only count when both adjacent sides connect.
     *
     * Adapted from MyCTMLib `Textures.buildConnect` (MIT, Copyright (c) 2024 初夏同学); see NOTICE.md.
     */
    fun indices(
        face: Direction,
        neighbours: FaceNeighbours,
        hasAlt: Boolean,
        out: IntArray = IntArray(4),
    ): IntArray {
        val r = Faces.right(face)
        val d = Faces.down(face, legacyBottom = true)
        // connections 0 up, 1 right, 2 down, 3 left; 4 up-right, 5 right-down, 6 down-left, 7 left-up
        val hs = intArrayOf(0, 1, 0, -1)
        val vs = intArrayOf(1, 0, -1, 0)

        fun offset(
            h: Int,
            v: Int,
        ): Boolean = neighbours.matches(r[0] * h - d[0] * v, r[1] * h - d[1] * v, r[2] * h - d[2] * v)
        val c = BooleanArray(8)
        for (i in 0 until 4) c[i] = offset(hs[i], vs[i])
        for (i in 4 until 8) {
            val a = i - 4
            val b = if (i - 3 == 4) 0 else i - 3
            c[i] = c[a] && c[b] && offset(hs[a] + hs[b], vs[a] + vs[b])
        }
        out[0] =
            when {
                c[7] -> 1
                c[3] && c[0] -> 11
                c[3] -> 9
                c[0] -> 3
                else -> 17
            }
        out[1] =
            when {
                c[4] -> 2
                c[0] && c[1] -> 12
                c[0] -> 4
                c[1] -> 10
                else -> 18
            }
        out[2] =
            when {
                c[6] -> 5
                c[2] && c[3] -> 15
                c[2] -> 7
                c[3] -> 13
                else -> 19
            }
        out[3] =
            when {
                c[5] -> 6
                c[1] && c[2] -> 16
                c[1] -> 14
                c[2] -> 8
                else -> 20
            }
        if (hasAlt && out.any { it !in 17..20 }) {
            for (i in 0 until 4) if (out[i] in 17..20) out[i] += 4
        }
        return out
    }
}
