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
@file:JvmName("LegacyDumpVerifier")

package de.bluecolored.bluemap.gtnh.legacy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.Chunk
import de.bluecolored.bluemap.core.world.biome.Biome
import de.bluecolored.bluemap.core.world.mca.region.MCARegion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Ground-truth check of the legacy reader against a block dump of the live server (bmh harness `dump`):
 * NDJSON lines `{"x","y","z","id","name","meta","te":<object|null>}` with air omitted.
 *
 * Usage: `LegacyDumpVerifier <world-folder> <dimension-key> <dump.ndjson> [--box x1 y1 z1 x2 y2 z2]`
 * (default box: min/max of the dump coordinates). Exits with 1 on any mismatch.
 */
fun main(args: Array<String>) {
    val positional = ArrayList<String>()
    var box: DumpVerifier.Box? = null
    var i = 0
    while (i < args.size) {
        if (args[i] == "--box") {
            require(i + 6 < args.size) { "--box needs 6 coordinates" }
            val c = (1..6).map { args[i + it].toInt() }
            box = DumpVerifier.Box(c[0], c[1], c[2], c[3], c[4], c[5])
            i += 7
        } else {
            positional += args[i++]
        }
    }
    require(positional.size == 3) {
        "Usage: LegacyDumpVerifier <world-folder> <dimension-key> <dump.ndjson> [--box x1 y1 z1 x2 y2 z2]"
    }

    val entries = DumpVerifier.readDump(Path.of(positional[2]))
    val result = DumpVerifier.verify(Path.of(positional[0]), Key.parse(positional[1]), entries, box)
    print(result.report(50))
    if (result.mismatches.isNotEmpty()) exitProcess(1)
}

object DumpVerifier {
    data class Entry(
        val x: Int,
        val y: Int,
        val z: Int,
        val name: String,
        val meta: Int,
        val tileEntityId: String?,
        val hasTileEntity: Boolean,
    )

    data class Box(
        val x1: Int,
        val y1: Int,
        val z1: Int,
        val x2: Int,
        val y2: Int,
        val z2: Int,
    ) {
        val minX get() = minOf(x1, x2)
        val minY get() = minOf(y1, y2)
        val minZ get() = minOf(z1, z2)
        val maxX get() = maxOf(x1, x2)
        val maxY get() = maxOf(y1, y2)
        val maxZ get() = maxOf(z1, z2)

        val volume: Long get() = (maxX - minX + 1).toLong() * (maxY - minY + 1) * (maxZ - minZ + 1)

        override fun toString() = "($minX,$minY,$minZ)..($maxX,$maxY,$maxZ)"
    }

    enum class Kind {
        /** The dump entry and the reader disagree on the block state. */
        STATE,

        /** The reader has a non-air block inside the box that is missing in the dump. */
        MISSING_IN_DUMP,

        /** The dump has a tile entity and the reader has none, or the other way round. */
        TILE_ENTITY_PRESENCE,

        /** Both have a tile entity but the ids differ. */
        TILE_ENTITY_ID,
    }

    data class Mismatch(
        val kind: Kind,
        val x: Int,
        val y: Int,
        val z: Int,
        val message: String,
    ) {
        override fun toString() = "$kind at ($x,$y,$z): $message"
    }

    class Result(
        val box: Box,
        val dumpEntries: Int,
        val blocksChecked: Long,
        val readerNonAir: Long,
        val tileEntitiesInDump: Int,
        val mismatches: List<Mismatch>,
    ) {
        fun report(limit: Int): String =
            buildString {
                appendLine("box: $box (${box.volume} blocks)")
                appendLine("dump entries: $dumpEntries (tile entities: $tileEntitiesInDump)")
                appendLine("reader non-air blocks in box: $readerNonAir, positions checked: $blocksChecked")
                appendLine("mismatches: ${mismatches.size}")
                for (kind in Kind.entries) appendLine("  $kind: ${mismatches.count { it.kind == kind }}")
                if (mismatches.isNotEmpty()) {
                    appendLine("first ${minOf(limit, mismatches.size)} mismatches:")
                    mismatches.take(limit).forEach { appendLine("  $it") }
                }
            }
    }

    fun readDump(file: Path): List<Entry> =
        Files.readAllLines(file).filter { it.isNotBlank() }.map { line ->
            val json = JsonParser.parseString(line).asJsonObject
            val te = json.get("te")?.takeUnless { it.isJsonNull }
            Entry(
                x = json.get("x").asInt,
                y = json.get("y").asInt,
                z = json.get("z").asInt,
                name = json.get("name").asString,
                meta = json.get("meta").asInt,
                tileEntityId = (te as? JsonObject)?.get("id")?.takeUnless { it.isJsonNull }?.asString,
                hasTileEntity = te != null,
            )
        }

