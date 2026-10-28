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
package de.bluecolored.bluemap.gtnh.render.mods

import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.render.QuadModel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One textured quad in block space. [positions] are 4 vertices × (x, y, z) in block units, counter-clockwise seen
 * from the front (the order `gtnh:quads` uses); [uvs] are 4 × (u, v) in sprite units (0..1).
 */
class MeshQuad(
    val positions: FloatArray,
    val uvs: FloatArray,
    val texture: Key,
    val rgb: Int = WHITE,
    /** Drawn with full block light. */
    val glow: Boolean = false,
    /** Only the first three vertices are used (the fourth repeats the third). */
    val isTriangle: Boolean = false,
    /** Multiplied with the block's colour (biome tint) on top of [rgb]. */
    val tint: Boolean = false,
) {
    private val normal = QuadModel.normal(positions)

    /** Axis direction closest to the face normal (neighbour lighting). */
    val facing: Direction = QuadModel.nearestDirection(normal)

    val normalY: Float get() = normal[1]

    /** The block face this quad lies on and faces out of (culled by an occluding neighbour there), else null. */
    val cullface: Direction? = boundaryFace(positions, facing)

    fun transformed(m: Transform): MeshQuad {
        val p = FloatArray(12)
        for (i in 0 until 4) m.apply(positions, i * 3, p, i * 3)
        return MeshQuad(p, uvs, texture, rgb, glow, isTriangle, tint)
    }

    fun withTexture(texture: Key, rgb: Int = this.rgb): MeshQuad = MeshQuad(positions, uvs, texture, rgb, glow, isTriangle, tint)

    companion object {
        const val WHITE = 0xFFFFFF
        private const val EPS = 1e-4f

        private fun boundaryFace(p: FloatArray, facing: Direction): Direction? {
            val axis = when (facing) {
                Direction.WEST, Direction.EAST -> 0
                Direction.DOWN, Direction.UP -> 1
                Direction.NORTH, Direction.SOUTH -> 2
            }
            val target = if (facing.toVector().let { it.x + it.y + it.z } > 0) 1f else 0f
            for (i in 0 until 4) if (abs(p[i * 3 + axis] - target) > EPS) return null
            return facing
        }
    }
}

class Mesh(val quads: List<MeshQuad>) {
    fun transformed(m: Transform): Mesh = Mesh(quads.map { it.transformed(m) })

    operator fun plus(other: Mesh): Mesh = Mesh(quads + other.quads)

    companion object {
        val EMPTY = Mesh(emptyList())
    }
}

/** A texture region on a face: sprite-unit rectangle, drawn upright and optionally rotated in 90° steps. */
data class FaceTexture(
    val texture: Key,
    val u1: Float = 0f,
    val v1: Float = 0f,
    val u2: Float = 1f,
    val v2: Float = 1f,
    /** Clockwise texture rotation in 90° steps. */
    val rotation: Int = 0,
    val rgb: Int = MeshQuad.WHITE,
    val glow: Boolean = false,
) {
    /** UVs for the corners top-left, bottom-left, bottom-right, top-right. */
    fun uvs(): FloatArray {
        val corners = floatArrayOf(u1, v1, u1, v2, u2, v2, u2, v1)
        val r = Math.floorMod(rotation, 4)
        if (r == 0) return corners
        // rotating the texture clockwise moves each corner's uv one corner on (TL takes BL's uv, ...)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val src = Math.floorMod(i + r, 4)
            out[i * 2] = corners[src * 2]
            out[i * 2 + 1] = corners[src * 2 + 1]
        }
        return out
    }

    companion object {
        /** The sub-rectangle of a sprite in pixels (0..16 by default). */
        fun pixels(texture: Key, u1: Float, v1: Float, u2: Float, v2: Float, size: Float = 16f, rotation: Int = 0) =
            FaceTexture(texture, u1 / size, v1 / size, u2 / size, v2 / size, rotation)
    }
}

/**
 * Face orientation conventions. Each face has a screen basis seen from outside the block: `right` and `down`
 * directions, which the texture's +u and +v follow.
 */
object Faces {
    /** All faces in BlueMap [Direction] order. */
    val ALL: List<Direction> = Direction.entries

    /** Screen-right unit vector of [face] (1.7 and 1.13 agree on every face). */
    fun right(face: Direction): IntArray = when (face) {
        Direction.NORTH -> intArrayOf(-1, 0, 0)
        Direction.SOUTH -> intArrayOf(1, 0, 0)
        Direction.WEST -> intArrayOf(0, 0, 1)
        Direction.EAST -> intArrayOf(0, 0, -1)
        Direction.UP, Direction.DOWN -> intArrayOf(1, 0, 0)
    }

