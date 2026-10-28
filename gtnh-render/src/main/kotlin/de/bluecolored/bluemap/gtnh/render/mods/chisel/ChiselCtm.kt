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
package de.bluecolored.bluemap.gtnh.render.mods.chisel

import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.math.Axis
import de.bluecolored.bluemap.gtnh.render.mods.Faces
import java.util.Random

/** Whether the block at an offset from the rendered block connects (same block and meta). */
fun interface Neighbours {
    fun same(
        dx: Int,
        dy: Int,
        dz: Int,
    ): Boolean
}

/**
 * Position-dependent texture selection for Chisel 2 blocks, expressed in each face's screen basis ([Faces.right] /
 * [Faces.down]).
 *
 * The connected-texture layouts follow Chisel's texture sheets (GPL-2.0, Chisel Team / GTNH fork); the
 * implementation is written independently from a behaviour spec.
 */
object ChiselCtm {
    /** Chisel's quadrant order: bottom-left, bottom-right, top-right, top-left (screen regions sx1, sy1, sx2, sy2). */
    val QUADRANTS: Array<FloatArray> =
        arrayOf(
            floatArrayOf(0f, 0.5f, 0.5f, 1f),
            floatArrayOf(0.5f, 0.5f, 1f, 1f),
            floatArrayOf(0.5f, 0f, 1f, 0.5f),
            floatArrayOf(0f, 0f, 0.5f, 0.5f),
        )

    private val SUBMAP_OFFSETS = intArrayOf(4, 5, 1, 0)
    private val UNCONNECTED = intArrayOf(18, 19, 17, 16)

    /**
     * CTMX: the submap index of each quadrant ([QUADRANTS] order) of [face]: 0..15 = cell of the 4×4 `-ctm` sheet,
     * 16..19 = quarter of the base tile. A neighbour connects when it is the same block and the block in front of
     * it (on the face's side) is not (Chisel's obscured-face check).
     */
    fun ctmIndices(
        face: Direction,
        neighbours: Neighbours,
        out: IntArray = IntArray(4),
    ): IntArray {
        val n = face.toVector()
        val r = Faces.right(face)
        val d = Faces.down(face)

        fun connected(
            h: Int,
            v: Int,
        ): Boolean {
            // h: +1 right / -1 left, v: +1 up / -1 down
            val dx = r[0] * h - d[0] * v
            val dy = r[1] * h - d[1] * v
            val dz = r[2] * h - d[2] * v
            return neighbours.same(dx, dy, dz) && !neighbours.same(dx + n.x, dy + n.y, dz + n.z)
        }
        val top = connected(0, 1)
        val bottom = connected(0, -1)
        val left = connected(-1, 0)
        val right = connected(1, 0)
        for (i in 0 until 4) {
            // quadrant i combines its vertical edge (bottom for 0/1, top for 2/3), its horizontal edge (left for 0/3,
            // right for 1/2) and the diagonal neighbour between the two
            val vertical = if (i < 2) bottom else top
            val horizontal = if (i == 0 || i == 3) left else right
            out[i] =
                when {
                    !vertical && !horizontal -> UNCONNECTED[i]
                    vertical && horizontal && connected(if (i == 0 || i == 3) -1 else 1, if (i < 2) -1 else 1) -> SUBMAP_OFFSETS[i]
                    else -> SUBMAP_OFFSETS[i] + (if (vertical) 2 else 0) + (if (horizontal) 8 else 0)
                }
        }
        return out
    }

    /** V9/V4: the sheet cell (`col + row * size`) of [face] at block x/y/z (`TextureType.getVIcon`). */
    fun variationCell(
        size: Int,
        x: Int,
        y: Int,
        z: Int,
        face: Direction,
    ): Int {
        var tx = Math.floorMod(x, size)
        var tz = Math.floorMod(z, size)
        val ty = size - Math.floorMod(y, size) - 1
        if (face == Direction.NORTH || face == Direction.EAST) {
            tx = size - tx - 1
            tz = size - tz - 1
        }
        return when (face) {
            Direction.DOWN, Direction.UP -> tx + tz * size
            Direction.NORTH, Direction.SOUTH -> tx + ty * size
            Direction.WEST, Direction.EAST -> tz + ty * size
        }
    }

    /** R16/R9/R4: the sheet cell of the block at x/y/z (`TextureType.getRIcon`, the same on every face). */
    fun randomCell(
        size: Int,
        x: Int,
        y: Int,
        z: Int,
    ): Int {
        var l = (x * 3129871).toLong() xor (z * 116129781L) xor y.toLong()
        l = l * l * 42317861L + l * 11L
        val rand = Random(l)
        rand.nextBoolean()
        val col = rand.nextInt(size)
        val row = rand.nextInt(size)
        return col + row * size
    }

    /** Cell value standing for the block's end texture (`top`) instead of a sheet cell. */
    private const val END_TEXTURE = -1

    // CTMH 2×2 sheet cells (index = column + row * 2)
    private const val ROW_ALONE = 0
    private const val ROW_MIDDLE = 1
    private const val ROW_RIGHT_ONLY = 2
    private const val ROW_LEFT_ONLY = 3

    // CTMV 2×2 sheet cells (index = column + row * 2), named by the sheet's "up"
    private const val PILLAR_ALONE = 0
    private const val PILLAR_UPPER_END = 1
    private const val PILLAR_MIDDLE = 2
    private const val PILLAR_LOWER_END = 3

    /** Screen-right step of every face, by [Direction] ordinal. */
    private val SCREEN_RIGHT: Array<IntArray> = Array(6) { Faces.right(Direction.entries[it]) }

