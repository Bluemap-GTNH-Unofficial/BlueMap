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

import de.bluecolored.bluemap.core.resources.pack.PackVersion
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The whole BlueMap side on a generated resource pack plus BlueMap's `resourceExtensions.zip` (no Minecraft jar). */
class StateRendererTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun registerAddons() = OracleResources.registerAddons()
    }

    @TempDir
    lateinit var dir: Path

    private fun file(
        path: String,
        content: String,
    ) {
        val file = dir.resolve("pack").resolve(path)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun png(
        path: String,
        argb: Int,
    ) {
        val file = dir.resolve("pack").resolve(path)
        file.parent.createDirectories()
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) for (x in 0 until 16) image.setRGB(x, y, argb)
        ImageIO.write(image, "png", file.toFile())
    }

    private fun renderer(): StateRenderer {
        file("assets/foo/blockstates/bar.json", """{"variants": {"": {"model": "foo:block/bar"}}}""")
        file(
            "assets/foo/models/block/bar.json",
            """
            {"textures": {"top": "foo:block/top", "side": "foo:block/side"},
             "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
               "down": {"texture": "#side", "cullface": "down"}, "up": {"texture": "#top", "cullface": "up"},
               "north": {"texture": "#side", "cullface": "north"}, "south": {"texture": "#side", "cullface": "south"},
               "west": {"texture": "#side", "cullface": "west"}, "east": {"texture": "#side", "cullface": "east"}}}]}
            """.trimIndent(),
        )
        png("assets/foo/textures/block/top.png", 0xFF00C000.toInt())
        png("assets/foo/textures/block/side.png", 0xFF0000FF.toInt())

        val roots = OracleResources.packRoots(listOf(dir.resolve("pack")), dir)
        val resourcePack = ResourcePack(PackVersion(4, 0)).apply { loadResources(roots) }
        val dataPack = DataPack(PackVersion(12, 0)).apply { loadResources(roots) }
        val resources = OracleResources(roots, resourcePack, dataPack)
        return StateRenderer(resources, resources.stateChunks(), RasterSpec())
    }

    @Test
    fun `renders a state through the hires pipeline`() {
        val result = renderer().render(ClientState("foo:bar[meta=0]", "foo:bar", 0, "foo/bar/0", 0, null))

        assertNull(result.error)
        assertEquals("foo:bar[meta=0]", result.blockState)
        assertNull(result.blockEntity)
        assertEquals(listOf(VariantInfo("bluemap:default", "foo:block/bar")), result.variants)
        assertEquals(12, result.triangles)
        assertFalse(result.missingTexture)
        assertFalse(result.blank)

        val expected =
            mapOf(
                FaceIds.UP to 0xFF00C000.toInt(),
                FaceIds.SOUTH to 0xFF0000CC.toInt(),
                FaceIds.NORTH to 0xFF0000CC.toInt(),
                FaceIds.EAST to 0xFF000099.toInt(),
                FaceIds.WEST to 0xFF000099.toInt(),
            )
        val seen = List(2) { HashSet<Int>() }
        for (y in 0 until 128) {
            for (x in 0 until 256) {
                val face = result.image.faceId(x, y)
                if (face == FaceIds.NONE) continue
                seen[x / 128] += face
                assertEquals(expected[face], result.image.argb(x, y), "($x, $y)")
            }
        }
        assertEquals(setOf(FaceIds.UP, FaceIds.SOUTH, FaceIds.EAST), seen[0])
        assertEquals(setOf(FaceIds.UP, FaceIds.NORTH, FaceIds.WEST), seen[1])
    }

    @Test
    fun `blocks without a blockstate resolve to the missing model`() {
        val result = renderer().render(ClientState("foo:nothing[meta=7]", "foo:nothing", 7, "foo/nothing/7", 7, null))

        assertNull(result.error)
        assertEquals("foo:nothing[meta=7]", result.blockState)
        assertEquals(listOf(VariantInfo("bluemap:missing", "bluemap:block/missing")), result.variants)
        assertTrue(result.missingTexture)
    }

    @Test
    fun `broken tile-entity NBT is reported and renders nothing`() {
        val result = renderer().render(ClientState("foo:bar[meta=0]", "foo:bar", 0, "foo/bar/0", 0, byteArrayOf(8, 0, 0)))

        assertTrue(result.error!!.isNotEmpty())
        assertTrue(result.blank)
        assertNull(result.blockState)
    }
}
