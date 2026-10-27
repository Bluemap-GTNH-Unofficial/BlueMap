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
import de.bluecolored.bluemap.gtnh.legacy.DumpVerifier.Box
import de.bluecolored.bluemap.gtnh.legacy.DumpVerifier.Entry
import de.bluecolored.bluemap.gtnh.legacy.DumpVerifier.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Fixture chunk (-10,-4); world coordinates from the nbtlib decode used in [Chunk_1_7Test]. */
class DumpVerifierTest {
    @TempDir
    lateinit var world: Path

    private val overworld = DataPack.DIMENSION_OVERWORLD

    // glow lichen with tile entity at local (3,21,5); gt ore meta 8877 at local (0,39,2)
    private val lichen = Entry(-157, 21, -59, "etfuturum:glow_lichen", 0, "etfuturum.glow_lichen", true)
    private val ore = Entry(-160, 39, -62, "gregtech:gt.blockores2", 8877, null, false)

    @BeforeEach
    fun setUp() {
        Files.write(world.resolve("level.dat"), Fixtures.bytes("level.dat"))
        Fixtures.writeRegion(world.resolve("region"), -10, -4, Fixtures.bytes("endlessids_-10_-4.zlib"))
    }

    private fun kinds(
        entries: List<Entry>,
        box: Box? = null,
    ) = DumpVerifier
        .verify(world, overworld, entries, box)
        .mismatches
        .groupingBy { it.kind }
        .eachCount()

    @Test
    fun matchingDump() {
        assertEquals(emptyMap<Kind, Int>(), kinds(listOf(lichen)))
        assertEquals(emptyMap<Kind, Int>(), kinds(listOf(ore)))
    }

    @Test
    fun stateAndTileEntityMismatches() {
        val wrong = ore.copy(meta = 1, tileEntityId = "Chest", hasTileEntity = true)
        assertEquals(mapOf(Kind.STATE to 1, Kind.TILE_ENTITY_PRESENCE to 1), kinds(listOf(wrong)))

        val wrongTe = lichen.copy(tileEntityId = "Sign")
        assertEquals(mapOf(Kind.TILE_ENTITY_ID to 1), kinds(listOf(wrongTe)))
    }

    @Test
    fun readerBlockMissingInDump() {
        val box = Box(lichen.x, lichen.y, lichen.z, lichen.x, lichen.y, lichen.z)
        assertEquals(mapOf(Kind.MISSING_IN_DUMP to 1), kinds(emptyList(), box))
    }
}