    /**
     * Screen-down unit vector of [face]. 1.13 models put north at the top of the down face (texture top towards
     * +z... seen from below), 1.7 `RenderBlocks` maps the down face like the up face (v grows with +z).
     */
    fun down(face: Direction, legacyBottom: Boolean = false): IntArray = when (face) {
        Direction.UP -> intArrayOf(0, 0, 1)
        Direction.DOWN -> if (legacyBottom) intArrayOf(0, 0, 1) else intArrayOf(0, 0, -1)
        else -> intArrayOf(0, -1, 0)
    }

    /**
     * Positions of the region (sx1..sx2 × sy1..sy2, screen units 0..1 from the face's top-left) of [face] of the
     * box, in the order top-left, bottom-left, bottom-right, top-right (counter-clockwise from outside, except for
     * a legacy bottom face, which is mirrored and therefore wound the other way round; see [region]).
     */
    private fun corner(face: Direction, from: FloatArray, to: FloatArray, sx: Float, sy: Float, legacyBottom: Boolean, out: FloatArray, at: Int) {
        val r = right(face)
        val d = down(face, legacyBottom)
        val n = face.toVector()
        for (axis in 0 until 3) {
            val lo = from[axis]
            val hi = to[axis]
            val nAxis = when (axis) { 0 -> n.x; 1 -> n.y; else -> n.z }
            out[at + axis] = when {
                nAxis > 0 -> hi
                nAxis < 0 -> lo
                r[axis] != 0 -> if (r[axis] > 0) lo + (hi - lo) * sx else hi - (hi - lo) * sx
                else -> if (d[axis] > 0) lo + (hi - lo) * sy else hi - (hi - lo) * sy
            }
        }
    }

    /** A quad covering a screen-space region of a box face, textured with [texture]. */
    fun region(
        face: Direction,
        from: FloatArray,
        to: FloatArray,
        sx1: Float,
        sy1: Float,
        sx2: Float,
        sy2: Float,
        texture: FaceTexture,
        legacyBottom: Boolean = false,
    ): MeshQuad {
        val p = FloatArray(12)
        corner(face, from, to, sx1, sy1, legacyBottom, p, 0)
        corner(face, from, to, sx1, sy2, legacyBottom, p, 3)
        corner(face, from, to, sx2, sy2, legacyBottom, p, 6)
        corner(face, from, to, sx2, sy1, legacyBottom, p, 9)
        var uv = texture.uvs()
        if (legacyBottom && face == Direction.DOWN) {
            // the mirrored basis turns the corner order clockwise: reverse it (keeping each corner's uv)
            reverse(p, 3)
            uv = uv.copyOf().also { reverse(it, 2) }
        }
        return MeshQuad(p, uv, texture.texture, texture.rgb, texture.glow)
    }

    private fun reverse(a: FloatArray, stride: Int) {
        // corner order 0,1,2,3 -> 0,3,2,1
        for (k in 0 until stride) {
            val t = a[stride + k]
            a[stride + k] = a[3 * stride + k]
            a[3 * stride + k] = t
        }
    }

    /** A whole box face. */
    fun face(face: Direction, from: FloatArray, to: FloatArray, texture: FaceTexture, legacyBottom: Boolean = false): MeshQuad =
        region(face, from, to, 0f, 0f, 1f, 1f, texture, legacyBottom)
}

/** Builds meshes from boxes; coordinates in pixels (0..16). */
class MeshBuilder {
    private val quads = ArrayList<MeshQuad>()

    fun add(quad: MeshQuad): MeshBuilder = apply { quads.add(quad) }

    fun add(mesh: Mesh): MeshBuilder = apply { quads.addAll(mesh.quads) }

    /**
     * An axis-aligned box in pixels with one texture per face (null = no face). Without explicit UVs a face shows
     * the part of its sprite that lies under it in block space (like vanilla models without `uv`).
     */
    fun box(
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        faces: (Direction) -> FaceTexture?,
    ): MeshBuilder = apply {
        val from = floatArrayOf(x1 / 16f, y1 / 16f, z1 / 16f)
        val to = floatArrayOf(x2 / 16f, y2 / 16f, z2 / 16f)
        for (face in Faces.ALL) {
            val tex = faces(face) ?: continue
            quads.add(Faces.face(face, from, to, tex))
        }
    }

