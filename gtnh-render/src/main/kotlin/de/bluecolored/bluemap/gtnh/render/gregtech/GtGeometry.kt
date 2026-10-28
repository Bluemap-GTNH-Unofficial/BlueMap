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

/** ForgeDirection ordinals, which GT uses for sides and facings. */
object Side {
    const val DOWN = 0
    const val UP = 1
    const val NORTH = 2
    const val SOUTH = 3
    const val WEST = 4
    const val EAST = 5

    /** Unit vector of a side: x, y, z. */
    private val OFFSETS =
        arrayOf(
            intArrayOf(0, -1, 0),
            intArrayOf(0, 1, 0),
            intArrayOf(0, 0, -1),
            intArrayOf(0, 0, 1),
            intArrayOf(-1, 0, 0),
            intArrayOf(1, 0, 0),
        )

    fun dx(side: Int) = OFFSETS[side][0]

    fun dy(side: Int) = OFFSETS[side][1]

    fun dz(side: Int) = OFFSETS[side][2]

    fun opposite(side: Int) = side xor 1
}

/** An axis-aligned box in block units. */
data class Box(
    val x0: Float,
    val y0: Float,
    val z0: Float,
    val x1: Float,
    val y1: Float,
    val z1: Float,
) {
    /** Whether the box's face on [side] lies on the block boundary (so a full neighbour can hide it). */
    fun touchesBoundary(side: Int): Boolean =
        when (side) {
            Side.DOWN -> y0 <= 0f
            Side.UP -> y1 >= 1f
            Side.NORTH -> z0 <= 0f
            Side.SOUTH -> z1 >= 1f
            Side.WEST -> x0 <= 0f
            else -> x1 >= 1f
        }

    /**
     * The 4 corners of the box's face on [side], x/y/z interleaved, counter-clockwise seen from outside (the
     * tessellator order BlueMap's front faces use).
     */
    fun corners(side: Int): FloatArray =
        when (side) {
            Side.DOWN -> floatArrayOf(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1)
            Side.UP -> floatArrayOf(x1, y1, z1, x1, y1, z0, x0, y1, z0, x0, y1, z1)
            Side.NORTH -> floatArrayOf(x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0)
            Side.SOUTH -> floatArrayOf(x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1)
            Side.WEST -> floatArrayOf(x0, y1, z1, x0, y1, z0, x0, y0, z0, x0, y0, z1)
            else -> floatArrayOf(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1)
        }

    companion object {
        val FULL = Box(0f, 0f, 0f, 1f, 1f, 1f)
    }
}

/**
 * UV orientation of GT faces (exporter README "UV orientation"), expressed in sprite units (0..1, v down).
 *
 * A face's base mapping takes the sprite coordinates from the vertex position like 1.7.10 `RenderBlocks` with
 * `field_152631_f` set on the north and east faces (the setting every GT texture renderer uses): up and down
 * `(x, z)`, north `(1 - x, 1 - y)`, south `(x, 1 - y)`, west `(z, 1 - y)`, east `(1 - z, 1 - y)`. On top of that a
 * layer applies an element of the square's symmetry group, encoded as [SWAP] (exchange u and v first), then
 * [NEG_U] / [NEG_V] (mirror u or v).
 */
object GtUv {
    const val SWAP = 1
    const val NEG_U = 2
    const val NEG_V = 4

    private const val CW = SWAP or NEG_U
    private const val CCW = SWAP or NEG_V
    private const val HALF = NEG_U or NEG_V
    private const val TRANSPOSE = SWAP
    private const val ANTI_TRANSPOSE = SWAP or NEG_U or NEG_V

    /**
     * What `RenderBlocks.uvRotate*` 1, 2 and 3 do to a full face, per side. On north and east the rotations come
     * out mirrored (a transpose instead of a quarter turn), which GT compensates with extra flips.
     */
    private val ROTATIONS =
        arrayOf(
            intArrayOf(0, CW, CCW, HALF), // down
            intArrayOf(0, CCW, CW, HALF), // up
            intArrayOf(0, ANTI_TRANSPOSE, TRANSPOSE, HALF), // north
            intArrayOf(0, CCW, CW, HALF), // south
            intArrayOf(0, CCW, CW, HALF), // west
            intArrayOf(0, ANTI_TRANSPOSE, TRANSPOSE, HALF), // east
        )

    /** StructureLib `Rotation` ordinal (normal, clockwise, upside down, counter-clockwise) -> `uvRotate` value. */
    private val UV_ROTATE = intArrayOf(0, 1, 3, 2)

