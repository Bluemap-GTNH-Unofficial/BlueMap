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
@file:JvmName("FlatteningComparator")

package de.bluecolored.bluemap.gtnh.legacy

import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.resources.pack.PackVersion
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.World
import de.bluecolored.bluemap.core.world.mca.MCAWorld
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Vanilla flattening oracle (plan §4 "原版差分"): compares, position by position, the legacy reader + flattening on a
 * vanilla 1.7.10 world with BlueMap's stock reader on the same world upgraded by an official 1.13.2 server
 * (built by `src/test/python/oracle_world.py`).
 *
 * Usage: `FlatteningComparator <1.7.10-world> <1.13.2-world> [--box x1 y1 z1 x2 y2 z2] [--labels manifest.json] [--list n]`
 *
 * Properties without any visual effect that 1.13 derives from far-away blocks are not compared:
 * [IGNORED_PROPERTIES]. Exits with 1 on any mismatch.
 */
fun main(args: Array<String>) {
    val positional = ArrayList<String>()
    var box = intArrayOf(-128, 0, -128, 127, 12, 143)
    var labelsFile: Path? = null
    var list = 40
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--box" -> {
                box = IntArray(6) { args[i + 1 + it].toInt() }
                i += 7
            }

            "--labels" -> {
                labelsFile = Path.of(args[i + 1])
                i += 2
            }

            "--list" -> {
                list = args[i + 1].toInt()
                i += 2
            }

            else -> {
                positional += args[i++]
            }
        }
    }
    require(positional.size == 2) {
        "Usage: FlatteningComparator <1.7.10-world> <1.13.2-world> [--box x1 y1 z1 x2 y2 z2] [--labels manifest.json] [--list n]"
    }
    val labels: Map<String, String> =
        labelsFile?.let { file ->
            JsonParser
                .parseString(Files.readString(file))
                .asJsonObject
                .getAsJsonObject("labels")
                .entrySet()
                .associate { it.key to it.value.asString }
        } ?: emptyMap()

    val dataPack = DataPack(PackVersion(12, 0))
    val legacyFolder = Path.of(positional[0])
    val legacy = LegacyWorldLoader().loadWorld(legacyFolder, DataPack.DIMENSION_OVERWORLD, null, dataPack)
    val raw =
        LegacyWorldLoader(
            { registry, _ -> RegistryBlockStateMapper(registry) },
        ).loadWorld(legacyFolder, DataPack.DIMENSION_OVERWORLD, null, dataPack)
    val modern = MCAWorld.load(Path.of(positional[1]), DataPack.DIMENSION_OVERWORLD, null, dataPack)

    val result = FlatteningComparison.compare(legacy, raw, modern, box, labels)
    print(result.report(list))
    if (result.unexplained.isNotEmpty()) exitProcess(1)
}

object FlatteningComparison {
    /** `block id -> properties` not compared (no visual effect, derived from distant blocks in 1.13). */
    val IGNORED_PROPERTIES: Map<String, Set<String>> =
        buildMap {
            for (wood in listOf(
                "oak",
                "spruce",
                "birch",
                "jungle",
                "acacia",
                "dark_oak",
            )) {
                put("minecraft:${wood}_leaves", setOf("distance"))
            }
            put("minecraft:note_block", setOf("instrument"))
        }

    /** Where the legacy reader deliberately differs from a real 1.13.2 upgrade (1.13 is wrong or inconsistent there). */
    enum class Intentional(
        val description: String,
    ) {
        UNREGISTERED_META(
            "1.7 renders unused metas as the block, a 1.13.2 upgrade turns metas missing from its flattening table into air (or a default)",
        ),
        SNOWY_UNDER_SNOW_BLOCK(
            "grass/podzol/mycelium below a snow block render snowy in 1.7 (and after any 1.13 shape update), the chunk converter only checks snow layers",
        ),
        ATTACHED_STEM("1.7 bends grown stems to an adjacent fruit, the 1.13 upgrade only attaches stems on marked chunk edges"),
        BREWING_STAND_BOTTLES(
            "1.13 recomputes brewing stand bottles from the (here empty) inventory when ticking, 1.7 renders the meta bits",
        ),
        CARVED_PUMPKIN_FACE(
            "1.7 always shows the pumpkin face, the 1.13 converter turns carved pumpkins on grass/dirt into plain pumpkins",
        ),
        FLOWER_POT_ITEM_ID(
            "1.13.2's converter only reads item names from flower pots, 1.7 stores numeric item ids, so a real upgrade empties every pot (DataConverter maps them)",
        ),
    }