    /** A box whose faces all show the block-space region of [texture] (auto UV, like a vanilla model element). */
    fun box(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, texture: Key, rgb: Int = MeshQuad.WHITE, skip: Set<Direction> = emptySet()): MeshBuilder =
        box(x1, y1, z1, x2, y2, z2) { face -> if (face in skip) null else autoUv(face, x1, y1, z1, x2, y2, z2, texture, rgb) }

    fun build(): Mesh = Mesh(ArrayList(quads))

    companion object {
        /** The vanilla automatic UV of an element face (pixels → sprite units). */
        fun autoUv(face: Direction, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, texture: Key, rgb: Int = MeshQuad.WHITE): FaceTexture {
            val (u1, v1, u2, v2) = when (face) {
                Direction.DOWN -> floatArrayOf(x1, 16 - z2, x2, 16 - z1)
                Direction.UP -> floatArrayOf(x1, z1, x2, z2)
                Direction.NORTH -> floatArrayOf(16 - x2, 16 - y2, 16 - x1, 16 - y1)
                Direction.SOUTH -> floatArrayOf(x1, 16 - y2, x2, 16 - y1)
                Direction.WEST -> floatArrayOf(z1, 16 - y2, z2, 16 - y1)
                Direction.EAST -> floatArrayOf(16 - z2, 16 - y2, 16 - z1, 16 - y1)
            }.let { arr -> arr.map { it / 16f } }
            return FaceTexture(texture, u1, v1, u2, v2, rgb = rgb)
        }
    }
}

/** An affine transform of block-space points (row-major 3×4). */
class Transform private constructor(private val m: FloatArray) {
    fun apply(src: FloatArray, srcAt: Int, dst: FloatArray, dstAt: Int) {
        val x = src[srcAt]
        val y = src[srcAt + 1]
        val z = src[srcAt + 2]
        dst[dstAt] = m[0] * x + m[1] * y + m[2] * z + m[3]
        dst[dstAt + 1] = m[4] * x + m[5] * y + m[6] * z + m[7]
        dst[dstAt + 2] = m[8] * x + m[9] * y + m[10] * z + m[11]
    }

    /** GL-style product `this · inner`: [inner] is applied first, as with consecutive `gl*` calls. */
    operator fun times(inner: Transform): Transform {
        val a = m
        val b = inner.m
        val r = FloatArray(12)
        for (row in 0 until 3) {
            for (col in 0 until 4) {
                var v = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col]
                if (col == 3) v += a[row * 4 + 3]
                r[row * 4 + col] = v
            }
        }
        return Transform(r)
    }

    companion object {
        val IDENTITY = Transform(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f))

        fun translate(x: Float, y: Float, z: Float) = Transform(floatArrayOf(1f, 0f, 0f, x, 0f, 1f, 0f, y, 0f, 0f, 1f, z))

        fun scale(x: Float, y: Float, z: Float) = Transform(floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f))

        /** Rotation by [degrees] about the axis (x, y, z) through the origin, like `glRotatef`. */
        fun rotate(degrees: Float, x: Float, y: Float, z: Float): Transform {
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            val ux = x / len
            val uy = y / len
            val uz = z / len
            val rad = Math.toRadians(degrees.toDouble())
            val c = snap(cos(rad).toFloat())
            val s = snap(sin(rad).toFloat())
            val t = 1 - c
            return Transform(
                floatArrayOf(
                    t * ux * ux + c, t * ux * uy - s * uz, t * ux * uz + s * uy, 0f,
                    t * ux * uy + s * uz, t * uy * uy + c, t * uy * uz - s * ux, 0f,
                    t * ux * uz - s * uy, t * uy * uz + s * ux, t * uz * uz + c, 0f,
                ),
            )
        }

        /** Rotation about the vertical axis through the block centre, by [degrees] like `glRotatef(deg, 0, 1, 0)`. */
        fun rotateYCentered(degrees: Float): Transform =
            translate(0.5f, 0f, 0.5f) * rotate(degrees, 0f, 1f, 0f) * translate(-0.5f, 0f, -0.5f)

        /** The product of [transforms] in GL call order (the last one is applied first). */
        fun gl(vararg transforms: Transform): Transform = transforms.fold(IDENTITY) { acc, t -> acc * t }

        private fun snap(v: Float): Float = when {
            abs(v) < 1e-6f -> 0f
            abs(v - 1) < 1e-6f -> 1f
            abs(v + 1) < 1e-6f -> -1f
            else -> v
        }
    }
}
