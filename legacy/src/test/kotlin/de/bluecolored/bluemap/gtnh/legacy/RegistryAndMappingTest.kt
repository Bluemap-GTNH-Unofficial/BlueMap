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

import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.DimensionType
import de.bluecolored.bluemap.core.world.biome.Biome
import de.bluecolored.bluemap.gtnh.legacy.Fixtures.registry
import de.bluecolored.bluemap.gtnh.legacy.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RegistryAndMappingTest {
    @Test
    fun levelDatRegistry() {
        assertEquals(26, registry.size)
        assertEquals(11302, registry.maxId)
        assertEquals("minecraft:air", registry.name(0))
        assertEquals("minecraft:stone", registry.name(1))
        assertEquals("gregtech:gt.blockcasings2", registry.name(3005))
        assertEquals("gregtech:gt.blockreinforced", registry.name(3053))
        assertEquals("gregtech:gt.blockores2", registry.name(3034))
        assertEquals("ProjRed|Exploration:projectred.exploration.lily", registry.name(11302))
        assertEquals("BiomesOPlenty:colorizedLeaves2", registry.name(955))
        // \u0002 item entries are not blocks
        assertNull(registry.name(20000))
        assertNull(registry.name(4096))
        assertNull(registry.name(-1))
        // BlockAliases redirect the persisted name
        assertEquals("newmod:new_block", registry.name(4000))
    }

    /** The full level.dat of the GTNH 2.9.0-RC-2 test world, if present in the workspace. */
    @Test
    fun fullWorldRegistry() {
        val levelDat = Path.of("../../worlds/gtnh-initial-rwg/level.dat")
        assumeTrue(Files.exists(levelDat), "test world not available")
        val full = FmlBlockRegistry.load(levelDat)
        assertEquals(4263, full.size)
        assertEquals(11388, full.maxId)
        assertEquals("gregtech:gt.blockcasings2", full.name(3005))
        assertEquals("gregtech:gt.blockreinforced", full.name(3053))
    }

    @Test
    fun blockStateMapper() {
        val mapper = RegistryBlockStateMapper(registry)
        assertSame(BlockState.AIR, mapper.map(0, 0))
        assertSame(BlockState.AIR, mapper.map(0, 7))
        assertEquals(state("gregtech:gt.blockcasings2", 12), mapper.map(3005, 12))
        assertEquals(Key("gregtech", "gt.blockcasings2"), mapper.map(3005, 12).id)
        assertSame(mapper.map(3034, 8877), mapper.map(3034, 8877))
        assertEquals("65535", mapper.map(3034, 65535).properties["meta"])

        val unknown = mapper.map(12345, 17)
        assertEquals(Key("bluemap-gtnh", "unknown"), unknown.id)
        assertEquals(mapOf("id" to "12345", "meta" to "17"), unknown.properties)
    }

    @Test
    fun biomeMapper() {
        val modern = setOf("plains", "windswept_hills", "snowy_plains", "old_growth_spruce_taiga", "jungle", "nether_wastes")
        val modernMapper = VanillaLegacyBiomeMapper({ key -> if (key.value in modern) TestBiome(key) else null })
        assertEquals(Key.minecraft("plains"), modernMapper.biome(1).key)
        assertEquals(Key.minecraft("windswept_hills"), modernMapper.biome(3).key) // extreme_hills
        assertEquals(Key.minecraft("nether_wastes"), modernMapper.biome(8).key) // hell
        assertEquals(Key.minecraft("snowy_plains"), modernMapper.biome(12).key) // ice_plains
        assertEquals(Key.minecraft("old_growth_spruce_taiga"), modernMapper.biome(160).key) // mega_spruce_taiga
        // modded / unknown ids fall back to plains
        assertEquals(Key.minecraft("plains"), modernMapper.biome(72).key)
        assertEquals(Key.minecraft("plains"), modernMapper.biome(40000).key)

        // older datapacks still resolve by the 1.13 name
        val oldMapper = VanillaLegacyBiomeMapper({ key -> if (key.value == "mountains") TestBiome(key) else null })
        assertEquals(Key.minecraft("mountains"), oldMapper.biome(3).key)
        assertSame(Biome.DEFAULT, oldMapper.biome(1))

        // overrides (e.g. a runtime biome dump) win
        val overridden = VanillaLegacyBiomeMapper({ key -> TestBiome(key) }, mapOf(72 to Key("gtnh", "some_biome")))
        assertEquals(Key("gtnh", "some_biome"), overridden.biome(72).key)

        // non-vanilla ids resolve to the server mod's runtime datapack biome gtnh:biome_<id> when it exists
        val runtime = VanillaLegacyBiomeMapper({ key -> if (key.namespace == "gtnh" || key.value == "plains") TestBiome(key) else null })
        assertEquals(Key("gtnh", "biome_72"), runtime.biome(72).key)
        assertEquals(Key.minecraft("plains"), runtime.biome(1).key)
    }

    @Test
    fun dimensions() {
        val root = Path.of("world")
        assertEquals(root, LegacyDimensions.resolveFolder(root, DataPack.DIMENSION_OVERWORLD))
        assertEquals(root.resolve("DIM-1"), LegacyDimensions.resolveFolder(root, DataPack.DIMENSION_THE_NETHER))
        assertEquals(root.resolve("DIM1"), LegacyDimensions.resolveFolder(root, DataPack.DIMENSION_THE_END))
        assertEquals(root.resolve("DIM7"), LegacyDimensions.resolveFolder(root, Key("gtnh", "dim_7")))
        assertEquals(root.resolve("DIM-112"), LegacyDimensions.resolveFolder(root, Key("gtnh", "dim_-112")))
        assertEquals(root, LegacyDimensions.resolveFolder(root, Key("gtnh", "dim_0")))

        assertEquals(Key("gtnh", "dim_7"), LegacyDimensions.dimensionKey(7))
        assertEquals(Key("gtnh", "dim_-112"), LegacyDimensions.dimensionKey(-112))
        assertEquals(DataPack.DIMENSION_THE_NETHER, LegacyDimensions.dimensionKey(-1))
        assertNull(LegacyDimensions.dimensionId(Key("gtnh", "dim_x")))
        assertNull(LegacyDimensions.dimensionId(Key("other", "dim_7")))

        for (key in listOf(DataPack.DIMENSION_OVERWORLD, DataPack.DIMENSION_THE_NETHER, DataPack.DIMENSION_THE_END, Key("gtnh", "dim_7"))) {
            val type = LegacyDimensions.dimensionType(key)
            assertEquals(0, type.minY)
            assertEquals(256, type.height)
        }
        assertSame(DimensionType.NETHER, LegacyDimensions.dimensionType(DataPack.DIMENSION_THE_NETHER))
        assertSame(DimensionType.END, LegacyDimensions.dimensionType(DataPack.DIMENSION_THE_END))
        assertTrue(LegacyDimensions.dimensionType(Key("gtnh", "dim_7")).hasSkylight())
    }
}