    /**
     * The transform of a layer drawn on [side].
     * [alignment]: the tile entity has an ExtendedFacing ([rotation] / [flip] are StructureLib `Rotation` / `Flip`
     * ordinals), which only `extFacing` layers follow.
     */
    fun transform(
        side: Int,
        mode: UvMode,
        alignment: Boolean,
        rotation: Int,
        flip: Int,
        fixedBottomFaceUV: Boolean,
    ): Int {
        val bottomFlip = if (side == Side.DOWN && !fixedBottomFaceUV) NEG_U else 0
        return when (mode) {
            UvMode.COPIED_BLOCK -> {
                0
            }

            // GTRenderedTexture with the default ExtendedFacing: normal rotation, no flip
            UvMode.RENDERED -> {
                bottomFlip
            }

            UvMode.EXT_FACING -> {
                if (!alignment) {
                    var op = 0
                    if (side == Side.EAST || side == Side.DOWN) op = op xor NEG_U
                    if (side == Side.NORTH) op = op xor NEG_V
                    op
                } else {
                    val r = rotation.mod(4)
                    val horizontallyFlipped = flip.mod(4).let { it == 1 || it == 3 } // HORIZONTAL, BOTH
                    var op = ROTATIONS[side][UV_ROTATE[r]]
                    if (r % 2 == 0) {
                        if (horizontallyFlipped) op = op xor NEG_U
                    } else {
                        if (horizontallyFlipped) op = op xor NEG_V
                        if (side == Side.EAST || side == Side.NORTH) op = op xor NEG_U
                    }
                    op xor bottomFlip
                }
            }
        }
    }

    /** Sprite (u, v) of position (x, y, z) on [side] under transform [op], written to [out] at [offset]. */
    fun uv(
        side: Int,
        x: Float,
        y: Float,
        z: Float,
        op: Int,
        out: FloatArray,
        offset: Int,
    ) {
        var u: Float
        var v: Float
        when (side) {
            Side.DOWN, Side.UP -> {
                u = x
                v = z
            }

            Side.NORTH -> {
                u = 1f - x
                v = 1f - y
            }

            Side.SOUTH -> {
                u = x
                v = 1f - y
            }

            Side.WEST -> {
                u = z
                v = 1f - y
            }

            else -> {
                u = 1f - z
                v = 1f - y
            }
        }
        if (op and SWAP != 0) {
            val t = u
            u = v
            v = t
        }
        if (op and NEG_U != 0) u = 1f - u
        if (op and NEG_V != 0) v = 1f - v
        out[offset] = u
        out[offset + 1] = v
    }
}

/**
 * One face GT draws for a pipe: [side] of [box], showing the pipe's texture for [side]
 * (`MetaPipeEntity.renderInWorld`).
 */
data class PipeFace(
    val side: Int,
    val box: Box,
)

object GtPipeGeometry {
    /**
     * Faces of a pipe of [thickness] with connection bitmask [connections] (bit `1 << side`): a core cube, and per
     * connected side an arm to the block boundary with its four side faces and an end face; the core shows its face
     * only towards unconnected sides. Thickness >= 1 is a full cube.
     */
    fun faces(
        thickness: Float,
        connections: Int,
    ): List<PipeFace> {
        if (thickness >= 1f) return (0 until 6).map { PipeFace(it, Box.FULL) }
        val min = (1f - thickness) / 2f
        val max = 1f - min
        val core = Box(min, min, min, max, max, max)
        val faces = ArrayList<PipeFace>(30)
        for (side in 0 until 6) {
            if (connections and (1 shl side) == 0) {
                faces.add(PipeFace(side, core))
                continue
            }
            val arm =
                when (side) {
                    Side.DOWN -> Box(min, 0f, min, max, min, max)
                    Side.UP -> Box(min, max, min, max, 1f, max)
                    Side.NORTH -> Box(min, min, 0f, max, max, min)
                    Side.SOUTH -> Box(min, min, max, max, max, 1f)
                    Side.WEST -> Box(0f, min, min, min, max, max)
                    else -> Box(max, min, min, 1f, max, max)
                }
            for (armSide in 0 until 6) {
                // the arm's face towards the core is inside the pipe
                if (armSide == Side.opposite(side)) continue
                faces.add(PipeFace(armSide, arm))
            }
        }
        return faces
    }

    /**
     * Whether GT draws the "connected" (end) texture on [side]: a single connection counts as a straight pipe through
     * the block, and a pipe without connections shows its end texture everywhere (`BaseMetaPipeEntity.getTextureUncovered`).
     */
    fun showsConnectedTexture(
        connections: Int,
        side: Int,
    ): Boolean {
        val textureConnections =
            when (connections) {
                1 shl Side.WEST, 1 shl Side.EAST -> (1 shl Side.WEST) or (1 shl Side.EAST)
                1 shl Side.DOWN, 1 shl Side.UP -> (1 shl Side.DOWN) or (1 shl Side.UP)
                1 shl Side.NORTH, 1 shl Side.SOUTH -> (1 shl Side.NORTH) or (1 shl Side.SOUTH)
                else -> connections
            }
        return textureConnections == 0 || textureConnections and (1 shl side) != 0
    }
}
