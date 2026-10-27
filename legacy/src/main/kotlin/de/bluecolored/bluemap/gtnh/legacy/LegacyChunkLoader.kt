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
import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.Chunk
import de.bluecolored.bluemap.core.world.DimensionType
import de.bluecolored.bluemap.core.world.mca.ChunkLoader
import de.bluecolored.bluemap.core.world.mca.data.LenientBlockEntityArrayDeserializer
import de.bluecolored.bluenbt.NBTReader
import de.bluecolored.bluenbt.TagType
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Loads Minecraft 1.7.10 Anvil chunks as written by vanilla, NotEnoughIDs and EndlessIDs 1.7 / ChunkAPI 0.8 (GTNH 2.9).
 *
 * Section block ids/metadata (index `y<<8 | z<<4 | x`, nibbles low-first):
 * - NotEnoughIDs `Blocks16`/`Data16` (big-endian shorts) take priority when present.
 * - Otherwise the chained EndlessIDs layers: id = `Blocks` | `Add`<<8 | `BlocksB2Hi`<<12 | `BlocksB3`<<16 and
 *   meta = `Data` | `Data1High`<<4 | `Data2`<<8, where each layer only counts if all lower layers are present
 *   (vanilla chunks only have `Blocks`/`Add`/`Data`).
 *
 * Biomes: `Biomes16v2` (little-endian shorts), then `Biomes16` (only the low bytes, as EndlessIDs reads it), then
 * vanilla `Biomes` (unsigned bytes). Unknown `Level` keys (e.g. `POSTEA`, `Entities`, `TileTicks`) are skipped.
 *
 * Block entities keep their 1.7 data needed by [LegacyBlockFixer]s and get modern ids, see [LegacyBlockEntities].
 * [neighbours] gives fixers access to blocks outside the chunk (normally a [WorldBlockAccess]).
 */