    fun verify(
        worldFolder: Path,
        dimension: Key,
        entries: List<Entry>,
        explicitBox: Box? = null,
    ): Result {
        require(explicitBox != null || entries.isNotEmpty()) { "Empty dump and no --box given" }
        val box =
            explicitBox ?: Box(
                entries.minOf { it.x },
                entries.minOf { it.y },
                entries.minOf { it.z },
                entries.maxOf { it.x },
                entries.maxOf { it.y },
                entries.maxOf { it.z },
            )

        val chunks = ChunkAccess(worldFolder, dimension)
        val mismatches = ArrayList<Mismatch>()
        val dumpPositions = HashMap<Long, Entry>(entries.size * 2)
        for (e in entries) dumpPositions[pack(e.x, e.y, e.z)] = e

        // every dump entry must match the reader
        for (e in entries) {
            val chunk = chunks.chunkAtBlock(e.x, e.z)
            val actual = chunk.getBlockState(e.x, e.y, e.z)
            val expected = BlockState(Key.parse(e.name), mapOf(RegistryBlockStateMapper.META to e.meta.toString()))
            if (actual != expected) {
                mismatches += Mismatch(Kind.STATE, e.x, e.y, e.z, "dump $expected, reader $actual")
            }
            val be = chunk.getBlockEntity(e.x, e.y, e.z)
            if (e.hasTileEntity != (be != null)) {
                mismatches +=
                    Mismatch(
                        Kind.TILE_ENTITY_PRESENCE,
                        e.x,
                        e.y,
                        e.z,
                        "dump te=${if (e.hasTileEntity) e.tileEntityId ?: "{}" else "null"}, reader ${be?.id ?: "none"}",
                    )
            } else if (be != null && e.tileEntityId != null && Key.parse(e.tileEntityId) != be.id) {
                mismatches += Mismatch(Kind.TILE_ENTITY_ID, e.x, e.y, e.z, "dump ${e.tileEntityId}, reader ${be.id}")
            }
        }

        // every non-air reader block (and every reader block entity) inside the box must be in the dump
        var checked = 0L
        var nonAir = 0L
        for (x in box.minX..box.maxX) {
            for (z in box.minZ..box.maxZ) {
                val chunk = chunks.chunkAtBlock(x, z)
                for (y in box.minY..box.maxY) {
                    checked++
                    val state = chunk.getBlockState(x, y, z)
                    val inDump = dumpPositions.containsKey(pack(x, y, z))
                    if (!state.isAir) {
                        nonAir++
                        if (!inDump) mismatches += Mismatch(Kind.MISSING_IN_DUMP, x, y, z, "reader $state")
                    } else if (!inDump && chunk.getBlockEntity(x, y, z) != null) {
                        mismatches +=
                            Mismatch(Kind.TILE_ENTITY_PRESENCE, x, y, z, "reader ${chunk.getBlockEntity(x, y, z)!!.id} on air, dump none")
                    }
                }
            }
        }

        return Result(box, entries.size, checked, nonAir, entries.count { it.hasTileEntity }, mismatches)
    }

    private fun pack(
        x: Int,
        y: Int,
        z: Int,
    ): Long = (x.toLong() and 0x3FFFFFF shl 38) or (z.toLong() and 0x3FFFFFF shl 12) or (y.toLong() and 0xFFF)

    /** Direct chunk access through the legacy loader (no DataPack needed). */
    private class ChunkAccess(
        worldFolder: Path,
        dimension: Key,
    ) {
        private val regionFolder = LegacyDimensions.resolveFolder(worldFolder, dimension).resolve("region")
        private val loader =
            LegacyChunkLoader(
                RegistryBlockStateMapper(FmlBlockRegistry.load(worldFolder.resolve("level.dat"))),
                { Biome.DEFAULT },
                LegacyDimensions.dimensionType(dimension),
            )
        private val chunks = HashMap<Long, Chunk>()

        fun chunkAtBlock(
            x: Int,
            z: Int,
        ): Chunk {
            val cx = x shr 4
            val cz = z shr 4
            return chunks.getOrPut((cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)) {
                MCARegion(loader, regionFolder.resolve(MCARegion.getRegionFileName(cx shr 5, cz shr 5))).loadChunk(cx, cz)
            }
        }
    }
}
