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

import com.flowpowered.math.vector.Vector2i
import de.bluecolored.bluemap.core.storage.compression.Compression
import de.bluecolored.bluemap.core.util.Grid
import de.bluecolored.bluemap.core.world.Chunk
import de.bluecolored.bluemap.core.world.ChunkConsumer
import de.bluecolored.bluemap.core.world.DimensionType
import de.bluecolored.bluemap.core.world.Entity
import de.bluecolored.bluemap.core.world.Region
import de.bluecolored.bluemap.core.world.World
import de.bluecolored.bluemap.gtnh.legacy.BlockShapes
import de.bluecolored.bluemap.gtnh.legacy.FlatteningBlockStateMapper
import de.bluecolored.bluemap.gtnh.legacy.FmlBlockRegistry
import de.bluecolored.bluemap.gtnh.legacy.LegacyBiomeMapper
import de.bluecolored.bluemap.gtnh.legacy.LegacyBlockAccess
import de.bluecolored.bluemap.gtnh.legacy.LegacyBlockStateMapper
import de.bluecolored.bluemap.gtnh.legacy.LegacyChunkLoader
import de.bluecolored.bluemap.gtnh.legacy.RegistryBlockStateMapper
import de.bluecolored.bluemap.gtnh.legacy.VanillaFlattening
import de.bluecolored.bluemap.gtnh.legacy.WorldBlockAccess
import de.bluecolored.bluenbt.NBTReader
import de.bluecolored.bluenbt.NBTWriter
import de.bluecolored.bluenbt.TagType
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Uncompressed 1.7.10 Anvil chunk NBT of the state oracle (contract): block id [BLOCK_ID] at index 0 of section
 * [SECTION_Y] (world `(0, 64, 0)`), its meta in `Data` plus the EndlessIDs layers `Data1High`/`Data2` above 15, sky
 * light 15, block light 0, biome [BIOME] everywhere and the client's tile-entity NBT.
 */
object SyntheticChunkNbt {
    const val BLOCK_ID: Int = 1
    const val SECTION_Y: Int = 4
    const val BIOME: Int = 1
    const val MAX_META: Int = 0xFFFF

    private const val BLOCKS = 4096
    private const val NIBBLES = 2048
    private const val COLUMNS = 256

    /**
     * Chunk (0, 0) holding the state. [tileEntity] is an uncompressed NBT file whose root compound becomes the
     * only `TileEntities` entry (written by `CompressedStreamTools.write`, coordinates already `0, 64, 0`).
     */
    fun stateChunk(
        meta: Int,
        tileEntity: ByteArray?,
    ): ByteArray {
        require(meta in 0..MAX_META) { "Meta $meta is outside 0..$MAX_META" }
        return chunk(0, 0) {
            name("Sections").beginList(1, TagType.COMPOUND)
            beginCompound()
            name("Y").value(SECTION_Y.toByte())
            name("Blocks").value(ByteArray(BLOCKS).also { it[0] = BLOCK_ID.toByte() })
            name("Data").value(ByteArray(NIBBLES).also { it[0] = (meta and 0xF).toByte() })
            if (meta > 0xF) {
                name("Data1High").value(ByteArray(NIBBLES).also { it[0] = (meta shr 4 and 0xF).toByte() })
                name("Data2").value(ByteArray(BLOCKS).also { it[0] = (meta shr 8 and 0xFF).toByte() })
            }
            name("BlockLight").value(ByteArray(NIBBLES))
            name("SkyLight").value(ByteArray(NIBBLES) { -1 })
            endCompound()
            endList()

            name("TileEntities").beginList(if (tileEntity == null) 0 else 1, TagType.COMPOUND)
            if (tileEntity != null) {
                val reader = NBTReader(tileEntity)
                require(reader.peek() == TagType.COMPOUND) { "Tile-entity NBT root is ${reader.peek()}, not a compound" }
                copyTag(reader, this)
            }
            endList()
        }
    }

    /** A generated chunk without sections: air, sky light 15 (above the absent sections), biome [BIOME]. */
    fun emptyChunk(
        chunkX: Int,
        chunkZ: Int,
    ): ByteArray =
        chunk(chunkX, chunkZ) {
            name("Sections").beginList(0, TagType.COMPOUND)
            endList()
            name("TileEntities").beginList(0, TagType.COMPOUND)
            endList()
        }

    private fun chunk(
        chunkX: Int,
        chunkZ: Int,
        content: NBTWriter.() -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        NBTWriter(out).use { writer ->
            writer.beginCompound()
            writer.name("Level").beginCompound()
            writer.name("xPos").value(chunkX)
            writer.name("zPos").value(chunkZ)
            writer.name("TerrainPopulated").value(1.toByte())
            writer.name("Biomes").value(ByteArray(COLUMNS) { BIOME.toByte() })
            writer.content()
            writer.endCompound()
            writer.endCompound()
        }
        return out.toByteArray()
    }

