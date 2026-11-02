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

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.awt.image.IndexColorModel
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One orthographic view of the oracle image: yaw and pitch in degrees, drawn at ([x], [y]) of the image. */
data class RasterView(
    val name: String,
    val yaw: Double,
    val pitch: Double,
    val x: Int,
    val y: Int = 0,
)

/**
 * Camera and image layout of the state oracle; the defaults are the contract's values. Screen x of a block-local
 * point `q` is `(q - target) . r`, screen y `(q - target) . u`, both in `[-halfExtent, halfExtent]` over [viewSize]
 * pixels, depth `-(q - target) . v` clipped to `[-depthRange, depthRange]`.
 */
data class RasterSpec(
    val width: Int = 256,
    val height: Int = 128,
    val viewSize: Int = 128,
    val views: List<RasterView> = DEFAULT_VIEWS,
    val halfExtent: Double = 1.0,
    val depthRange: Double = 10.0,
    val targetX: Double = 0.5,
    val targetY: Double = 0.5,
    val targetZ: Double = 0.5,
) {
    init {
        for (view in views) {
            require(view.x >= 0 && view.y >= 0 && view.x + viewSize <= width && view.y + viewSize <= height) {
                "View '${view.name}' does not fit into the ${width}x$height image"
            }
        }
    }

    companion object {
        val DEFAULT_VIEWS: List<RasterView> = listOf(RasterView("se", 45.0, 30.0, 0), RasterView("nw", 225.0, 30.0, 128))
    }
}

/**
 * A texture as the webapp samples it: straight ARGB texels, row-major from the top-left texel (uv `(0, 0)`).
 * Animated textures stack square frames vertically (frame height = [width], as the webapp's `TextureAnimation`
 * assumes); [frame] is the frame drawn (the first entry of the animation's frame list, 0 without one).
 */
class RasterTexture(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val halfTransparent: Boolean,
    val animated: Boolean = false,
    val frame: Int = 0,
) {
    private val frameHeight = if (animated) width else height

    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "Invalid texture size ${width}x$height" }
    }

    /** The texel at ([u], [v]): nearest, clamped to the edge of the frame. */
    fun texel(
        u: Double,
        v: Double,
    ): Int {
        val col = floor(u * width).toInt().coerceIn(0, width - 1)
        val row = floor(v * frameHeight).toInt().coerceIn(0, frameHeight - 1)
        return argb[(frame * frameHeight + row).coerceIn(0, height - 1) * width + col]
    }
}

/**
 * Non-indexed triangles as the webapp receives them (PRBM geometry): per-vertex attributes and one material
 * (texture id) per triangle; -1 marks triangles outside every material group, which are not drawn.
 */
class RasterMesh(
    /** x, y, z per vertex, block-local (the block occupies `[0, 1]^3`). */
    val positions: FloatArray,
    /** u, v per vertex. */
    val uvs: FloatArray,
    /** r, g, b per vertex. */
    val colors: FloatArray,
    val aos: FloatArray,
    val sunlight: FloatArray,
    val blocklight: FloatArray,
    val materials: IntArray,
) {
    val triangleCount: Int get() = materials.size

    init {
        val vertices = materials.size * 3
        require(
            positions.size == vertices * 3 && uvs.size == vertices * 2 && colors.size == vertices * 3 &&
                aos.size == vertices && sunlight.size == vertices && blocklight.size == vertices,
        ) { "Inconsistent attribute sizes for ${materials.size} triangles" }
    }
}

/** Contract face ids (BlueMap `Direction` order + 1), the vanilla face shade and the face PNG palette. */
object FaceIds {
    const val NONE: Int = 0
    const val DOWN: Int = 1
    const val UP: Int = 2
    const val NORTH: Int = 3
    const val SOUTH: Int = 4
    const val WEST: Int = 5
    const val EAST: Int = 6
    const val OTHER: Int = 7

    /** The face id of a unit normal: the axis direction if its component has magnitude >= 0.95, else [OTHER]. */
    fun of(
        nx: Double,
        ny: Double,
        nz: Double,
    ): Int =
        when {
            ny <= -AXIS -> DOWN
            ny >= AXIS -> UP
            nz <= -AXIS -> NORTH
            nz >= AXIS -> SOUTH
            nx <= -AXIS -> WEST
            nx >= AXIS -> EAST
            else -> OTHER
        }

