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
import de.bluecolored.bluenbt.NBTReader
import de.bluecolored.bluenbt.TagType
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The numeric block-id -> registry-name table of a Forge 1.7.10 world, read from `level.dat` -> `FML`.
 *
 * `FML.ItemData` holds `{K: String, V: Int}` entries for blocks (`K` prefixed with `\u0001`) and
 * items (prefixed with `\u0002`). Names are kept verbatim (case preserved, e.g. `gregtech:gt.blockcasings2`).
 * `FML.BlockAliases` (`{K: from, V: to}`) redirect a persisted name to its current registry name.
 * Item names are only needed for block entities that store item ids (flower pots).
 */
class FmlBlockRegistry
    @JvmOverloads
    constructor(
        names: Map<Int, String>,
        private val itemNames: Map<Int, String> = emptyMap(),
    ) {
        private val names: Array<String?>

        init {
            val maxId = names.keys.maxOrNull() ?: -1
            require(maxId < MAX_BLOCK_ID_LIMIT) { "Block id $maxId exceeds $MAX_BLOCK_ID_LIMIT" }
            this.names = arrayOfNulls(maxId + 1)
            for ((id, name) in names) {
                if (id >= 0) this.names[id] = name
            }
        }

        /** The number of registered block ids. */
        val size: Int get() = names.count { it != null }

        /** The highest registered block id, or -1 if the registry is empty. */
        val maxId: Int get() = names.indexOfLast { it != null }

        /** The registry name (`modid:name`) of a numeric block id, or null if the id is not registered. */
        fun name(id: Int): String? = if (id >= 0 && id < names.size) names[id] else null

        /** The registry name of a numeric item id, or null if the id is not registered. */
        fun itemName(id: Int): String? = itemNames[id]

        companion object {
            const val BLOCK_PREFIX: Char = '\u0001'
            const val ITEM_PREFIX: Char = '\u0002'

            /** Sanity bound: EndlessIDs could at most use 24-bit block ids. */
            private const val MAX_BLOCK_ID_LIMIT = 1 shl 24

            @JvmField
            val EMPTY: FmlBlockRegistry = FmlBlockRegistry(emptyMap())

            /**
             * The fixed ids of vanilla Minecraft 1.7.10 (worlds without FML data): blocks 0..175, and the item ids
             * of block items, which equal their block ids.
             */
            @JvmStatic
            val VANILLA_1_7_10: FmlBlockRegistry by lazy {
                val blocks = VanillaFlattening.vanillaBlockNames()
                FmlBlockRegistry(blocks, blocks)
            }

            /** Reads the registry from a (gzipped) `level.dat`. */
            @JvmStatic
            @Throws(IOException::class)
            fun load(levelDat: Path): FmlBlockRegistry = Compression.GZIP.decompress(Files.newInputStream(levelDat)).use { read(it) }

            /** Reads the registry from an uncompressed `level.dat` NBT stream. */
            @JvmStatic
            @Throws(IOException::class)
            fun read(input: InputStream): FmlBlockRegistry {
                val reader = NBTReader(input)
                val blocks = HashMap<Int, String>(8192)
                val items = HashMap<Int, String>(16384)
                val aliases = HashMap<String, String>()

                reader.beginCompound()
                while (reader.hasNext()) {
                    if (reader.name() != "FML" || reader.peek() != TagType.COMPOUND) {
                        reader.skip()
                        continue
                    }
                    reader.beginCompound()
                    while (reader.hasNext()) {
                        when (reader.name()) {
                            "ItemData" -> {
                                reader.readKeyValueList { k, v ->
                                    if (k.length > 1 && v is Int) {
                                        when (k[0]) {
                                            BLOCK_PREFIX -> blocks[v] = k.substring(1)
                                            ITEM_PREFIX -> items[v] = k.substring(1)
                                        }
                                    }
                                }
                            }

                            "BlockAliases" -> {
                                reader.readKeyValueList { k, v ->
                                    if (v is String) aliases[k] = v
                                }
                            }

                            else -> {
                                reader.skip()
                            }
                        }
                    }
                    reader.endCompound()
                }
                reader.endCompound()

                if (aliases.isNotEmpty()) {
                    blocks.replaceAll { _, name -> resolveAlias(name, aliases) }
                }
                return FmlBlockRegistry(blocks, items)
            }

            private fun resolveAlias(
                name: String,
                aliases: Map<String, String>,
            ): String {
                var current = name
                // follow alias chains, bounded to protect against cycles
                repeat(aliases.size) {
                    current = aliases[current] ?: return current
                }
                return current
            }

            /** Reads a list of `{K: String, V: Int|String}` compounds. */
            private inline fun NBTReader.readKeyValueList(consumer: (String, Any?) -> Unit) {
                if (peek() != TagType.LIST) {
                    skip()
                    return
                }
                beginList()
                while (hasNext()) {
                    if (peek() != TagType.COMPOUND) {
                        skip()
                        continue
                    }
                    var key: String? = null
                    var value: Any? = null
                    beginCompound()
                    while (hasNext()) {
                        when (name()) {
                            "K" -> {
                                key = nextString()
                            }

                            "V" -> {
                                value =
                                    when (peek()) {
                                        TagType.STRING -> {
                                            nextString()
                                        }

                                        TagType.INT -> {
                                            nextInt()
                                        }

                                        TagType.SHORT -> {
                                            nextShort().toInt()
                                        }

                                        TagType.BYTE -> {
                                            nextByte().toInt()
                                        }

                                        else -> {
                                            skip()
                                            null
                                        }
                                    }
                            }

                            else -> {
                                skip()
                            }
                        }
                    }
                    endCompound()
                    if (key != null) consumer(key, value)
                }
                endList()
            }
        }
    }
