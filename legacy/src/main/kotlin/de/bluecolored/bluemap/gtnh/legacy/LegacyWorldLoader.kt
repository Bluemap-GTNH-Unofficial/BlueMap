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

import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.World
import de.bluecolored.bluemap.core.world.WorldLoader
import de.bluecolored.bluemap.core.world.mca.MCAWorld
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads a Forge 1.7.10 world (one dimension) as an [MCAWorld] backed by a [LegacyChunkLoader].
 *
 * The block and biome mappings are pluggable; by default vanilla blocks are flattened to 1.13 states
 * ([FlatteningBlockStateMapper]) and everything else keeps its `modid:name[meta]` identity.
 * Worlds without FML registry data (plain vanilla 1.7.10) use the fixed vanilla ids.
 */
class LegacyWorldLoader
    @JvmOverloads
    constructor(
        private val blockStateMapperFactory: (FmlBlockRegistry) -> LegacyBlockStateMapper = { FlatteningBlockStateMapper(it) },
        private val biomeMapperFactory: (DataPack) -> LegacyBiomeMapper = { dataPack -> VanillaLegacyBiomeMapper(dataPack::getBiome) },
    ) : WorldLoader {
        @Throws(IOException::class)
        override fun loadWorld(
            path: Path,
            dimension: Key,
            dimensionTypeKey: Key?,
            dataPack: DataPack,
        ): World {
            val dimensionType =
                dimensionTypeKey?.let { key ->
                    dataPack.getDimensionType(key) ?: null.also {
                        Logger.global.logWarning("Unknown dimension-type '$key' for world '$path' ($dimension), using legacy default.")
                    }
                } ?: LegacyDimensions.dimensionType(dimension)
            val dimensionFolder = LegacyDimensions.resolveFolder(path, dimension)

            val blockStates = blockStateMapperFactory(loadRegistry(path))
            val biomes = biomeMapperFactory(dataPack)

            return MCAWorld(path, dimension, dimensionType, dimensionFolder, dataPack) { world ->
                LegacyChunkLoader(blockStates, biomes, dimensionType, WorldBlockAccess(world))
            }
        }

        private fun loadRegistry(worldFolder: Path): FmlBlockRegistry {
            val levelDat = worldFolder.resolve("level.dat")
            if (!Files.exists(levelDat)) {
                Logger.global.logWarning("No level.dat found in '$worldFolder', assuming vanilla 1.7.10 block ids.")
                return FmlBlockRegistry.VANILLA_1_7_10
            }
            val registry = FmlBlockRegistry.load(levelDat)
            if (registry.size == 0) {
                Logger.global.logInfo("'$levelDat' contains no FML block registry (FML.ItemData), assuming vanilla 1.7.10 block ids.")
                return FmlBlockRegistry.VANILLA_1_7_10
            }
            return registry
        }
    }