    /** Copies the next tag of [reader] (its value; the caller has written the name if inside a compound). */
    fun copyTag(
        reader: NBTReader,
        writer: NBTWriter,
    ) {
        when (val type = reader.peek()) {
            TagType.BYTE -> {
                writer.value(reader.nextByte())
            }

            TagType.SHORT -> {
                writer.value(reader.nextShort())
            }

            TagType.INT -> {
                writer.value(reader.nextInt())
            }

            TagType.LONG -> {
                writer.value(reader.nextLong())
            }

            TagType.FLOAT -> {
                writer.value(reader.nextFloat())
            }

            TagType.DOUBLE -> {
                writer.value(reader.nextDouble())
            }

            TagType.BYTE_ARRAY -> {
                writer.value(reader.nextByteArray())
            }

            TagType.STRING -> {
                writer.value(reader.nextString())
            }

            TagType.INT_ARRAY -> {
                writer.value(reader.nextIntArray())
            }

            TagType.LONG_ARRAY -> {
                writer.value(reader.nextLongArray())
            }

            TagType.LIST -> {
                val size = reader.beginList()
                if (size == 0) writer.beginList(0, TagType.END) else writer.beginList(size)
                while (reader.hasNext()) copyTag(reader, writer)
                reader.endList()
                writer.endList()
            }

            TagType.COMPOUND -> {
                reader.beginCompound()
                writer.beginCompound()
                while (reader.hasNext()) {
                    writer.name(reader.name())
                    copyTag(reader, writer)
                }
                reader.endCompound()
                writer.endCompound()
            }

            else -> {
                throw IllegalArgumentException("Unexpected NBT tag $type")
            }
        }
    }
}

/**
 * Builds the synthetic world of one block state and reads it through the real [LegacyChunkLoader], with the
 * block mapping `LegacyWorldLoader` uses ([FlatteningBlockStateMapper] over the FML registry `{1: name}` and the
 * modded [shapes]). Item ids (flower-pot contents) resolve like vanilla 1.7.10, whose block items share their
 * block ids.
 *
 * All other chunks are generated but empty 1.7 chunks (no sections, biome [SyntheticChunkNbt.BIOME]) rather than
 * `Chunk.EMPTY_CHUNK`, whose `Biome.DEFAULT` would otherwise enter BlueMap's 5x5 biome blend of grass, foliage and
 * water tints at the chunk corner `(0, 0)`.
 */
class StateChunks(
    private val shapes: BlockShapes,
    private val biomes: LegacyBiomeMapper,
    private val dimensionType: DimensionType,
) {
    private val emptyMapper = RegistryBlockStateMapper(FmlBlockRegistry.EMPTY)
    private val emptyChunks = ConcurrentHashMap<Long, Chunk>()

    /** The world holding [name] with [meta] (the block meta stored in the world) and the [tileEntity] NBT. */
    fun world(
        name: String,
        meta: Int,
        tileEntity: ByteArray?,
    ): StateWorld {
        val registry = FmlBlockRegistry(mapOf(SyntheticChunkNbt.BLOCK_ID to name), VANILLA_ITEMS)
        val world = StateWorld(dimensionType, ::emptyChunk)
        world.chunk =
            load(
                FlatteningBlockStateMapper(registry, shapes = shapes),
                SyntheticChunkNbt.stateChunk(meta, tileEntity),
                WorldBlockAccess(world),
            )
        return world
    }

    private fun emptyChunk(
        chunkX: Int,
        chunkZ: Int,
    ): Chunk =
        emptyChunks.computeIfAbsent((chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)) {
            load(emptyMapper, SyntheticChunkNbt.emptyChunk(chunkX, chunkZ), LegacyBlockAccess.EMPTY)
        }

    private fun load(
        mapper: LegacyBlockStateMapper,
        data: ByteArray,
        neighbours: LegacyBlockAccess,
    ): Chunk = LegacyChunkLoader(mapper, biomes, dimensionType, neighbours).load(data, 0, data.size, Compression.NONE)

    private companion object {
        val VANILLA_ITEMS: Map<Int, String> = VanillaFlattening.vanillaBlockNames()
    }
}

/** A world of one block state at `(0, 64, 0)` in chunk (0, 0); every other chunk comes from [emptyChunk]. */
class StateWorld(
    private val dimensionType: DimensionType,
    private val emptyChunk: (Int, Int) -> Chunk,
) : World {
    /** Chunk (0, 0), holding the state. */
    lateinit var chunk: Chunk

    override fun getId(): String = "bluemap-oracle"

    override fun getDimensionType(): DimensionType = dimensionType

    override fun getChunkGrid(): Grid = CHUNK_GRID

    override fun getRegionGrid(): Grid = REGION_GRID

    override fun getChunkAtBlock(
        x: Int,
        z: Int,
    ): Chunk = getChunk(x shr 4, z shr 4)

    override fun getChunk(
        x: Int,
        z: Int,
    ): Chunk = if (x == 0 && z == 0) chunk else emptyChunk(x, z)

    override fun getRegion(
        x: Int,
        z: Int,
    ): Region<Chunk> =
        object : Region<Chunk> {
            override fun iterateAllChunks(consumer: ChunkConsumer<Chunk>) {
                if (x == 0 && z == 0 && consumer.filter(0, 0, 0)) consumer.accept(0, 0, chunk)
            }

            override fun emptyChunk(): Chunk = Chunk.EMPTY_CHUNK

            override fun exists(): Boolean = x == 0 && z == 0
        }

    override fun listRegions(): Collection<Vector2i> = listOf(Vector2i.ZERO)

    override fun preloadRegionChunks(
        x: Int,
        z: Int,
        chunkFilter: Predicate<Vector2i>,
    ) {}

    override fun invalidateChunkCache() {}

    override fun invalidateChunkCache(
        x: Int,
        z: Int,
    ) {}

    override fun iterateEntities(
        minX: Int,
        minZ: Int,
        maxX: Int,
        maxZ: Int,
        entityConsumer: Consumer<Entity>,
    ) {}

    private companion object {
        val CHUNK_GRID = Grid(16)
        val REGION_GRID: Grid = Grid(32).multiply(CHUNK_GRID)
    }
}
