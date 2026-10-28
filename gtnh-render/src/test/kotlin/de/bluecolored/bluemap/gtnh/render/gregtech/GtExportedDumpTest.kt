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
package de.bluecolored.bluemap.gtnh.render.gregtech

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.render.QuadModel
import de.bluecolored.bluemap.gtnh.render.gregtech.GtTestTileEntities.machine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Checks the face rules against GT's own results recorded in a real exporter pack
 * (`<workspace>/out/exporter/bluemap-gtnh-resources-*.zip`, or `-Dgtnh.exporterPack=<zip>`); skipped without one.
 */
class GtExportedDumpTest {
    private val pack: Path? =
        System.getProperty("gtnh.exporterPack")?.let { Paths.get(it) }
            ?: Paths.get("../../out/exporter").takeIf { it.isDirectory() }?.let { dir ->
                Files.list(dir).use { files ->
                    files
                        .filter {
                            it.name.startsWith(
                                "bluemap-gtnh-resources-",
                            ) && it.name.endsWith(".zip")
                        }.sorted()
                        .toList()
                        .lastOrNull()
                }
            }

    private fun <T> withDump(block: (Map<String, JsonObject>) -> T): T {
        assumeTrue(pack != null && Files.isRegularFile(pack), "no exporter pack")
        FileSystems.newFileSystem(pack!!, null as ClassLoader?).use { fs ->
            val files =
                listOf("mte", "casings", "materials", "ores").associateWith { name ->
                    Files.newBufferedReader(fs.getPath("gtnh/gregtech/$name.json")).use { JsonParser.parseReader(it).asJsonObject }
                }
            return block(files)
        }
    }

    @Test
    fun `ore rule reproduces GT's getTextures examples`() =
        withDump { files ->
            val data = GtDataParser.parse(files["mte"], files["casings"], files["materials"], files["ores"])
            val ores = files.getValue("ores")
            val layers = ores.getAsJsonArray("layers").map { GtDataParser.layer(it.asJsonObject) }
            var checked = 0
            for (block in ores.getAsJsonArray("blocks")) {
                val b = block.asJsonObject
                val key = Key(b.get("block").asString)
                for (example in b.getAsJsonArray("examples")) {
                    val e = example.asJsonObject
                    val meta = e.get("meta").asInt
                    val actual = GtFaces.ore(data, key, meta)
                    for (side in e.getAsJsonArray("faces")) {
                        val expected = side.asJsonArray.map { layers[it.asInt] }
                        assertEquals(expected, actual, "$key meta $meta")
                    }
                    checked++
                }
            }
            assertTrue(checked > 50, "examples checked: $checked")
        }

    @Test
    fun `hatch and basic machine composition matches GT's textures`() =
        withDump { files ->
            val data = GtDataParser.parse(files["mte"], files["casings"], files["materials"], files["ores"])
            val hatches = data.mtes.values.filter { it.hatch != null && it.textures != null }
            val machines = data.mtes.values.filter { it.outputFace != null && it.textures != null }
            assertTrue(hatches.size > 100 && machines.size > 100, "hatches ${hatches.size}, basic machines ${machines.size}")

            // the dump's textures were taken with mFacing = mMainFacing = facing, unpainted, default casing
            for (mte in machines) {
                for (facing in 0 until 6) {
                    for (active in 0..1) {
                        val te = machine(mte.id, facing = facing, active = active == 1, mainFacing = facing)
                        assertEquals(
                            mte.textures!![facing][active],
                            GtFaces.machine(data, mte, te),
                            "${mte.name} facing $facing active $active",
                        )
                    }
                }
            }

            // composing the front overlay with the background (used for custom casings and paint) gives GT's result,
            // except for the few hatches that override getTexture
            val differing =
                hatches.filter { mte ->
                    (0 until 6).any { facing ->
                        (0..1).any { active ->
                            GtFaces.hatch(data, mte, mte.hatch!!, 0, facing, active, -1) !=
                                mte.textures!![facing][active]
                        }
                    }
                }
            assertTrue(differing.size * 100 <= hatches.size, "hatches differing from GT: ${differing.map { it.name }}")
        }

