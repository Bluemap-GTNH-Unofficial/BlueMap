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
@file:JvmName("ModelCoverage")

package de.bluecolored.bluemap.gtnh.legacy

import de.bluecolored.bluemap.core.resources.pack.PackVersion
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.World
import java.nio.file.Path

/**
 * Diagnostic: which block states of a 1.7.10 world (legacy reader + flattening) have no model in a resource pack,
 * i.e. would render nothing / the missing model in BlueMap.
 *
 * Usage: `ModelCoverage <world> <dimension> <resource-pack-root>...` (e.g. BlueMap's `minecraft-client-1.13.jar`
 * and `resourceExtensions.zip` from a CLI data folder; the first root wins, as in BlueMap).
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "Usage: ModelCoverage <world> <dimension> <resource-pack-root>..." }
    val resourcePack = ResourcePack(PackVersion(4, 0)) // 1.13
    resourcePack.loadResources(args.drop(2).map { Path.of(it) })
    val world = LegacyWorldLoader().loadWorld(Path.of(args[0]), Key.parse(args[1]), null, DataPack(PackVersion(12, 0)))
    print(ModelCoverageReport.of(world, resourcePack).report(30))
}

class ModelCoverageReport(
    private val counts: Map<BlockState, Long>,
    private val missing: Set<BlockState>,
) {
    fun report(limit: Int): String =
        buildString {
            val byNamespace = counts.keys.groupBy { it.id.namespace }.toSortedMap()
            for ((namespace, states) in byNamespace) {
                val missingStates = states.filter { it in missing }
                appendLine(
                    "$namespace: ${states.size} states (${states.sumOf { counts.getValue(it) }} blocks), " +
                        "missing ${missingStates.size} states (${missingStates.sumOf { counts.getValue(it) }} blocks)",
                )
                missingStates.sortedByDescending { counts.getValue(it) }.take(limit).forEach {
                    appendLine("  ${counts.getValue(it)}x $it")
                }
            }
        }

    companion object {
        fun of(
            world: World,
            resourcePack: ResourcePack,
        ): ModelCoverageReport {
            val counts = HashMap<BlockState, Long>()
            for (region in world.listRegions()) {
                for (cx in region.x * 32 until region.x * 32 + 32) {
                    for (cz in region.y * 32 until region.y * 32 + 32) {
                        val chunk = world.getChunk(cx, cz)
                        if (!chunk.isGenerated) continue
                        for (x in cx * 16 until cx * 16 + 16) {
                            for (z in cz * 16 until cz * 16 + 16) {
                                for (y in chunk.getMinY(x, z)..chunk.getMaxY(x, z)) {
                                    val state = chunk.getBlockState(x, y, z)
                                    if (!state.isAir) counts.merge(state, 1L, Long::plus)
                                }
                            }
                        }
                    }
                }
            }
            val missing = counts.keys.filterTo(HashSet()) { !hasModel(resourcePack, it) }
            return ModelCoverageReport(counts, missing)
        }

        /** True if the state resolves to at least one variant whose model exists (what BlueMap would render). */
        fun hasModel(
            resourcePack: ResourcePack,
            state: BlockState,
        ): Boolean {
            val resource = resourcePack.getBlockState(state) ?: return false
            var found = false
            resource.forEach(state, 0, 0, 0) { variant ->
                if (variant.model != ResourcePack.MISSING_BLOCK_MODEL &&
                    variant.model.getResource(resourcePack.models::get) != null
                ) {
                    found = true
                }
            }
            return found
        }
    }
}
