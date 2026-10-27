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

import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.Chunk
import de.bluecolored.bluemap.core.world.LightData
import de.bluecolored.bluemap.core.world.biome.Biome
import java.util.function.Consumer

/**
 * A Minecraft 1.7.10 Anvil chunk (vanilla, NotEnoughIDs or EndlessIDs/ChunkAPI layout), see [LegacyChunkLoader].
 */
class Chunk_1_7 internal constructor(
    private val generated: Boolean,
    private val hasLightData: Boolean,
    private val inhabitedTime: Long,
    private val sections: Array<LegacySection?>,
    private val sectionMin: Int,
    private val biomeIds: CharArray?,
    private val heightMap: IntArray?,
    private val blockEntities: Map<Long, BlockEntity>,
    private val biomes: LegacyBiomeMapper,
    private val skyLight: Int,
) : Chunk {
    private val sectionMax: Int = if (sections.isEmpty()) sectionMin else sectionMin + sections.size - 1

    override fun isGenerated(): Boolean = generated

    /**
     * True if the sections carry light arrays. `LightPopulated` is not used: GTNH (ArchaicFix lighting) leaves it 0 on
     * many fully lit chunks, including spawn chunks, while their stored light is valid.
     */
    override fun hasLightData(): Boolean = hasLightData

    override fun getInhabitedTime(): Long = inhabitedTime

    override fun getBlockState(
        x: Int,
        y: Int,
        z: Int,
    ): BlockState = section(y shr 4)?.blockState(index(x, y, z)) ?: BlockState.AIR

    /** The raw legacy biome id at the column, or -1 if the chunk has no biome data. */
    fun getBiomeId(
        x: Int,
        z: Int,
    ): Int = biomeIds?.get((z and 0xF) shl 4 or (x and 0xF))?.code ?: -1

    override fun getBiome(
        x: Int,
        y: Int,
        z: Int,
    ): Biome {
        val id = getBiomeId(x, z)
        return if (id < 0) Biome.DEFAULT else biomes.biome(id)
    }

    override fun getLightData(
        x: Int,
        y: Int,
        z: Int,
        target: LightData,
    ): LightData {
        val sectionY = y shr 4
        val section = section(sectionY) ?: return if (sectionY < sectionMin) target.set(0, 0) else target.set(skyLight, 0)
        return section.lightData(index(x, y, z), target)
    }

    override fun getMinY(
        x: Int,
        z: Int,
    ): Int = sectionMin * 16

    override fun getMaxY(
        x: Int,
        z: Int,
    ): Int = sectionMax * 16 + 15

    /** The 1.7 `HeightMap`: the lowest y with full skylight access in the column. */
    override fun hasWorldSurfaceHeights(): Boolean = heightMap != null

    override fun getWorldSurfaceY(
        x: Int,
        z: Int,
    ): Int = heightMap?.get((z and 0xF) shl 4 or (x and 0xF)) ?: 0

    override fun getBlockEntity(
        x: Int,
        y: Int,
        z: Int,
    ): BlockEntity? = blockEntities[blockEntityKey(x, y, z)]

    override fun iterateBlockEntities(consumer: Consumer<BlockEntity>) {
        blockEntities.values.forEach(consumer)
    }

    private fun section(sectionY: Int): LegacySection? {
        val i = sectionY - sectionMin
        return if (i < 0 || i >= sections.size) null else sections[i]
    }

    companion object {
        internal fun index(
            x: Int,
            y: Int,
            z: Int,
        ): Int = (y and 0xF) shl 8 or ((z and 0xF) shl 4) or (x and 0xF)

        internal fun blockEntityKey(
            x: Int,
            y: Int,
            z: Int,
        ): Long = (y.toLong() shl 8) or ((x and 0xF) shl 4).toLong() or (z and 0xF).toLong()
    }
}

/**
 * One 16x16x16 section with its blocks already resolved to a palette of [BlockState]s,
 * so lookups are a single array access without allocation.
 */
internal class LegacySection(
    val sectionY: Int,
    private val palette: Array<BlockState>,
    // palette index per block, null if the palette has a single entry; byte-indices when the palette fits 256 entries
    private val smallIndices: ByteArray?,
    private val largeIndices: CharArray?,
    private val blockLight: ByteArray,
    private val skyLight: ByteArray,
) {
    val hasLightData: Boolean get() = skyLight.size >= LIGHT_BYTES || blockLight.size >= LIGHT_BYTES

    fun blockState(index: Int): BlockState =
        when {
            smallIndices != null -> palette[smallIndices[index].toInt() and 0xFF]
            largeIndices != null -> palette[largeIndices[index].code]
            else -> palette[0]
        }

    fun lightData(
        index: Int,
        target: LightData,
    ): LightData = target.set(nibble(skyLight, index), nibble(blockLight, index))

    private fun nibble(
        array: ByteArray,
        index: Int,
    ): Int {
        val i = index shr 1
        if (i >= array.size) return 0
        return (array[i].toInt() shr ((index and 1) shl 2)) and 0xF
    }

    private companion object {
        const val LIGHT_BYTES = 2048
    }
}
