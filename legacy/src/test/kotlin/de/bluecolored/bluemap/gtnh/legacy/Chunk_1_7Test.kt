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
@file:Suppress("ktlint:standard:filename", "ktlint:standard:class-naming")

package de.bluecolored.bluemap.gtnh.legacy

import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.Chunk
import de.bluecolored.bluemap.core.world.LightData
import de.bluecolored.bluemap.gtnh.legacy.Fixtures.loadChunk
import de.bluecolored.bluemap.gtnh.legacy.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Chunk_1_7Test {
    private fun Chunk.light(
        x: Int,
        y: Int,
        z: Int,
    ): Pair<Int, Int> {
        val light = getLightData(x, y, z, LightData(0, 0))
        return light.skyLight to light.blockLight
    }

    private fun Chunk.count(state: BlockState): Int {
        var count = 0
        for (y in 0..255) for (z in 0..15) for (x in 0..15) if (getBlockState(x, y, z) == state) count++
        return count
    }

    /** Real GTNH 2.9 chunk (-10,-4): EndlessIDs layout with BlocksB2Hi (id > 4095) and Data2 (meta > 255). */
    @Test
    fun endlessIdsChunk() {
        val chunk = loadChunk("endlessids_-10_-4.zlib")

        assertTrue(chunk.isGenerated)
        assertTrue(chunk.hasLightData())
        assertEquals(0, chunk.getMinY(0, 0))
        assertEquals(95, chunk.getMaxY(0, 0))

        // id 11302 > 4095 needs BlocksB2Hi
        assertEquals(state("ProjRed|Exploration:projectred.exploration.lily", 0), chunk.getBlockState(1, 70, 15))
        // 16-bit metadata needs Data1High + Data2
        assertEquals(state("gregtech:gt.blockores2", 8877), chunk.getBlockState(0, 39, 2))
        assertEquals(state("gregtech:gt.blockores2", 24054), chunk.getBlockState(14, 29, 9))
        assertEquals(state("gregtech:gt.blockores7", 28902), chunk.getBlockState(2, 12, 0))
        assertEquals(state("minecraft:bedrock", 0), chunk.getBlockState(0, 0, 0))
        assertEquals(state("minecraft:stone", 0), chunk.getBlockState(5, 20, 0))
        assertEquals(state("etfuturum:deepslate", 0), chunk.getBlockState(0, 1, 0))
        assertEquals(state("minecraft:leaves", 1), chunk.getBlockState(7, 71, 0))
        assertEquals(state("BiomesOPlenty:colorizedLeaves2", 9), chunk.getBlockState(13, 76, 11))
        assertSame(BlockState.AIR, chunk.getBlockState(14, 11, 8))
        assertSame(BlockState.AIR, chunk.getBlockState(0, 200, 0))

        // whole-chunk histogram values from nbtlib
        assertEquals(9260, chunk.count(state("minecraft:stone", 0)))
        assertEquals(4307, chunk.count(state("etfuturum:deepslate", 0)))
        assertEquals(25, chunk.count(state("gregtech:gt.blockores2", 8877)))
        assertEquals(1, chunk.count(state("ProjRed|Exploration:projectred.exploration.lily", 0)))

        // light: (sky, block)
        assertEquals(0 to 15, chunk.light(5, 5, 2)) // lava
        assertEquals(15 to 0, chunk.light(0, 70, 12))
        assertEquals(0 to 7, chunk.light(3, 21, 5)) // glow lichen
        assertEquals(15 to 0, chunk.light(7, 90, 7))
        assertEquals(15 to 0, chunk.light(7, 200, 7)) // above the highest section

        // HeightMap
        assertTrue(chunk.hasWorldSurfaceHeights())
        assertEquals(71, chunk.getWorldSurfaceY(0, 0))
        assertEquals(70, chunk.getWorldSurfaceY(1, 15))

        // tile entities (absolute coordinates in NBT, 1.7 ids without namespace)
        val lichen = chunk.getBlockEntity(3, 21, 5)
        assertNotNull(lichen)
        assertEquals(Key("minecraft", "etfuturum.glow_lichen"), lichen!!.id)
        assertEquals(-157, lichen.x)
        assertEquals("tile.projectred.exploration.lily|0", chunk.getBlockEntity(1, 70, 15)!!.id.value)
        var blockEntities = 0
        chunk.iterateBlockEntities { blockEntities++ }
        assertEquals(2, blockEntities)
    }

    @Test
    fun endlessIdsBiomes() {
        val chunk = loadChunk("endlessids_-10_-4.zlib") as Chunk_1_7
        // Biomes16v2 little-endian shorts, the whole chunk is biome 72 (modded)
        for (z in 0..15) for (x in 0..15) assertEquals(72, chunk.getBiomeId(x, z))
    }

    /** Synthetic NotEnoughIDs section: Blocks16/Data16 (big-endian) win over a conflicting vanilla copy. */
    @Test
    fun notEnoughIdsSection() {
        val chunk = loadChunk("neid_synthetic.zlib")

        assertTrue(chunk.isGenerated)
        assertEquals(42L, chunk.inhabitedTime)
        assertEquals(state("ProjRed|Exploration:projectred.exploration.lily", 0), chunk.getBlockState(0, 0, 0))
        assertEquals(state("gregtech:gt.blockores2", 8877), chunk.getBlockState(1, 2, 3))
        assertEquals(state("minecraft:stone", 0), chunk.getBlockState(15, 15, 15))
        // the vanilla Blocks copy is all stone; Blocks16 says air
        assertSame(BlockState.AIR, chunk.getBlockState(2, 2, 2))

        // unknown id
        val unknown = chunk.getBlockState(4, 5, 6)
        assertEquals(Key("bluemap-gtnh", "unknown"), unknown.id)
        assertEquals(mapOf("id" to "20000", "meta" to "3"), unknown.properties)

        assertEquals(9 to 4, chunk.light(1, 2, 3))
        assertEquals(15 to 14, chunk.light(2, 2, 3))
        assertEquals(0 to 0, chunk.light(3, 3, 3))

        // second section: Blocks/Add/Data/Data1High without Data2 -> id 3005, meta 0x27
        assertEquals(state("gregtech:gt.blockcasings2", 39), chunk.getBlockState(2, 19, 4))
        assertEquals(state("minecraft:grass", 0), chunk.getBlockState(3, 19, 4))
        assertSame(BlockState.AIR, chunk.getBlockState(5, 19, 5))
        assertEquals(15 to 0, chunk.light(5, 19, 5))

        assertEquals(20, chunk.getWorldSurfaceY(5, 3))
        // legacy block entity ids are read as their modern keys
        assertEquals(Key.minecraft("chest"), chunk.getBlockEntity(1, 2, 3)!!.id)
        assertNull(chunk.getBlockEntity(0, 2, 3))
    }

    @Test
    fun notEnoughIdsBiomes16() {
        val chunk = loadChunk("neid_synthetic.zlib") as Chunk_1_7
        // Biomes16 (low bytes only, as EndlessIDs reads it) wins over vanilla Biomes (all 1)
        assertEquals(4, chunk.getBiomeId(5, 3))
        assertEquals(0, chunk.getBiomeId(0, 0))
    }

    @Test
    fun vanillaChunk() {
        val mapper = VanillaLegacyBiomeMapper({ key -> if (key.namespace == "minecraft") TestBiome(key) else null })
        val chunk = loadChunk("vanilla_synthetic.zlib", mapper) as Chunk_1_7

        assertFalse(chunk.isGenerated)
        // LightPopulated=0, but the sections carry light arrays
        assertTrue(chunk.hasLightData())
        assertEquals(64, chunk.getMinY(0, 0))
        assertEquals(79, chunk.getMaxY(0, 0))
        assertEquals(state("minecraft:stone", 0), chunk.getBlockState(0, 64, 0))
        assertSame(BlockState.AIR, chunk.getBlockState(0, 63, 0))
        assertEquals(0 to 0, chunk.light(0, 10, 0)) // below the lowest section
        assertEquals(15 to 0, chunk.light(0, 100, 0)) // above: full skylight

        // vanilla Biomes bytes are unsigned
        assertEquals(129, chunk.getBiomeId(0, 0))
        assertEquals(255, chunk.getBiomeId(1, 0))
        assertEquals(3, chunk.getBiomeId(2, 0))
        assertEquals(Key.minecraft("sunflower_plains"), chunk.getBiome(0, 64, 0).key)
        assertEquals(Key.minecraft("windswept_hills"), chunk.getBiome(2, 64, 0).key)
        assertEquals(Key.minecraft("plains"), chunk.getBiome(1, 64, 0).key)
    }
}