    /** Vanilla 1.7 directional face shade. */
    fun shade(faceId: Int): Double =
        when (faceId) {
            DOWN -> 0.5
            NORTH, SOUTH -> 0.8
            WEST, EAST -> 0.6
            else -> 1.0
        }

    /** `<path>.faces.png` palette, index 0 transparent. */
    val PALETTE: IndexColorModel =
        IndexColorModel(
            8,
            8,
            byteArrayOf(0, 64, 255.toByte(), 255.toByte(), 0, 0, 255.toByte(), 255.toByte()),
            byteArrayOf(0, 64, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 0),
            byteArrayOf(0, 64, 255.toByte(), 0, 255.toByte(), 255.toByte(), 0, 255.toByte()),
            0,
        )

    private const val AXIS = 0.95
}

/** Rasterised views: straight-alpha RGBA (`0..1`) and the face id of the front-most drawn fragment per pixel. */
class RasterImage(
    val width: Int,
    val height: Int,
) {
    val rgba: FloatArray = FloatArray(width * height * 4)
    val faces: ByteArray = ByteArray(width * height)

    val coveredPixels: Int get() = faces.count { it.toInt() != FaceIds.NONE }

    /** The 8-bit straight ARGB value of a pixel as written to the PNG (fully transparent pixels are `0`). */
    fun argb(
        x: Int,
        y: Int,
    ): Int {
        val o = (y * width + x) * 4
        val a = unorm8(rgba[o + 3])
        if (a == 0) return 0
        return (a shl 24) or (unorm8(rgba[o]) shl 16) or (unorm8(rgba[o + 1]) shl 8) or unorm8(rgba[o + 2])
    }

    fun faceId(
        x: Int,
        y: Int,
    ): Int = faces[y * width + x].toInt()

    fun colorImage(): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val data = (image.raster.dataBuffer as DataBufferInt).data
        for (y in 0 until height) for (x in 0 until width) data[y * width + x] = argb(x, y)
        return image
    }

    fun facesImage(): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, FaceIds.PALETTE)
        faces.copyInto((image.raster.dataBuffer as DataBufferByte).data)
        return image
    }

    /** Writes `<path>.png` (straight-alpha RGBA) and `<path>.faces.png` (8-bit palette). */
    fun write(
        colorPng: Path,
        facesPng: Path,
    ) {
        colorPng.parent?.let { Files.createDirectories(it) }
        facesPng.parent?.let { Files.createDirectories(it) }
        check(ImageIO.write(colorImage(), "png", colorPng.toFile())) { "No PNG writer" }
        check(ImageIO.write(facesImage(), "png", facesPng.toFile())) { "No PNG writer" }
    }

    private fun unorm8(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
}

/**
 * Software rasteriser emulating the webapp's hires material (`HiresFragmentShader.js`, `Map.js`) under the
 * oracle camera: orthographic views, back-face culling, pixel-centre coverage with the top-left rule (vertices
 * snapped to 1/[SUBPIXELS] pixel, exact integer edge functions), a 24-bit LEQUAL depth buffer written by every
 * drawn fragment, nearest clamped texels, discard at texel alpha <= 0.01, and two phases: textures that are not
 * `halfTransparent` replace the pixel (in mesh order), then `halfTransparent` ones blend over it (straight alpha).
 * Colour: `texel * vertexColor * ao * mix(ambientLight, 1, max(sunlight, blocklight) / 15) * shade(faceId)`.
 *
 * Not thread-safe (scratch buffers); use one instance per thread.
 */
