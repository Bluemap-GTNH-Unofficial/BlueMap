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
package de.bluecolored.bluemap.gtnh.render.mods.chisel

import com.google.gson.JsonObject
import de.bluecolored.bluemap.core.util.Key

/** Chisel's texture types (`com.cricketcraft.chisel.api.rendering.TextureType`) that depend on the position. */
enum class ChiselType(
    /** Cells per side of the type's sheet. */
    val sheetSize: Int,
) {
    /** Connected texture: base tile (2×2 quarters) plus a 4×4 `-ctm` quarter sheet. */
    CTMX(4),

    /** Pillar: 2×2 `-ctmv` sheet by vertical (else horizontal) connections, `-top` on the ends. */
    CTMV(2),

    /** Horizontal connection: 2×2 `-ctmh` sheet on the sides, `-top` on top and bottom. */
    CTMH(2),

    /** Position-based variation over a 3×3 / 2×2 sheet. */
    V9(3),
    V4(2),

    /** Coordinate-random tile of a 4×4 / 3×3 / 2×2 sheet. */
    R16(4),
    R9(3),
    R4(2),
}

class ChiselVariant(
    val type: ChiselType,
    /** CTMX: the unconnected tile. */
    val base: Key?,
    /** CTMX: the 4×4 quarter sheet; other types: their sheet. */
    val sheet: Key,
    /** CTMV/CTMH: the end texture. */
    val top: Key?,
) {
    /** Lazily built quads, indexed by the renderer (see [ChiselCtmRenderer]). */
    internal val quadCache = arrayOfNulls<Any>(CACHE_SIZE)

    companion object {
        internal const val CACHE_SIZE = 6 * 4 * 20
    }
}

/** `gtnh/mods/chisel.json`: block state key -> meta -> variant; only full cubes are listed. */
class ChiselData(private val blocks: Map<Key, Map<Int, ChiselVariant>>) {
    val size: Int get() = blocks.values.sumOf { it.size }

    operator fun get(block: Key, meta: Int): ChiselVariant? = blocks[block]?.get(meta)

    fun textureKeys(): Set<Key> = blocks.values.flatMapTo(HashSet()) { metas ->
        metas.values.flatMap { listOfNotNull(it.base, it.sheet, it.top) }
    }

    companion object {
        fun parse(json: JsonObject): ChiselData {
            val blocks = HashMap<Key, Map<Int, ChiselVariant>>()
            json.getAsJsonObject("blocks")?.entrySet()?.forEach { (blockKey, metas) ->
                val variants = HashMap<Int, ChiselVariant>()
                metas.asJsonObject.entrySet().forEach metas@{ (meta, value) ->
                    val entry = value.asJsonObject
                    if (entry.get("fullCube")?.asBoolean == false) return@metas
                    val type = runCatching { ChiselType.valueOf(entry.get("type").asString) }.getOrNull() ?: return@metas
                    val textures = entry.getAsJsonObject("textures") ?: return@metas
                    fun tex(name: String): Key? = textures.get(name)?.takeIf { !it.isJsonNull }?.asString?.let(Key::parse)
                    val variant = when (type) {
                        ChiselType.CTMX -> {
                            val base = tex("base") ?: return@metas
                            ChiselVariant(type, base, tex("ctm") ?: return@metas, null)
                        }
                        ChiselType.CTMV, ChiselType.CTMH -> ChiselVariant(type, null, tex("sheet") ?: return@metas, tex("top") ?: return@metas)
                        else -> ChiselVariant(type, null, tex("sheet") ?: return@metas, null)
                    }
                    variants[meta.toIntOrNull() ?: return@metas] = variant
                }
                if (variants.isNotEmpty()) blocks[Key.parse(blockKey)] = variants
            }
            return ChiselData(blocks)
        }
    }
}
