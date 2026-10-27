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
package de.bluecolored.bluemap.gtnh

import de.bluecolored.bluemap.core.resources.pack.PackVersion
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.gtnh.RuntimeDataPack.BiomeInfo
import de.bluecolored.bluemap.gtnh.RuntimeDataPack.DimensionInfo
import de.bluecolored.bluemap.gtnh.legacy.VanillaLegacyBiomeMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The generated pack must load through BlueMap's own datapack reader and reach the legacy biome mapper. */
class RuntimeDataPackTest {
    @Test
    fun dimensionTypesLoadAsBlueMapDimensionTypes(
        @TempDir root: Path,
    ) {
        val pack = RuntimeDataPack(root)
        pack.writeDimensionType(DimensionInfo(7, natural = true, hasSkylight = true, hasCeiling = false, ambientLight = 0f, coordinateScale = 1.0))
        pack.writeDimensionType(DimensionInfo(-23, natural = false, hasSkylight = false, hasCeiling = true, ambientLight = 0.1f, coordinateScale = 8.0))

        val dataPack = load(root)

        val surface = dataPack.getDimensionType(RuntimeDataPack.dimensionTypeKey(7))!!
        assertTrue(surface.hasSkylight())
        assertFalse(surface.hasCeiling())
        assertEquals(0, surface.minY)
        assertEquals(256, surface.height)

        val hell = dataPack.getDimensionType(RuntimeDataPack.dimensionTypeKey(-23))!!
        assertFalse(hell.hasSkylight())
        assertTrue(hell.hasCeiling())
        assertEquals(0.1f, hell.ambientLight)
        assertEquals(8.0, hell.coordinateScale)
        assertEquals(0, hell.minY)
        assertEquals(256, hell.height)
    }

    @Test
    fun moddedBiomesResolveThroughTheLegacyBiomeMapper(
        @TempDir root: Path,
    ) {
        val pack = RuntimeDataPack(root)
        pack.writeBiomes(
            listOf(
                BiomeInfo(1, 0.1f, 0.1f, 0xFFFFFF), // vanilla plains: left to the vanilla table
                BiomeInfo(72, -0.5f, 0.4f, 0xFFFFFF),
                BiomeInfo(209, 2.0f, 0f, 0x808080),
            ),
        )

        val dataPack = load(root)
        val mapper = VanillaLegacyBiomeMapper(dataPack::getBiome)

        assertEquals(null, dataPack.getBiome(VanillaLegacyBiomeMapper.runtimeKey(1)))

        val cold = mapper.biome(72)
        assertEquals(VanillaLegacyBiomeMapper.runtimeKey(72), cold.key)
        assertEquals(-0.5f, cold.temperature)
        assertEquals(0.4f, cold.downfall)

        val hot = mapper.biome(209)
        assertEquals(VanillaLegacyBiomeMapper.runtimeKey(209), hot.key)
        assertEquals(2.0f, hot.temperature)
        assertEquals(0f, hot.downfall)
        assertEquals(RuntimeDataPack.multiply(RuntimeDataPack.DEFAULT_WATER_COLOR, 0x808080) or OPAQUE, hot.waterColor.straight().int)
    }

    @Test
    fun biomesOfAPreviousRunAreReplaced(
        @TempDir root: Path,
    ) {
        val pack = RuntimeDataPack(root)
        pack.writeBiomes(listOf(BiomeInfo(72, 0.5f, 0.5f, 0xFFFFFF), BiomeInfo(209, 0.5f, 0.5f, 0xFFFFFF)))
        pack.writeBiomes(listOf(BiomeInfo(209, 0.5f, 0.5f, 0xFFFFFF)))

        val biomes = root.resolve("data/gtnh/worldgen/biome")
        assertEquals(listOf("biome_209.json"), Files.list(biomes).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun waterColorMultiplierScalesEachChannel() {
        assertEquals(0x3F76E4, RuntimeDataPack.multiply(0x3F76E4, 0xFFFFFF))
        assertEquals(0x000000, RuntimeDataPack.multiply(0x3F76E4, 0x000000))
        assertEquals(0x37769B, RuntimeDataPack.multiply(0x3F76E4, 0xE0FFAE)) // 1.7.10 swampland
    }

    private fun load(root: Path): DataPack = DataPack(PackVersion(12, 0)).apply { loadResources(listOf(root)) }

    private companion object {
        const val OPAQUE = 0xFF shl 24
    }
}