    data class Mismatch(
        val x: Int,
        val y: Int,
        val z: Int,
        val legacy: BlockState,
        val modern: BlockState,
        val label: String?,
        val intentional: Intentional?,
    ) {
        /** Groups mismatches by block ids and the differing properties. */
        val category: String
            get() {
                if (legacy.id != modern.id) return "${legacy.id.formatted} -> ${modern.id.formatted}"
                val keys = (legacy.properties.keys + modern.properties.keys).filter { legacy.properties[it] != modern.properties[it] }
                return "${legacy.id.formatted} ${keys.sorted().joinToString(
                    ",",
                ) { "$it: ${legacy.properties[it]} -> ${modern.properties[it]}" }}"
            }

        override fun toString() = "($x,$y,$z) legacy $legacy, 1.13.2 $modern${label?.let { " [$it]" } ?: ""}"
    }

    class Result(
        val positionsChecked: Long,
        val nonAirPositions: Long,
        val mismatches: List<Mismatch>,
    ) {
        val unexplained: List<Mismatch> get() = mismatches.filter { it.intentional == null }

        fun report(limit: Int): String =
            buildString {
                appendLine("positions checked: $positionsChecked, non-air (either world): $nonAirPositions")
                appendLine(
                    "mismatches: ${mismatches.size}, intentional: ${mismatches.size - unexplained.size}, unexplained: ${unexplained.size}",
                )
                for (kind in Intentional.entries) {
                    val count = mismatches.count { it.intentional == kind }
                    if (count > 0) appendLine("  intentional $kind: $count (${kind.description})")
                }
                unexplained.groupBy { it.category }.entries.sortedByDescending { it.value.size }.forEach { (category, list) ->
                    appendLine("  ${list.size}x $category   e.g. ${list.first()}")
                }
                if (unexplained.isNotEmpty()) {
                    appendLine("first ${minOf(limit, unexplained.size)} unexplained mismatches:")
                    unexplained.take(limit).forEach { appendLine("  $it") }
                }
            }
    }

    /** Compares [legacy] (flattened) with [modern]; [raw] is the same legacy world with identity states (id + meta). */
    fun compare(
        legacy: World,
        raw: World,
        modern: World,
        box: IntArray,
        labels: Map<String, String> = emptyMap(),
    ): Result {
        val (x1, y1, z1, x2, y2) = box
        val z2 = box[5]
        var checked = 0L
        var nonAir = 0L
        val mismatches = ArrayList<Mismatch>()
        for (x in minOf(x1, x2)..maxOf(x1, x2)) {
            for (z in minOf(z1, z2)..maxOf(z1, z2)) {
                val legacyChunk = legacy.getChunkAtBlock(x, z)
                val modernChunk = modern.getChunkAtBlock(x, z)
                for (y in minOf(y1, y2)..maxOf(y1, y2)) {
                    checked++
                    val a = legacyChunk.getBlockState(x, y, z)
                    val b = modernChunk.getBlockState(x, y, z)
                    if (a.isAir && b.isAir) continue
                    nonAir++
                    if (comparable(a) != comparable(b)) {
                        mismatches += Mismatch(x, y, z, a, b, labels["$x,$y,$z"], intentional(a, b, raw, x, y, z))
                    }
                }
            }
        }
        return Result(checked, nonAir, mismatches)
    }

    private fun intentional(
        legacy: BlockState,
        modern: BlockState,
        raw: World,
        x: Int,
        y: Int,
        z: Int,
    ): Intentional? {
        val id = legacy.id.formatted
        val rawState = raw.getChunkAtBlock(x, z).getBlockState(x, y, z)
        val above = raw.getChunkAtBlock(x, z).getBlockState(x, y + 1, z)
        return when {
            isUnregistered(rawState) -> {
                Intentional.UNREGISTERED_META
            }

            id in SNOWY_BLOCKS && modern.id == legacy.id && legacy.properties["snowy"] == "true" &&
                (above.id.formatted == "minecraft:snow" || isUnregistered(above)) -> {
                Intentional.SNOWY_UNDER_SNOW_BLOCK
            }

            id.startsWith("minecraft:attached_") && modern.id.formatted == id.replace("attached_", "") -> {
                Intentional.ATTACHED_STEM
            }

            id == "minecraft:brewing_stand" && modern.id == legacy.id -> {
                Intentional.BREWING_STAND_BOTTLES
            }

            id == "minecraft:carved_pumpkin" && modern.id.formatted == "minecraft:pumpkin" -> {
                Intentional.CARVED_PUMPKIN_FACE
            }

            id.startsWith("minecraft:potted_") && modern.id.formatted == "minecraft:flower_pot" -> {
                Intentional.FLOWER_POT_ITEM_ID
            }

            else -> {
                null
            }
        }
    }

    /** True for a vanilla id:meta (identity state) outside Mojang's flattening table. */
    private fun isUnregistered(raw: BlockState): Boolean {
        val meta = raw.properties[RegistryBlockStateMapper.META]?.toIntOrNull() ?: return false
        val name = raw.id.formatted
        return VanillaFlattening.states(name) != null && !VanillaFlattening.isRegistered(name, meta)
    }

    private val SNOWY_BLOCKS = setOf("minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium")

    private fun comparable(state: BlockState): BlockState {
        val ignored = IGNORED_PROPERTIES[state.id.formatted] ?: return state
        return BlockState(state.id, state.properties.filterKeys { it !in ignored })
    }
}
