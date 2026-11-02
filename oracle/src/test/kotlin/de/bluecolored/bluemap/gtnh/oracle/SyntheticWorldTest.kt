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
package de.bluecolored.bluemap.gtnh.oracle

import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.LightData
import de.bluecolored.bluemap.core.world.biome.Biome
import de.bluecolored.bluemap.gtnh.legacy.BlockShapes
import de.bluecolored.bluemap.gtnh.legacy.Chunk_1_7
import de.bluecolored.bluemap.gtnh.legacy.LegacyDimensions
import de.bluecolored.bluenbt.NBTReader
import de.bluecolored.bluenbt.NBTWriter
import de.bluecolored.bluenbt.TagType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class SyntheticWorldTest {
    private val chunks = StateChunks(BlockShapes.DEFAULT, { Biome.DEFAULT }, LegacyDimensions.OVERWORLD_TYPE)

    private fun state(
        name: String,
        meta: Int,
        tileEntity: ByteArray? = null,
    ): String =
        chunks
            .world(name, meta, tileEntity)
            .chunk
            .getBlockState(0, 64, 0)
            .toString()
            .removeSuffix("[]")

    private fun nbt(content: NBTWriter.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        NBTWriter(out).use { writer ->
            writer.beginCompound()
            writer.content()
            writer.endCompound()
        }
        return out.toByteArray()
    }

    @Test
    fun `modded blocks keep their identity and metas above 15 use the EndlessIDs layers`() {
        assertEquals("foo:bar[meta=3]", state("foo:bar", 3))
        assertEquals("foo:bar[meta=16]", state("foo:bar", 16))
        assertEquals("foo:bar[meta=300]", state("foo:bar", 300))
        assertEquals("foo:bar[meta=65535]", state("foo:bar", 65535))
        assertEquals("~i~c2:block~rub~wood[meta=0]", state("IC2:blockRubWood", 0))
    }

    @Test
    fun `vanilla blocks are flattened and fixed`() {
        assertEquals("minecraft:sandstone_slab[type=bottom,waterlogged=false]", state("minecraft:stone_slab", 1))
        assertEquals("minecraft:stone", state("minecraft:stone", 0))
        assertEquals("minecraft:grass_block[snowy=false]", state("minecraft:grass", 0))
        assertEquals("minecraft:torch", state("minecraft:torch", 5))
    }

    @Test
    fun `the client's tile-entity NBT becomes the block entity`() {
        // minecraft:chest[meta=2] of the client exporter: {Items: [], z: 0, id: "Chest", y: 64, x: 0}
        val chest = Base64.getDecoder().decode("CgAACQAFSXRlbXMAAAAAAAMAAXoAAAAACAACaWQABUNoZXN0AwABeQAAAEADAAF4AAAAAAA=")
        val world = chunks.world("minecraft:chest", 2, chest)
        val blockEntity = requireNotNull(world.chunk.getBlockEntity(0, 64, 0))
        assertEquals(Key.minecraft("chest"), blockEntity.id)
        assertEquals(listOf(0, 64, 0), listOf(blockEntity.x, blockEntity.y, blockEntity.z))
        assertEquals("minecraft:chest[facing=north,type=single,waterlogged=false]", world.chunk.getBlockState(0, 64, 0).toString())
        assertNull(chunks.world("minecraft:chest", 2, null).chunk.getBlockEntity(0, 64, 0))
    }

    @Test
    fun `tile-entity NBT is copied tag by tag`() {
        val original =
            nbt {
                name("b").value(1.toByte())
                name("s").value(2.toShort())
                name("i").value(3)
                name("l").value(4L)
                name("f").value(5f)
                name("d").value(6.0)
                name("ba").value(byteArrayOf(7, 8))
                name("str").value("nine")
                name("ia").value(intArrayOf(10, 11))
                name("la").value(longArrayOf(12L))
                name("empty").beginList(0, TagType.END)
                endList()
                name("list").beginList(2)
                beginCompound()
                name("x").value(1)
                endCompound()
                beginCompound()
                endCompound()
                endList()
                name("nested").beginCompound()
                name("strings").beginList(1)
                value("a")
                endList()
                endCompound()
            }
        val copy = ByteArrayOutputStream()
        NBTWriter(copy).use { SyntheticChunkNbt.copyTag(NBTReader(original), it) }
        assertArrayEquals(original, copy.toByteArray())
    }

    @Test
    fun `light, biome and the empty neighbour chunks`() {
        val world = chunks.world("foo:bar", 0, null)
        val chunk = world.chunk as Chunk_1_7
        val light = chunk.getLightData(0, 64, 0, LightData(0, 0))
        assertEquals(listOf(15, 0), listOf(light.skyLight, light.blockLight))
        assertEquals(SyntheticChunkNbt.BIOME, chunk.getBiomeId(0, 0))
        assertEquals(BlockState.AIR, chunk.getBlockState(1, 64, 0))

        for ((x, z) in listOf(-1 to 0, 0 to -1, -1 to -1, 16 to 16)) {
            val neighbour = world.getChunkAtBlock(x, z) as Chunk_1_7
            assertTrue(neighbour.isGenerated)
            assertEquals(BlockState.AIR, neighbour.getBlockState(x, 64, z))
            assertEquals(15, neighbour.getLightData(x, 64, z, LightData(0, 0)).skyLight)
            assertEquals(SyntheticChunkNbt.BIOME, neighbour.getBiomeId(x, z))
        }
        assertSame(world.getChunk(-1, 0), chunks.world("foo:baz", 1, null).getChunk(-1, 0))
    }

    @Test
    fun `client index entries place worldMeta in the world`() {
        val index =
            ClientIndex.parse(
                JsonParser
                    .parseString(
                        """
                        {"format": 1, "image": {"width": 256, "height": 128, "viewSize": 128, "halfExtent": 1.0, "depthRange": 10.0,
                          "target": [0.5, 0.5, 0.5], "views": [{"name": "se", "yaw": 45.0, "pitch": 30.0, "x": 0},
                          {"name": "nw", "yaw": 225.0, "pitch": 30.0, "x": 128}]},
                         "states": [
                          {"key": "gregtech:gt.blockmachines[meta=1234]", "name": "gregtech:gt.blockmachines", "meta": 1234,
                           "worldMeta": 4, "mte": 1234, "path": "gregtech/gt.blockmachines/1234", "te": "${Base64.getEncoder().encodeToString(
                            nbt {},
                        )}"},
                          {"key": "minecraft:stone[meta=0]", "name": "minecraft:stone", "meta": 0, "path": "minecraft/stone/0", "te": null}
                         ]}
                        """.trimIndent(),
                    ).asJsonObject,
            )
        assertEquals(RasterSpec(), index.spec)
        val (machine, stone) = index.states
        assertEquals(listOf(1234, 4), listOf(machine.meta, machine.worldMeta))
        assertEquals("gregtech/gt.blockmachines/1234", machine.path)
        assertArrayEquals(nbt {}, machine.tileEntity)
        assertEquals(listOf(0, 0), listOf(stone.meta, stone.worldMeta))
        assertNull(stone.tileEntity)
        assertEquals("gregtech:gt.blockmachines[meta=4]", state(machine.name, machine.worldMeta))
    }
}