class LegacyChunkLoader
    @JvmOverloads
    constructor(
        private val blockStates: LegacyBlockStateMapper,
        private val biomes: LegacyBiomeMapper,
        dimensionType: DimensionType,
        private val neighbours: LegacyBlockAccess = LegacyBlockAccess.EMPTY,
    ) : ChunkLoader<Chunk> {
        private val skyLight = if (dimensionType.hasSkylight()) 15 else 0
        private val blockEntityDeserializer = LenientBlockEntityArrayDeserializer(LegacyBlockEntities.blueNbt())
        private val decoder = ThreadLocal.withInitial { SectionDecoder() }

        @Throws(IOException::class)
        override fun load(
            data: ByteArray,
            offset: Int,
            length: Int,
            compression: Compression,
        ): Chunk {
            try {
                compression.decompress(ByteArrayInputStream(data, offset, length)).use { input ->
                    return read(NBTReader(input))
                }
            } catch (ex: IOException) {
                throw IOException("Failed to parse 1.7 chunk-data: $ex", ex)
            } catch (ex: RuntimeException) {
                throw IOException("Failed to parse 1.7 chunk-data: $ex", ex)
            }
        }

        override fun emptyChunk(): Chunk = Chunk.EMPTY_CHUNK

        override fun erroredChunk(): Chunk = Chunk.ERRORED_CHUNK

        private fun read(reader: NBTReader): Chunk {
            var chunk: Chunk = Chunk.EMPTY_CHUNK
            reader.beginCompound()
            while (reader.hasNext()) {
                if (reader.name() == "Level" && reader.peek() == TagType.COMPOUND) {
                    chunk = readLevel(reader)
                } else {
                    reader.skip()
                }
            }
            reader.endCompound()
            return chunk
        }

        private fun readLevel(reader: NBTReader): Chunk {
            var chunkX = 0
            var chunkZ = 0
            var generated = false
            var inhabitedTime = 0L
            var sections: List<LegacySection> = emptyList()
            var biomes16v2: ByteArray? = null
            var biomes16: ByteArray? = null
            var biomesVanilla: ByteArray? = null
            var heightMap: IntArray? = null
            var blockEntities: Array<BlockEntity?> = emptyArray()

            reader.beginCompound()
            while (reader.hasNext()) {
                when (reader.name()) {
                    "xPos" -> {
                        chunkX = reader.nextNumber().toInt()
                    }

                    "zPos" -> {
                        chunkZ = reader.nextNumber().toInt()
                    }

                    "TerrainPopulated" -> {
                        generated = reader.nextNumber() != 0L
                    }

                    "InhabitedTime" -> {
                        inhabitedTime = reader.nextNumber()
                    }

                    "Sections" -> {
                        sections = readSections(reader)
                    }

                    "Biomes16v2" -> {
                        biomes16v2 = reader.nextBytes()
                    }

                    "Biomes16" -> {
                        biomes16 = reader.nextBytes()
                    }

                    "Biomes" -> {
                        biomesVanilla = reader.nextBytes()
                    }

                    "HeightMap" -> {
                        heightMap =
                            if (reader.peek() == TagType.INT_ARRAY) {
                                reader.nextIntArray()
                            } else {
                                reader.skip()
                                null
                            }
                    }

                    "TileEntities" -> {
                        blockEntities = blockEntityDeserializer.read(reader)
                    }

                    else -> {
                        reader.skip()
                    }
                }
            }
            reader.endCompound()

            var sectionMin = 0
            val sectionArray: Array<LegacySection?>
            if (sections.isEmpty()) {
                sectionArray = emptyArray()
            } else {
                sectionMin = sections.minOf { it.sectionY }
                val sectionMax = sections.maxOf { it.sectionY }
                sectionArray = arrayOfNulls(sectionMax - sectionMin + 1)
                for (section in sections) sectionArray[section.sectionY - sectionMin] = section
            }

            val blockEntityMap = HashMap<Long, BlockEntity>(blockEntities.size)
            for (be in blockEntities) {
                if (be == null) continue
                blockEntityMap[Chunk_1_7.blockEntityKey(be.x, be.y, be.z)] = be
            }

            return Chunk_1_7(
                chunkX = chunkX,
                chunkZ = chunkZ,
                generated = generated,
                hasLightData = sections.any { it.hasLightData },
                inhabitedTime = inhabitedTime,
                sections = sectionArray,
                sectionMin = sectionMin,
                biomeIds = decodeBiomes(biomes16v2, biomes16, biomesVanilla),
                heightMap = heightMap?.takeIf { it.size >= COLUMNS },
                blockEntities = blockEntityMap,
                biomes = biomes,
                skyLight = skyLight,
                neighbours = neighbours,
            )
        }

        private fun readSections(reader: NBTReader): List<LegacySection> {
            if (reader.peek() != TagType.LIST) {
                reader.skip()
                return emptyList()
            }
            val count = reader.beginList()
            val sections = ArrayList<LegacySection>(count)
            while (reader.hasNext()) {
                if (reader.peek() != TagType.COMPOUND) {
                    reader.skip()
                    continue
                }
                sections.add(readSection(reader))
            }
            reader.endList()
            return sections
        }

        private fun readSection(reader: NBTReader): LegacySection {
            val raw = RawSection()
            reader.beginCompound()
            while (reader.hasNext()) {
                when (reader.name()) {
                    "Y" -> raw.y = reader.nextNumber().toInt()
                    "Blocks" -> raw.blocks = reader.nextBytes()
                    "Add" -> raw.add = reader.nextBytes()
                    "BlocksB2Hi" -> raw.blocksB2Hi = reader.nextBytes()
                    "BlocksB3" -> raw.blocksB3 = reader.nextBytes()
                    "Blocks16" -> raw.blocks16 = reader.nextBytes()
                    "Data" -> raw.data = reader.nextBytes()
                    "Data1High" -> raw.data1High = reader.nextBytes()
                    "Data2" -> raw.data2 = reader.nextBytes()
                    "Data16" -> raw.data16 = reader.nextBytes()
                    "BlockLight" -> raw.blockLight = reader.nextBytes() ?: EMPTY_BYTES
                    "SkyLight" -> raw.skyLight = reader.nextBytes() ?: EMPTY_BYTES
                    else -> reader.skip()
                }
            }
            reader.endCompound()
            return decoder.get().decode(raw, blockStates)
        }

        internal class RawSection {
            var y = 0
            var blocks: ByteArray? = null
            var add: ByteArray? = null
            var blocksB2Hi: ByteArray? = null
            var blocksB3: ByteArray? = null
            var blocks16: ByteArray? = null
            var data: ByteArray? = null
            var data1High: ByteArray? = null
            var data2: ByteArray? = null
            var data16: ByteArray? = null
            var blockLight: ByteArray = EMPTY_BYTES
            var skyLight: ByteArray = EMPTY_BYTES

            fun blockId(i: Int): Int {
                blocks16?.let { return beShort(it, i) }
                var id = byte(blocks, i)
                val add = add ?: return id
                id = id or (nibble(add, i) shl 8)
                val b2Hi = blocksB2Hi ?: return id
                id = id or (nibble(b2Hi, i) shl 12)
                val b3 = blocksB3 ?: return id
                return id or (byte(b3, i) shl 16)
            }

            fun meta(i: Int): Int {
                data16?.let { return beShort(it, i) }
                var meta = nibble(data, i)
                val data1High = data1High ?: return meta
                meta = meta or (nibble(data1High, i) shl 4)
                val data2 = data2 ?: return meta
                return meta or (byte(data2, i) shl 8)
            }
        }

        /**
         * Resolves the raw arrays of a section into a palette + index array (reused per thread, no per-block allocation).
         */
        internal class SectionDecoder {
            private val keys = LongArray(TABLE_SIZE)
            private val values = IntArray(TABLE_SIZE)
            private val usedSlots = IntArray(BLOCKS)
            private val indices = CharArray(BLOCKS)
            private val palette = ArrayList<BlockState>()

            fun decode(
                raw: RawSection,
                mapper: LegacyBlockStateMapper,
            ): LegacySection {
                var used = 0
                var lastKey = -1L
                var lastIndex = 0
                try {
                    for (i in 0 until BLOCKS) {
                        val id = raw.blockId(i)
                        val key = (id.toLong() shl 16) or raw.meta(i).toLong()
                        if (key != lastKey) {
                            var slot = mix(key) and (TABLE_SIZE - 1)
                            while (true) {
                                val stored = keys[slot]
                                if (stored == key + 1) {
                                    lastIndex = values[slot]
                                    break
                                }
                                if (stored == 0L) {
                                    lastIndex = palette.size
                                    palette.add(mapper.map(id, (key and 0xFFFF).toInt()))
                                    keys[slot] = key + 1 // 0 marks an empty slot
                                    values[slot] = lastIndex
                                    usedSlots[used++] = slot
                                    break
                                }
                                slot = (slot + 1) and (TABLE_SIZE - 1)
                            }
                            lastKey = key
                        }
                        indices[i] = lastIndex.toChar()
                    }

                    val states = palette.toTypedArray()
                    var small: ByteArray? = null
                    var large: CharArray? = null
                    if (states.size in 2..256) {
                        small = ByteArray(BLOCKS) { indices[it].code.toByte() }
                    } else if (states.size > 256) {
                        large = indices.copyOf()
                    }
                    var fixers: Array<LegacyBlockFixer?>? = null
                    for (p in states.indices) {
                        val fixer = mapper.fixer(states[p]) ?: continue
                        if (fixers == null) fixers = arrayOfNulls(states.size)
                        fixers[p] = fixer
                    }
                    return LegacySection(raw.y, states, small, large, raw.blockLight, raw.skyLight, fixers)
                } finally {
                    for (s in 0 until used) keys[usedSlots[s]] = 0L
                    palette.clear()
                }
            }

            private fun mix(key: Long): Int {
                val h = key * -0x61c8864680b583ebL
                return (h xor (h ushr 32)).toInt()
            }

            private companion object {
                const val TABLE_SIZE = 8192 // > 2 * 4096, power of two
            }
        }

        companion object {
            private const val BLOCKS = 4096
            private const val COLUMNS = 256
            private val EMPTY_BYTES = ByteArray(0)

            private fun byte(
                array: ByteArray?,
                i: Int,
            ): Int = if (array != null && i < array.size) array[i].toInt() and 0xFF else 0

            private fun nibble(
                array: ByteArray?,
                i: Int,
            ): Int {
                if (array == null) return 0
                val b = i shr 1
                if (b >= array.size) return 0
                return (array[b].toInt() shr ((i and 1) shl 2)) and 0xF
            }

            private fun beShort(
                array: ByteArray,
                i: Int,
            ): Int {
                val b = i shl 1
                if (b + 1 >= array.size) return 0
                return ((array[b].toInt() and 0xFF) shl 8) or (array[b + 1].toInt() and 0xFF)
            }

            internal fun decodeBiomes(
                biomes16v2: ByteArray?,
                biomes16: ByteArray?,
                biomesVanilla: ByteArray?,
            ): CharArray? {
                if (biomes16v2 != null && biomes16v2.size >= COLUMNS * 2) {
                    return CharArray(COLUMNS) {
                        ((biomes16v2[it * 2].toInt() and 0xFF) or ((biomes16v2[it * 2 + 1].toInt() and 0xFF) shl 8)).toChar()
                    }
                }
                // EndlessIDs' byteToShortArrayLegacy effectively only keeps the low bytes (first 256 entries)
                if (biomes16 != null && biomes16.size >= COLUMNS) {
                    return CharArray(COLUMNS) { (biomes16[it].toInt() and 0xFF).toChar() }
                }
                if (biomesVanilla != null && biomesVanilla.size >= COLUMNS) {
                    return CharArray(COLUMNS) { (biomesVanilla[it].toInt() and 0xFF).toChar() }
                }
                return null
            }

            private fun NBTReader.nextNumber(): Long =
                when (peek()) {
                    TagType.BYTE -> {
                        nextByte().toLong()
                    }

                    TagType.SHORT -> {
                        nextShort().toLong()
                    }

                    TagType.INT -> {
                        nextInt().toLong()
                    }

                    TagType.LONG -> {
                        nextLong()
                    }

                    else -> {
                        skip()
                        0L
                    }
                }

            private fun NBTReader.nextBytes(): ByteArray? =
                when (peek()) {
                    TagType.BYTE_ARRAY -> {
                        nextByteArray()
                    }

                    else -> {
                        skip()
                        null
                    }
                }
        }
    }