    /**
     * CTMH: the 2×2 sheet cell of a side face, or -1 for the end texture on up/down faces.
     *
     * A side face looks at the blocks directly to its screen left and right. Such a neighbour joins when it is the
     * same block and its own copy of this face is exposed (the block one step in front of it is not the same block).
     * Neither side joined: cell 0; both: cell 1 (the middle of a row); only the right one: cell 2; only the left
     * one: cell 3.
     */
    fun horizontalCell(
        face: Direction,
        neighbours: Neighbours,
    ): Int {
        if (face.axis == Axis.Y) return END_TEXTURE
        val right = SCREEN_RIGHT[face.ordinal]
        val front = face.toVector()

        fun joinsAt(side: Int): Boolean {
            val dx = right[0] * side
            val dz = right[2] * side
            return neighbours.same(dx, 0, dz) && !neighbours.same(dx + front.x, front.y, dz + front.z)
        }
        val toRight = joinsAt(1)
        val toLeft = joinsAt(-1)
        return when {
            toRight && toLeft -> ROW_MIDDLE
            toRight -> ROW_RIGHT_ONLY
            toLeft -> ROW_LEFT_ONLY
            else -> ROW_ALONE
        }
    }

    /**
     * One axis a pillar can run along: [up] is the direction the sheet's top points to, [claimedBy] the directions
     * of the axes that take priority over this one.
     */
    private class PillarAxis(
        val axis: Axis,
        val up: Direction,
        private vararg val claimedBy: Direction,
    ) {
        /** Clockwise texture turns per face that point the sheet's top along [up] (0 on the end faces). */
        val rotations = IntArray(6)

        init {
            val upVector = up.toVector().let { intArrayOf(it.x, it.y, it.z) }
            for (face in Direction.entries) {
                if (face.axis != axis) rotations[face.ordinal] = rotationTowards(face, upVector, legacyBottom = true)
            }
        }

        /**
         * Whether the neighbour one step towards [step] continues this pillar: it is the same block and does not
         * itself have a same-block neighbour along a higher-priority axis.
         */
        fun continuesTowards(
            neighbours: Neighbours,
            step: Direction,
        ): Boolean {
            val s = step.toVector()
            if (!neighbours.same(s.x, s.y, s.z)) return false
            return claimedBy.none { c ->
                val v = c.toVector()
                neighbours.same(s.x + v.x, s.y + v.y, s.z + v.z)
            }
        }
    }

    /** Pillar axes in priority order: vertical, then east-west (top east), then north-south (top north). */
    private val PILLAR_AXES =
        arrayOf(
            PillarAxis(Axis.Y, Direction.UP),
            PillarAxis(Axis.X, Direction.EAST, Direction.UP, Direction.DOWN),
            PillarAxis(Axis.Z, Direction.NORTH, Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST),
        )

    /**
     * CTMV pillars: per face (BlueMap [Direction] ordinal) the 2×2 sheet cell, or -1 for the end texture, and the
     * clockwise texture rotation that turns the sheet's top along the pillar.
     *
     * Behaviour:
     * - A pillar runs along one axis, chosen by priority: vertical, then east-west, then north-south. The first
     *   axis with a continuing neighbour on either side wins; with none the block stands alone (cell 0 on the
     *   sides, end texture on top and bottom, no rotation).
     * - A neighbour along the axis continues the pillar when it is the same block. For a horizontal axis it must also
     *   not belong to a pillar of a higher-priority axis: an east/west neighbour with the same block above or below
     *   it does not count, nor does a north/south neighbour with the same block above, below, east or west of it.
     * - The two faces the axis passes through show the end texture; the four faces around it show the cell for the
     *   block's place in the pillar: 2 when it continues both ways, 3 when it continues only towards the sheet's top
     *   (it is the lower end), 1 when only towards the sheet's bottom (the upper end).
     * - The sheet's top points up for vertical pillars, east for east-west and north for north-south ones; the side
     *   faces of horizontal pillars are rotated so the texture's top points that way (1.7 bottom-face orientation).
     */
    fun column(
        neighbours: Neighbours,
        cells: IntArray = IntArray(6),
        rotations: IntArray = IntArray(6),
    ) {
        var pillar = PILLAR_AXES[0]
        var cell = PILLAR_ALONE
        for (candidate in PILLAR_AXES) {
            val towardsTop = candidate.continuesTowards(neighbours, candidate.up)
            val towardsBottom = candidate.continuesTowards(neighbours, candidate.up.opposite)
            if (!towardsTop && !towardsBottom) continue
            pillar = candidate
            cell =
                when {
                    towardsTop && towardsBottom -> PILLAR_MIDDLE
                    towardsTop -> PILLAR_LOWER_END
                    else -> PILLAR_UPPER_END
                }
            break
        }
        for (face in Direction.entries) cells[face.ordinal] = if (face.axis == pillar.axis) END_TEXTURE else cell
        pillar.rotations.copyInto(rotations)
    }

    /** Clockwise quarter turns that make a texture's top point along [up] on [face]. */
    fun rotationTowards(
        face: Direction,
        up: IntArray,
        legacyBottom: Boolean,
    ): Int {
        val r = Faces.right(face)
        val d = Faces.down(face, legacyBottom)
        // rotation 0: top = -down, 1: top = right, 2: top = down, 3: top = -right
        return when {
            up.contentEquals(intArrayOf(-d[0], -d[1], -d[2])) -> 0
            up.contentEquals(r) -> 1
            up.contentEquals(d) -> 2
            else -> 3
        }
    }
}
