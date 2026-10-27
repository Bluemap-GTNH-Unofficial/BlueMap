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

import de.bluecolored.bluemap.core.storage.compression.Compression
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.biome.Biome
import de.bluecolored.bluenbt.NBTWriter
import de.bluecolored.bluenbt.TagType
import java.io.ByteArrayOutputStream

/**
 * A small in-memory 1.7.10 world for tests: blocks and block entities are written as real 1.7 chunk NBT
 * (vanilla `Blocks`/`Data` sections) and read back through [LegacyChunkLoader], with neighbour access across chunks.
 */
class TestWorld(
    private val registry: FmlBlockRegistry = FmlBlockRegistry.VANILLA_1_7_10,
    mapper: LegacyBlockStateMapper = FlatteningBlockStateMapper(registry),
) {
    private class Block(
        val id: Int,
        val meta: Int,
    )

    private val blocks = HashMap<Triple<Int, Int, Int>, Block>()
    private val tileEntities = HashMap<Triple<Int, Int, Int>, NBTWriter.() -> Unit>()
    private val chunks = HashMap<Pair<Int, Int>, Chunk_1_7>()

    private val access =
        object : LegacyBlockAccess {
            override fun baseState(
                x: Int,
                y: Int,
                z: Int,
            ): BlockState = chunk(x shr 4, z shr 4).getBaseBlockState(x, y, z)

            override fun state(
                x: Int,
                y: Int,
                z: Int,
            ): BlockState = chunk(x shr 4, z shr 4).getBlockState(x, y, z)
        }

    private val loader = LegacyChunkLoader(mapper, { Biome.DEFAULT }, LegacyDimensions.OVERWORLD_TYPE, access)

    /** Places a block by 1.7.10 registry name (`minecraft:` may be omitted). */
    fun set(
        x: Int,
        y: Int,
        z: Int,
        name: String,
        meta: Int = 0,
    ): TestWorld {
        val full = if (':' in name) name else "minecraft:$name"
        val id = (0..registry.maxId).first { registry.name(it) == full }
        blocks[Triple(x, y, z)] = Block(id, meta)
        chunks.clear()
        return this
    }

    /** Adds a block entity; [fields] writes the compound content besides `id`/`x`/`y`/`z`. */
    fun tileEntity(
        x: Int,
        y: Int,
        z: Int,
        id: String,
        fields: NBTWriter.() -> Unit = {},
    ): TestWorld {
        tileEntities[Triple(x, y, z)] = {
            name("id").value(id)
            name("x").value(x)
            name("y").value(y)
            name("z").value(z)
            fields()
        }
        chunks.clear()
        return this
    }

    fun state(
        x: Int,
        y: Int,
        z: Int,
    ): BlockState = access.state(x, y, z)

    fun chunk(
        cx: Int,
        cz: Int,
    ): Chunk_1_7 =
        chunks.getOrPut(cx to cz) {
            val data = chunkNbt(cx, cz)
            loader.load(data, 0, data.size, Compression.NONE) as Chunk_1_7
        }

    private fun chunkNbt(
        cx: Int,
        cz: Int,
    ): ByteArray {
        val inChunk = blocks.filterKeys { (x, _, z) -> x shr 4 == cx && z shr 4 == cz }
        val sectionYs = inChunk.keys.map { it.second shr 4 }.toSortedSet()
        val out = ByteArrayOutputStream()
        NBTWriter(out).use { w ->
            w.beginCompound()
            w.name("Level").beginCompound()
            w.name("xPos").value(cx)
            w.name("zPos").value(cz)
            w.name("TerrainPopulated").value(1.toByte())
            w.name("Sections").beginList(sectionYs.size, TagType.COMPOUND)
            for (sy in sectionYs) {
                val ids = ByteArray(4096)
                val data = ByteArray(2048)
                for ((pos, block) in inChunk) {
                    val (x, y, z) = pos
                    if (y shr 4 != sy) continue
                    val i = (y and 15) shl 8 or ((z and 15) shl 4) or (x and 15)
                    ids[i] = block.id.toByte()
                    data[i shr 1] = (data[i shr 1].toInt() or ((block.meta and 15) shl ((i and 1) * 4))).toByte()
                }
                w.beginCompound()
                w.name("Y").value(sy.toByte())
                w.name("Blocks").value(ids)
                w.name("Data").value(data)
                w.name("BlockLight").value(ByteArray(2048))
                w.name("SkyLight").value(ByteArray(2048) { -1 })
                w.endCompound()
            }
            w.endList()
            val tes = tileEntities.filterKeys { (x, _, z) -> x shr 4 == cx && z shr 4 == cz }
            w.name("TileEntities").beginList(tes.size, TagType.COMPOUND)
            for (te in tes.values) {
                w.beginCompound()
                w.te()
                w.endCompound()
            }
            w.endList()
            w.endCompound()
            w.endCompound()
        }
        return out.toByteArray()
    }
}
