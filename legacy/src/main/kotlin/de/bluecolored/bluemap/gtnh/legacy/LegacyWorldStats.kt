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
@file:JvmName("LegacyWorldStats")

package de.bluecolored.bluemap.gtnh.legacy

import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.Chunk
import de.bluecolored.bluemap.core.world.ChunkConsumer
import de.bluecolored.bluemap.core.world.biome.Biome
import de.bluecolored.bluemap.core.world.mca.region.MCARegion
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Diagnostic: reads every chunk of one dimension of a 1.7.10 world with the legacy reader and prints
 * chunk/error counts plus block-state frequencies by namespace and by state.
 *
 * Usage: `LegacyWorldStats <world-folder> [dimension-key] [top-n]`
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: LegacyWorldStats <world-folder> [dimension-key] [top-n]" }
    val world = Path.of(args[0])
    val dimension = Key.parse(args.getOrElse(1) { "minecraft:overworld" })
    val topN = args.getOrNull(2)?.toInt() ?: 20

    val registry = FmlBlockRegistry.load(world.resolve("level.dat"))
    val loader =
        LegacyChunkLoader(
            RegistryBlockStateMapper(registry),
            { Biome.DEFAULT },
            LegacyDimensions.dimensionType(dimension),
        )
    val regionFolder = LegacyDimensions.resolveFolder(world, dimension).resolve("region")

    val byState = HashMap<BlockState, Long>()
    var chunks = 0
    var generated = 0
    var errors = 0
    val regionFiles =
        Files.list(regionFolder).use { files ->
            files.filter { MCARegion.FILE_PATTERN.matcher(it.name).matches() }.sorted().toList()
        }
    for (file in regionFiles) {
        MCARegion(loader, file).iterateAllChunks(
            object : ChunkConsumer<Chunk> {
                override fun accept(
                    chunkX: Int,
                    chunkZ: Int,
                    chunk: Chunk,
                ) {
                    chunks++
                    if (chunk.isGenerated) generated++
                    for (y in chunk.getMinY(0, 0)..chunk.getMaxY(0, 0)) {
                        for (z in 0 until 16) {
                            for (x in 0 until 16) {
                                val state = chunk.getBlockState(x, y, z)
                                if (!state.isAir) byState.merge(state, 1L, Long::plus)
                            }
                        }
                    }
                }

                override fun fail(
                    chunkX: Int,
                    chunkZ: Int,
                    exception: IOException,
                ) {
                    errors++
                    System.err.println("Chunk $chunkX,$chunkZ failed: $exception")
                }
            },
        )
    }

    val byNamespace = HashMap<String, Long>()
    val byBlock = HashMap<Key, Long>()
    for ((state, count) in byState) {
        byNamespace.merge(state.id.namespace, count, Long::plus)
        byBlock.merge(state.id, count, Long::plus)
    }

    println("registry: ${registry.size} blocks, max id ${registry.maxId}")
    println("regions: ${regionFiles.size}, chunks: $chunks, generated: $generated, errors: $errors")
    println("distinct states: ${byState.size}, distinct blocks: ${byBlock.size}, non-air blocks: ${byState.values.sum()}")
    println("\nby namespace:")
    byNamespace.entries.sortedByDescending { it.value }.forEach { println("  %12d  %s".format(it.value, it.key)) }
    println("\ntop $topN block states:")
    byState.entries
        .sortedByDescending { it.value }
        .take(topN)
        .forEach { println("  %12d  %s".format(it.value, it.key)) }
}
