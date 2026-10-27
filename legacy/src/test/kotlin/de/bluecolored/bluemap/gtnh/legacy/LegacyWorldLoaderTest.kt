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

import com.flowpowered.math.vector.Vector2i
import de.bluecolored.bluemap.core.resources.pack.PackVersion
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.WorldLoaderType
import de.bluecolored.bluemap.core.world.mca.MCAWorld
import de.bluecolored.bluemap.gtnh.legacy.Fixtures.state
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LegacyWorldLoaderTest {
    @Test
    fun loadsWorldThroughRegisteredLoaderType(
        @TempDir world: Path,
    ) {
        Files.write(world.resolve("level.dat"), Fixtures.bytes("level.dat"))
        Fixtures.writeRegion(world.resolve("region"), -10, -4, Fixtures.bytes("endlessids_-10_-4.zlib"))
        Fixtures.writeRegion(world.resolve("DIM7/region"), 3, -2, Fixtures.bytes("neid_synthetic.zlib"))

        GtnhLegacy.register()
        val loaderType = WorldLoaderType.REGISTRY.get(Key("gtnh", "anvil_1_7"))!!
        val dataPack = DataPack(PackVersion(12, 0))

        val overworld = loaderType.loadWorld(world, DataPack.DIMENSION_OVERWORLD, null, dataPack)
        assertTrue(overworld is MCAWorld)
        overworld as MCAWorld
        assertEquals(world, overworld.dimensionFolder)
        assertEquals(0, overworld.dimensionType.minY)
        assertEquals(256, overworld.dimensionType.height)
        assertEquals(listOf(Vector2i(-1, -1)), overworld.listRegions().toList())
        assertEquals(state("gregtech:gt.blockores2", 8877), overworld.getChunk(-10, -4).getBlockState(0, 39, 2))
        assertEquals(state("gregtech:gt.blockores2", 8877), overworld.getChunkAtBlock(-160, -62).getBlockState(-160, 39, -62))

        val dim7 = loaderType.loadWorld(world, Key("gtnh", "dim_7"), null, dataPack) as MCAWorld
        assertEquals(world.resolve("DIM7"), dim7.dimensionFolder)
        assertEquals(state("gregtech:gt.blockcasings2", 39), dim7.getChunk(3, -2).getBlockState(2, 19, 4))
        assertSame(de.bluecolored.bluemap.core.world.Chunk.EMPTY_CHUNK, dim7.getChunk(0, 0))
    }
}
