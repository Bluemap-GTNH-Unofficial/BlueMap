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

import de.bluecolored.bluemap.gtnh.oracle.MeshBuilder.Companion.solid
import de.bluecolored.bluemap.gtnh.oracle.MeshBuilder.Vertex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.IndexColorModel
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class RasterizerTest {
    /** The contract camera of a view, for analytic expectations. */
    private class View(
        yawDegrees: Double,
        pitchDegrees: Double,
    ) {
        private val yaw = Math.toRadians(yawDegrees)
        private val pitch = Math.toRadians(pitchDegrees)
        val r = doubleArrayOf(cos(yaw), 0.0, -sin(yaw))
        val u = doubleArrayOf(-sin(pitch) * sin(yaw), cos(pitch), -sin(pitch) * cos(yaw))
        val v = doubleArrayOf(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw))

        /** Where the viewing ray of pixel ([i], [j]) starts (10 units towards the camera) and its direction. */
        fun ray(
            i: Int,
            j: Int,
        ): Pair<DoubleArray, DoubleArray> {
            val sx = (i + 0.5) / 64 - 1
            val sy = 1 - (j + 0.5) / 64
            return DoubleArray(3) { 0.5 + sx * r[it] + sy * u[it] + 10 * v[it] } to DoubleArray(3) { -v[it] }
        }

        /** The face of the unit cube a ray enters first, [FaceIds.NONE] if it misses the cube. */
        fun cubeFace(
            i: Int,
            j: Int,
        ): Int {
            val (origin, direction) = ray(i, j)
            var near = Double.NEGATIVE_INFINITY
            var far = Double.POSITIVE_INFINITY
            var axis = -1
            for (k in 0..2) {
                val t0 = (0 - origin[k]) / direction[k]
                val t1 = (1 - origin[k]) / direction[k]
                if (minOf(t0, t1) > near) {
                    near = minOf(t0, t1)
                    axis = k
                }
                far = minOf(far, maxOf(t0, t1))
            }
            if (near > far) return FaceIds.NONE
            return when (axis) {
                0 -> if (direction[0] < 0) FaceIds.EAST else FaceIds.WEST
                1 -> if (direction[1] < 0) FaceIds.UP else FaceIds.DOWN
                else -> if (direction[2] < 0) FaceIds.SOUTH else FaceIds.NORTH
            }
        }
    }

    private val se = View(45.0, 30.0)
    private val nw = View(225.0, 30.0)
    private val top = RasterSpec(width = 128, height = 128, views = listOf(RasterView("top", 0.0, 90.0, 0)))
    private val front = RasterSpec(width = 128, height = 128, views = listOf(RasterView("front", 0.0, 0.0, 0)))

    private fun render(
        mesh: RasterMesh,
        spec: RasterSpec = RasterSpec(),
        ambientLight: Double = 0.1,
        textures: (Int) -> RasterTexture?,
    ): RasterImage = Rasterizer(spec).render(mesh, textures, ambientLight)

    private fun unorm8(value: Double): Int = (value * 255 + 0.5).toInt()

    /** A 16x16 texture whose texel (col, row) has red = 16 * col + 8 and green = 16 * row + 8. */
    private val coordinates =
        RasterTexture(
            16,
            16,
            IntArray(256) {
                (0xFF shl 24) or ((it % 16 * 16 + 8) shl 16) or
                    ((it / 16 * 16 + 8) shl 8)
            },
            false,
        )

    /** Up-facing square `[0, 1]` in x and z at height [y] (u = x, v = z). */
    private fun MeshBuilder.level(
        y: Double,
        material: Int,
    ) = quad(
        Vertex(0.0, y, 1.0, 0.0, 1.0),
        Vertex(1.0, y, 1.0, 1.0, 1.0),
        Vertex(1.0, y, 0.0, 1.0, 0.0),
        Vertex(0.0, y, 0.0, 0.0, 0.0),
        material,
    )

    /** South-facing square `[0, 1]` in x and y at depth [z]. */
    private fun MeshBuilder.wall(
        z: Double,
        material: Int,
    ) = quad(
        Vertex(0.0, 0.0, z, 0.0, 1.0),
        Vertex(1.0, 0.0, z, 1.0, 1.0),
        Vertex(1.0, 1.0, z, 1.0, 0.0),
        Vertex(0.0, 1.0, z, 0.0, 0.0),
        material,
    )

    @Test
    fun `cube faces match the analytic projection in both views`() {
        val colors = IntArray(8) { (0xFF shl 24) or ((30 * it) shl 16) or ((250 - 25 * it) shl 8) or 120 }
        val image = render(MeshBuilder().cube().build()) { solid(colors[it]) }

        for ((view, x0, visible) in listOf(
            Triple(se, 0, setOf(FaceIds.UP, FaceIds.SOUTH, FaceIds.EAST)),
            Triple(nw, 128, setOf(FaceIds.UP, FaceIds.NORTH, FaceIds.WEST)),
        )) {
            val expectedCounts = HashMap<Int, Int>()
            val actualCounts = HashMap<Int, Int>()
            val mismatches = ArrayList<String>()
            for (j in 0 until 128) {
                for (i in 0 until 128) {
                    val expected = view.cubeFace(i, j)
                    val actual = image.faceId(x0 + i, j)
                    expectedCounts.merge(expected, 1, Int::plus)
                    actualCounts.merge(actual, 1, Int::plus)
                    if (expected != actual) mismatches += "($i, $j): expected $expected, got $actual"
                    if (actual == FaceIds.NONE) {
                        assertEquals(0, image.argb(x0 + i, j))
                    } else {
                        val shade = FaceIds.shade(actual)
                        val c = colors[actual]
                        val rgb = listOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF).map { unorm8(it / 255.0 * shade) }
                        assertEquals(
                            (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2],
                            image.argb(x0 + i, j),
                            "colour at ($i, $j)",
                        )
                    }
                }
            }
            assertEquals(emptyList<String>(), mismatches.take(20), "${mismatches.size} pixels differ from the analytic projection")
            assertEquals(expectedCounts, actualCounts)
            assertEquals(visible + FaceIds.NONE, actualCounts.keys)
            assertTrue(visible.all { (actualCounts[it] ?: 0) > 1000 }, "every visible face covers a large area: $actualCounts")
        }
    }

    @Test
    fun `texels land on predicted pixels and v = 0 is the top row`() {
        val image = render(MeshBuilder().level(1.0, 0).build(), top) { coordinates }
        for (j in 0 until 128) {
            for (i in 0 until 128) {
                if (i in 32..95 && j in 32..95) {
                    val col = (i - 32) / 4
                    val row = (j - 32) / 4
                    assertEquals(FaceIds.UP, image.faceId(i, j))
                    assertEquals(coordinates.argb[row * 16 + col], image.argb(i, j), "texel at ($i, $j)")
                } else {
                    assertEquals(FaceIds.NONE, image.faceId(i, j))
                }
            }
        }
    }

    @Test
    fun `up face of the se view samples the analytic uv`() {
        val image = render(MeshBuilder().cube { if (it == FaceIds.UP) 1 else 0 }.build()) { if (it == 1) coordinates else solid(-1) }
        var checked = 0
        var ambiguous = 0
        for (j in 0 until 128) {
            for (i in 0 until 128) {
                if (image.faceId(i, j) != FaceIds.UP) continue
                val (origin, direction) = se.ray(i, j)
                val t = (1 - origin[1]) / direction[1]
                val u = (origin[0] + t * direction[0]) * 16
                val v = (origin[2] + t * direction[2]) * 16
                if (u - floor(u) < 1e-3 || u - floor(u) > 1 - 1e-3 || v - floor(v) < 1e-3 || v - floor(v) > 1 - 1e-3) {
                    ambiguous++
                    continue
                }
                assertEquals(coordinates.argb[floor(v).toInt() * 16 + floor(u).toInt()], image.argb(i, j), "texel at ($i, $j)")
                checked++
            }
        }
        assertTrue(checked > 2000 && ambiguous < checked / 50, "checked $checked, ambiguous $ambiguous")
    }

    @Test
    fun `vanilla face shade, light and ao`() {
        val white = render(MeshBuilder().cube { 0 }.build()) { solid(-1) }
        val byFace = HashMap<Int, MutableSet<Int>>()
        for (y in 0 until 128) for (x in 0 until 256) byFace.getOrPut(white.faceId(x, y)) { HashSet() } += white.argb(x, y)
        assertEquals(setOf(0), byFace[FaceIds.NONE])
        assertEquals(setOf(gray(255)), byFace[FaceIds.UP])
        assertEquals(setOf(gray(204)), byFace[FaceIds.SOUTH])
        assertEquals(setOf(gray(204)), byFace[FaceIds.NORTH])
        assertEquals(setOf(gray(153)), byFace[FaceIds.EAST])
        assertEquals(setOf(gray(153)), byFace[FaceIds.WEST])

        val below = RasterSpec(views = listOf(RasterView("below", 45.0, -30.0, 0)))
        val down = render(MeshBuilder().cube { 0 }.build(), below) { solid(-1) }
        assertEquals(gray(128), down.argb(64, 100))
        assertEquals(FaceIds.DOWN, down.faceId(64, 100))

        // vertical quad through the block diagonal, facing south-east: "other", no shade
        val diagonal =
            MeshBuilder()
                .quad(Vertex(0.0, 0.0, 1.0), Vertex(1.0, 0.0, 0.0), Vertex(1.0, 1.0, 0.0), Vertex(0.0, 1.0, 1.0), 0)
                .build()
        val other = render(diagonal) { solid(-1) }
        assertEquals(FaceIds.OTHER, other.faceId(64, 64))
        assertEquals(gray(255), other.argb(64, 64))
        assertEquals(FaceIds.NONE, other.faceId(128 + 64, 64), "back side culled in the nw view")

        fun level(
            sun: Int,
            block: Int,
            ao: Double,
            color: DoubleArray = MeshBuilder.WHITE,
        ): Int {
            val mesh =
                MeshBuilder()
                    .quad(
                        Vertex(0.0, 1.0, 1.0, ao = ao),
                        Vertex(1.0, 1.0, 1.0, ao = ao),
                        Vertex(1.0, 1.0, 0.0, ao = ao),
                        Vertex(0.0, 1.0, 0.0, ao = ao),
                        0,
                        color,
                        sun,
                        block,
                    ).build()
            return render(mesh, top, ambientLight = 0.1) { solid(-1) }.argb(64, 64)
        }
        assertEquals(gray(255), level(15, 0, 1.0))
        assertEquals(gray(26), level(0, 0, 1.0))
        assertEquals(gray(255), level(0, 15, 1.0))
        assertEquals(gray(unorm8(0.1 + 0.9 * 9 / 15)), level(9, 3, 1.0))
        assertEquals(gray(128), level(15, 0, 0.5))
        assertEquals(argb(255, 51, 255, 128), level(15, 0, 1.0, doubleArrayOf(0.2, 1.0, 0.5)))
    }

    @Test
    fun `discarded texels draw nothing and write no depth`() {
        val red = solid(argb(255, 255, 0, 0))

        fun frontOf(alpha: Int) =
            render(MeshBuilder().wall(0.75, 1).wall(0.25, 0).build(), front) {
                if (it ==
                    0
                ) {
                    red
                } else {
                    solid(argb(alpha, 255, 255, 255))
                }
            }

        val discarded = frontOf(2)
        assertEquals(argb(255, 204, 0, 0), discarded.argb(64, 64), "alpha 2/255 <= 0.01: the red wall behind shows")

        val kept = frontOf(3)
        assertEquals(argb(3, 204, 204, 204), kept.argb(64, 64), "alpha 3/255 > 0.01: replaces and hides the wall behind")
        assertEquals(FaceIds.SOUTH, kept.faceId(64, 64))
    }

    @Test
    fun `half-transparent textures blend over the opaque pass in straight alpha`() {
        val glass = solid(argb(128, 255, 255, 255), halfTransparent = true)
        val red = solid(argb(255, 255, 0, 0))
        val textures = { id: Int -> if (id == 0) red else glass }

        // the glass is first in model order but drawn after the opaque pass
        val over = render(MeshBuilder().level(0.75, 1).level(0.25, 0).build(), top, textures = textures)
        assertEquals(argb(255, 255, 128, 128), over.argb(64, 64))

        val alone = render(MeshBuilder().level(0.75, 1).build(), top, textures = textures)
        assertEquals(argb(128, 255, 255, 255), alone.argb(64, 64))

        val twice = render(MeshBuilder().level(0.25, 1).level(0.75, 1).build(), top, textures = textures)
        assertEquals(argb(unorm8(1 - (127 / 255.0) * (127 / 255.0)), 255, 255, 255), twice.argb(64, 64))

        // glass behind an opaque surface fails the depth test
        val hidden = render(MeshBuilder().level(0.75, 0).level(0.25, 1).build(), top, textures = textures)
        assertEquals(argb(255, 255, 0, 0), hidden.argb(64, 64))
    }

    @Test
    fun `depth ties go to the later triangle`() {
        val textures = { id: Int -> solid(if (id == 0) argb(255, 255, 0, 0) else argb(255, 0, 255, 0)) }
        assertEquals(argb(255, 0, 255, 0), render(MeshBuilder().level(0.5, 0).level(0.5, 1).build(), top, textures = textures).argb(64, 64))
        assertEquals(argb(255, 255, 0, 0), render(MeshBuilder().level(0.5, 1).level(0.5, 0).build(), top, textures = textures).argb(64, 64))
    }

    @Test
    fun `back faces are culled`() {
        val a = Vertex(0.0, 0.5, 1.0)
        val b = Vertex(1.0, 0.5, 1.0)
        val c = Vertex(1.0, 0.5, 0.0)
        val facingUp = render(MeshBuilder().triangle(a, b, c, 0).build(), top) { solid(-1) }
        val facingDown = render(MeshBuilder().triangle(a, c, b, 0).build(), top) { solid(-1) }
        assertEquals(FaceIds.UP, facingUp.faceId(90, 40))
        assertEquals(0, facingDown.coveredPixels)
    }

    @Test
    fun `shared edges through pixel centres are covered exactly once`() {
        val glass = solid(argb(128, 255, 255, 255), halfTransparent = true)
        // full-view quad: its diagonal runs through the pixel centres (i, 127 - i)
        val full =
            MeshBuilder()
                .quad(
                    Vertex(-0.5, -0.5, 0.5),
                    Vertex(1.5, -0.5, 0.5),
                    Vertex(1.5, 1.5, 0.5),
                    Vertex(-0.5, 1.5, 0.5),
                    0,
                ).build()
        val diagonal = render(full, front) { glass }
        // south-facing: vanilla shade 0.8
        for (y in 0 until 128) for (x in 0 until 128) assertEquals(argb(128, 204, 204, 204), diagonal.argb(x, y), "($x, $y)")

        // two quads sharing the horizontal edge y = 65/128, the pixel centres of row 63
        val split = 0.5078125
        val halves =
            MeshBuilder()
                .quad(Vertex(0.0, 0.0, 0.5), Vertex(1.0, 0.0, 0.5), Vertex(1.0, split, 0.5), Vertex(0.0, split, 0.5), 0)
                .quad(Vertex(0.0, split, 0.5), Vertex(1.0, split, 0.5), Vertex(1.0, 1.0, 0.5), Vertex(0.0, 1.0, 0.5), 0)
                .build()
        val horizontal = render(halves, front) { glass }
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val expected = if (x in 32..95 && y in 32..95) argb(128, 204, 204, 204) else 0
                assertEquals(expected, horizontal.argb(x, y), "($x, $y)")
            }
        }
    }

    @Test
    fun `animated textures show the first frame of their frame list`() {
        val frames = intArrayOf(argb(255, 255, 0, 0), argb(255, 0, 255, 0), argb(255, 0, 0, 255))
        val pixels = IntArray(16 * 48) { frames[it / (16 * 16)] }
        val mesh = MeshBuilder().level(1.0, 0).build()

        fun color(texture: RasterTexture) = render(mesh, top) { texture }.argb(64, 40)

        assertEquals(frames[2], color(RasterTexture(16, 48, pixels, false, animated = true, frame = 2)))
        assertEquals(frames[0], color(RasterTexture(16, 48, pixels, false, animated = true)))
        assertEquals(frames[0], color(RasterTexture(16, 48, pixels, false)), "not animated: v spans the whole image")
        assertEquals(frames[2], render(mesh, top) { RasterTexture(16, 48, pixels, false) }.argb(64, 90))
    }

    @Test
    fun `png files hold straight alpha and the face palette`(
        @TempDir dir: Path,
    ) {
        val glass = solid(argb(128, 255, 0, 0), halfTransparent = true)
        val image = render(MeshBuilder().cube { 0 }.build()) { glass }
        image.write(dir.resolve("a/b.png"), dir.resolve("a/b.faces.png"))

        val color = ImageIO.read(dir.resolve("a/b.png").toFile())
        val faces = ImageIO.read(dir.resolve("a/b.faces.png").toFile())
        val palette = faces.colorModel as IndexColorModel
        assertEquals(8, faces.colorModel.pixelSize)
        assertEquals(0, palette.getAlpha(0))
        for (i in 1 until 8) assertEquals(255, palette.getAlpha(i))
        assertEquals(
            listOf(
                0xFF404040.toInt(),
                -1,
                0xFFFF0000.toInt(),
                0xFF00FFFF.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFF00.toInt(),
                0xFFFF00FF.toInt(),
            ),
            (
                1 until
                    8
            ).map(palette::getRGB),
        )
        for (y in 0 until 128) {
            for (x in 0 until 256) {
                assertEquals(image.argb(x, y), color.getRGB(x, y))
                assertEquals(image.faceId(x, y), faces.raster.getSample(x, y, 0))
            }
        }
        assertEquals(argb(128, 255, 0, 0), color.getRGB(64, 30), "straight alpha: the colour is not darkened by alpha")
    }

    private fun gray(value: Int) = argb(255, value, value, value)

    private fun argb(
        a: Int,
        r: Int,
        g: Int,
        b: Int,
    ) = (a shl 24) or (r shl 16) or (g shl 8) or b
}