class Rasterizer(
    private val spec: RasterSpec = RasterSpec(),
) {
    private val cameras = spec.views.map { Camera(it) }
    private val scale = spec.viewSize / (2.0 * spec.halfExtent) * SUBPIXELS
    private val screenX = LongArray(3)
    private val screenY = LongArray(3)
    private val depths = DoubleArray(3)

    private class Camera(
        view: RasterView,
    ) {
        val x = view.x
        val y = view.y
        val rx: Double
        val rz: Double
        val ux: Double
        val uy: Double
        val uz: Double
        val vx: Double
        val vy: Double
        val vz: Double

        init {
            val yaw = Math.toRadians(view.yaw)
            val pitch = Math.toRadians(view.pitch)
            rx = cos(yaw)
            rz = -sin(yaw)
            ux = -sin(pitch) * sin(yaw)
            uy = cos(pitch)
            uz = -sin(pitch) * cos(yaw)
            vx = cos(pitch) * sin(yaw)
            vy = sin(pitch)
            vz = cos(pitch) * cos(yaw)
        }
    }

    /** Draws [mesh] into a new image; [textures] maps a material (texture id) to its texture, null = not drawn. */
    fun render(
        mesh: RasterMesh,
        textures: (Int) -> RasterTexture?,
        ambientLight: Double,
    ): RasterImage {
        val image = RasterImage(spec.width, spec.height)
        val depth = IntArray(spec.width * spec.height) { DEPTH_FAR }
        for (halfTransparentPhase in PHASES) {
            for (t in 0 until mesh.triangleCount) {
                val material = mesh.materials[t]
                if (material < 0) continue
                val texture = textures(material) ?: continue
                if (texture.halfTransparent != halfTransparentPhase) continue
                val faceId = faceId(mesh, t)
                if (faceId == FaceIds.NONE) continue
                for (camera in cameras) draw(camera, mesh, t, texture, faceId, ambientLight, image, depth)
            }
        }
        return image
    }

    /** The face id of the triangle's right-hand normal, [FaceIds.NONE] for degenerate triangles. */
    private fun faceId(
        mesh: RasterMesh,
        t: Int,
    ): Int {
        val p = mesh.positions
        val i = t * 9
        val ax = p[i + 3].toDouble() - p[i]
        val ay = p[i + 4].toDouble() - p[i + 1]
        val az = p[i + 5].toDouble() - p[i + 2]
        val bx = p[i + 6].toDouble() - p[i]
        val by = p[i + 7].toDouble() - p[i + 1]
        val bz = p[i + 8].toDouble() - p[i + 2]
        val nx = ay * bz - az * by
        val ny = az * bx - ax * bz
        val nz = ax * by - ay * bx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (!(length > 0.0) || !length.isFinite()) return FaceIds.NONE
        return FaceIds.of(nx / length, ny / length, nz / length)
    }

    private fun draw(
        camera: Camera,
        mesh: RasterMesh,
        t: Int,
        texture: RasterTexture,
        faceId: Int,
        ambientLight: Double,
        image: RasterImage,
        depth: IntArray,
    ) {
        val positions = mesh.positions
        for (k in 0..2) {
            val p = (t * 3 + k) * 3
            val dx = positions[p] - spec.targetX
            val dy = positions[p + 1] - spec.targetY
            val dz = positions[p + 2] - spec.targetZ
            val sx = dx * camera.rx + dz * camera.rz
            val sy = dx * camera.ux + dy * camera.uy + dz * camera.uz
            val d = -(dx * camera.vx + dy * camera.vy + dz * camera.vz)
            if (!sx.isFinite() || !sy.isFinite() || !d.isFinite()) return
            screenX[k] = snap(sx)
            screenY[k] = snap(sy)
            depths[k] = d
        }
        val x0 = screenX[0]
        val y0 = screenY[0]
        val x1 = screenX[1]
        val y1 = screenY[1]
        val x2 = screenX[2]
        val y2 = screenY[2]

        // signed area in the y-up screen frame = n . v (orthonormal camera): front faces are counter-clockwise
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (area <= 0L) return

        val size = spec.viewSize
        val colMin = max(0L, Math.ceilDiv(min(x0, min(x1, x2)) - HALF, SUBPIXELS)).toInt()
        val colMax = min(size - 1L, Math.floorDiv(max(x0, max(x1, x2)) - HALF, SUBPIXELS)).toInt()
        val upMin = max(0L, Math.ceilDiv(min(y0, min(y1, y2)) - HALF, SUBPIXELS)).toInt()
        val upMax = min(size - 1L, Math.floorDiv(max(y0, max(y1, y2)) - HALF, SUBPIXELS)).toInt()
        if (colMin > colMax || upMin > upMax) return

        // edge k is the one opposite vertex k; its function is the (scaled) barycentric weight of vertex k
        val topLeft0 = isTopLeft(x2 - x1, y2 - y1)
        val topLeft1 = isTopLeft(x0 - x2, y0 - y2)
        val topLeft2 = isTopLeft(x1 - x0, y1 - y0)
        val inverseArea = 1.0 / area

        val v = t * 3
        val uvs = mesh.uvs
        val colors = mesh.colors
        val shade = FaceIds.shade(faceId)
        val faceByte = faceId.toByte()
        val halfTransparent = texture.halfTransparent
        val rgba = image.rgba
        val range = spec.depthRange

        for (up in upMin..upMax) {
            val py = up * SUBPIXELS + HALF
            val row = camera.y + (size - 1 - up)
            for (col in colMin..colMax) {
                val px = col * SUBPIXELS + HALF
                val e0 = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
                if (e0 < 0L || (e0 == 0L && !topLeft0)) continue
                val e1 = (x0 - x2) * (py - y2) - (y0 - y2) * (px - x2)
                if (e1 < 0L || (e1 == 0L && !topLeft1)) continue
                val e2 = (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)
                if (e2 < 0L || (e2 == 0L && !topLeft2)) continue

                val w0 = e0 * inverseArea
                val w1 = e1 * inverseArea
                val w2 = e2 * inverseArea
                val d = w0 * depths[0] + w1 * depths[1] + w2 * depths[2]
                if (d < -range || d > range) continue
                val z = Math.round((d + range) / (2 * range) * DEPTH_FAR).toInt()
                val pixel = row * spec.width + camera.x + col
                if (z > depth[pixel]) continue

                val texel =
                    texture.texel(
                        w0 * uvs[v * 2] + w1 * uvs[v * 2 + 2] + w2 * uvs[v * 2 + 4],
                        w0 * uvs[v * 2 + 1] + w1 * uvs[v * 2 + 3] + w2 * uvs[v * 2 + 5],
                    )
                val alpha = (texel ushr 24) / 255.0
                if (alpha <= DISCARD_ALPHA) continue

                val ao = w0 * mesh.aos[v] + w1 * mesh.aos[v + 1] + w2 * mesh.aos[v + 2]
                val sun = w0 * mesh.sunlight[v] + w1 * mesh.sunlight[v + 1] + w2 * mesh.sunlight[v + 2]
                val block = w0 * mesh.blocklight[v] + w1 * mesh.blocklight[v + 1] + w2 * mesh.blocklight[v + 2]
                val light = max(sun, block) / 15.0
                val factor = ao * (ambientLight * (1 - light) + light) * shade / 255.0
                val c = v * 3
                val red = w0 * colors[c] + w1 * colors[c + 3] + w2 * colors[c + 6]
                val green = w0 * colors[c + 1] + w1 * colors[c + 4] + w2 * colors[c + 7]
                val blue = w0 * colors[c + 2] + w1 * colors[c + 5] + w2 * colors[c + 8]
                val r = ((texel shr 16) and 0xFF) * red * factor
                val g = ((texel shr 8) and 0xFF) * green * factor
                val b = (texel and 0xFF) * blue * factor

                val o = pixel * 4
                if (halfTransparent) {
                    val below = rgba[o + 3] * (1 - alpha)
                    val out = alpha + below
                    rgba[o] = ((r * alpha + rgba[o] * below) / out).toFloat()
                    rgba[o + 1] = ((g * alpha + rgba[o + 1] * below) / out).toFloat()
                    rgba[o + 2] = ((b * alpha + rgba[o + 2] * below) / out).toFloat()
                    rgba[o + 3] = out.toFloat()
                } else {
                    rgba[o] = r.toFloat()
                    rgba[o + 1] = g.toFloat()
                    rgba[o + 2] = b.toFloat()
                    rgba[o + 3] = alpha.toFloat()
                }
                depth[pixel] = z
                image.faces[pixel] = faceByte
            }
        }
    }

    /** Fixed-point screen coordinate (y up) of a view-space coordinate; far-away vertices are clamped. */
    private fun snap(screen: Double): Long = Math.round((screen + spec.halfExtent) * scale).coerceIn(-COORDINATE_LIMIT, COORDINATE_LIMIT)

    /** Top edge: horizontal with the triangle below; left edge: the triangle to its right (counter-clockwise, y up). */
    private fun isTopLeft(
        dx: Long,
        dy: Long,
    ): Boolean = dy < 0L || (dy == 0L && dx < 0L)

    companion object {
        /** Sub-pixel precision of the vertex snapping. */
        const val SUBPIXELS: Long = 1L shl 14
        private const val HALF = SUBPIXELS / 2

        /** Keeps the edge functions within a Long: +-2^15 pixels (512 block units) around the view. */
        private const val COORDINATE_LIMIT = 1L shl 29
        private const val DEPTH_FAR = (1 shl 24) - 1
        private const val DISCARD_ALPHA = 0.01
        private val PHASES = booleanArrayOf(false, true)
    }
}