    @Test
    fun `ore and frame faces, tints and UVs match the quads captured from GT's renderer in the client`() =
        withDump { files ->
            val data = GtDataParser.parse(files["mte"], files["casings"], files["materials"], files["ores"])
            FileSystems.newFileSystem(pack!!, null as ClassLoader?).use { fs ->
                var quads = 0
                val blocks: Map<Key, (Int) -> GtFace?> =
                    data.oreBlocks.keys.associateWith { block -> { meta: Int -> GtFaces.ore(data, block, meta) } } +
                        (Key("gregtech", "gt.blockframes") to { meta: Int -> GtFaces.frame(data, meta) })
                for ((block, faceOf) in blocks) {
                    val blockstate = fs.getPath("assets/${block.namespace}/blockstates/${block.value}.json")
                    if (!Files.isRegularFile(blockstate)) continue
                    val variants =
                        Files
                            .newBufferedReader(
                                blockstate,
                            ).use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("variants") }
                    for ((condition, variant) in variants.entrySet()) {
                        val meta = condition.removePrefix("meta=").toIntOrNull() ?: continue
                        val model = Key(variant.asJsonObject.get("model").asString)
                        if (!model.value.startsWith("gtnh_quads/")) continue
                        val captured =
                            Files
                                .newBufferedReader(
                                    fs.getPath("assets/${model.namespace}/models/${model.value}.json"),
                                ).use { QuadModel.parse(it) }
                        val face = faceOf(meta) ?: continue
                        val expectedTextures = face.flatMap { listOfNotNull(it.texture, it.overlay) }
                        for (side in 0 until 6) {
                            val onSide = captured.quads.filter { it.facing.ordinal == directionOrdinal(side) }
                            assertEquals(
                                expectedTextures,
                                onSide.map { captured.textures[it.texture] },
                                "$block meta $meta side $side textures",
                            )
                            if (side == Side.UP) {
                                // tint: the up face has no vanilla shade, so the captured vertex colour is GT's rgba
                                val tints =
                                    face.flatMap { layer ->
                                        listOfNotNull(layer.texture?.let { layer.rgb }, layer.overlay?.let { GtLayer.WHITE })
                                    }
                                for ((quad, rgb) in onSide.zip(tints)) {
                                    assertEquals((rgb shr 16 and 0xFF) / 255f, quad.r, 0.01f, "$block meta $meta red")
                                    assertEquals((rgb shr 8 and 0xFF) / 255f, quad.g, 0.01f, "$block meta $meta green")
                                    assertEquals((rgb and 0xFF) / 255f, quad.b, 0.01f, "$block meta $meta blue")
                                }
                            }
                            for (quad in onSide) {
                                val op = GtUv.transform(side, UvMode.RENDERED, false, 0, 0, data.fixedBottomFaceUV)
                                val uv = FloatArray(2)
                                for (i in 0 until 4) {
                                    GtUv.uv(side, quad.positions[i * 3], quad.positions[i * 3 + 1], quad.positions[i * 3 + 2], op, uv, 0)
                                    assertEquals(quad.uvs[i * 2], uv[0], 1e-3f, "$block meta $meta side $side corner $i u")
                                    assertEquals(quad.uvs[i * 2 + 1], uv[1], 1e-3f, "$block meta $meta side $side corner $i v")
                                }
                                quads++
                            }
                        }
                    }
                }
                assertTrue(quads > 1000, "captured quads checked: $quads")
            }
        }

    /** BlueMap [de.bluecolored.bluemap.core.util.Direction] ordinal of a ForgeDirection side. */
    private fun directionOrdinal(side: Int): Int =
        when (side) {
            Side.DOWN -> de.bluecolored.bluemap.core.util.Direction.DOWN.ordinal
            Side.UP -> de.bluecolored.bluemap.core.util.Direction.UP.ordinal
            Side.NORTH -> de.bluecolored.bluemap.core.util.Direction.NORTH.ordinal
            Side.SOUTH -> de.bluecolored.bluemap.core.util.Direction.SOUTH.ordinal
            Side.WEST -> de.bluecolored.bluemap.core.util.Direction.WEST.ordinal
            else -> de.bluecolored.bluemap.core.util.Direction.EAST.ordinal
        }
}
