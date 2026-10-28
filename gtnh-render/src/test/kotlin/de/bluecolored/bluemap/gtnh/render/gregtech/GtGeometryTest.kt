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

import de.bluecolored.bluemap.gtnh.render.QuadModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GtGeometryTest {
    private val sideNames = listOf("down", "up", "north", "south", "west", "east")

    @Test
    fun `box faces point outwards`() {
        val box = Box(0.25f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f)
        for (side in 0 until 6) {
            val normal = QuadModel.normal(box.corners(side))
            assertEquals(Side.dx(side).toFloat(), normal[0], 1e-6f, sideNames[side])
            assertEquals(Side.dy(side).toFloat(), normal[1], 1e-6f, sideNames[side])
            assertEquals(Side.dz(side).toFloat(), normal[2], 1e-6f, sideNames[side])
        }
    }

    @Test
    fun `pipe without connections is a core cube`() {
        val faces = GtPipeGeometry.faces(0.5f, 0)
        assertEquals(6, faces.size)
        val core = Box(0.25f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f)
        assertEquals((0 until 6).map { PipeFace(it, core) }, faces)
        assertTrue(faces.none { it.box.touchesBoundary(it.side) })
    }

    @Test
    fun `straight pipe reaches both block faces and hides the core ends`() {
        val connections = (1 shl Side.WEST) or (1 shl Side.EAST)
        val faces = GtPipeGeometry.faces(0.375f, connections)
        // two arms × (4 sides + end) + 4 core sides
        assertEquals(14, faces.size)
        val ends = faces.filter { it.box.touchesBoundary(it.side) }
        assertEquals(setOf(Side.WEST, Side.EAST), ends.map { it.side }.toSet())
        assertEquals(2, ends.size)
        assertTrue(faces.none { (it.side == Side.WEST || it.side == Side.EAST) && !it.box.touchesBoundary(it.side) }, "no core face towards an arm")
        // the sides cover x 0..1 without gaps: arm 0..0.3125, core 0.3125..0.6875, arm 0.6875..1
        val up = faces.filter { it.side == Side.UP }.map { it.box.x0 to it.box.x1 }.sortedBy { it.first }
        assertEquals(listOf(0f to 0.3125f, 0.3125f to 0.6875f, 0.6875f to 1f), up)
        assertTrue(faces.filter { it.side == Side.UP }.all { it.box.y1 == 0.6875f })
    }

    @Test
    fun `junction pipe draws one arm per connection`() {
        val connections = (1 shl Side.DOWN) or (1 shl Side.NORTH) or (1 shl Side.EAST)
        val faces = GtPipeGeometry.faces(0.25f, connections)
        assertEquals(3 * 5 + 3, faces.size)
        val boundary = faces.filter { it.box.touchesBoundary(it.side) }.map { it.side }.toSet()
        assertEquals(setOf(Side.DOWN, Side.NORTH, Side.EAST), boundary)
        val coreSides = faces.filter { it.box == Box(0.375f, 0.375f, 0.375f, 0.625f, 0.625f, 0.625f) }.map { it.side }.toSet()
        assertEquals(setOf(Side.UP, Side.SOUTH, Side.WEST), coreSides)
    }

    @Test
    fun `thick pipes and frames are full cubes`() {
        val faces = GtPipeGeometry.faces(1f, 0b111111)
        assertEquals(6, faces.size)
        assertTrue(faces.all { it.box == Box.FULL && it.box.touchesBoundary(it.side) })
    }

    @Test
    fun `connected texture uses GT's straight-pipe normalisation`() {
        assertTrue(GtPipeGeometry.showsConnectedTexture(0, Side.UP))
        assertTrue(GtPipeGeometry.showsConnectedTexture(1 shl Side.NORTH, Side.SOUTH))
        assertFalse(GtPipeGeometry.showsConnectedTexture(1 shl Side.NORTH, Side.UP))
        assertFalse(GtPipeGeometry.showsConnectedTexture((1 shl Side.NORTH) or (1 shl Side.UP), Side.SOUTH))
    }

    private fun uv(side: Int, x: Float, y: Float, z: Float, op: Int): Pair<Float, Float> {
        val out = FloatArray(2)
        GtUv.uv(side, x, y, z, op, out, 0)
        return out[0] to out[1]
    }

    @Test
    fun `base mapping shows textures upright and unmirrored from outside`() {
        // sprite (0, 0) is the top-left texel seen from outside the block
        assertEquals(0f to 0f, uv(Side.NORTH, 1f, 1f, 0f, 0)) // looking south at the north face, top-left is +x
        assertEquals(0f to 0f, uv(Side.SOUTH, 0f, 1f, 1f, 0))
        assertEquals(0f to 0f, uv(Side.WEST, 0f, 1f, 0f, 0))
        assertEquals(0f to 0f, uv(Side.EAST, 1f, 1f, 1f, 0))
        assertEquals(0f to 0f, uv(Side.UP, 0f, 1f, 0f, 0)) // north-west corner, north up
        // partial boxes take the UVs of their position (pipes): the east half of the north face
        assertEquals(0.25f to 0.5f, uv(Side.NORTH, 0.75f, 0.5f, 0f, 0))
    }

    @Test
    fun `rendered layers flip U on the bottom unless fixed`() {
        assertEquals(GtUv.NEG_U, GtUv.transform(Side.DOWN, UvMode.RENDERED, false, 0, 0, fixedBottomFaceUV = false))
        assertEquals(0, GtUv.transform(Side.DOWN, UvMode.RENDERED, false, 0, 0, fixedBottomFaceUV = true))
        for (side in 1 until 6) assertEquals(0, GtUv.transform(side, UvMode.RENDERED, true, 1, 1, false), sideNames[side])
        for (side in 0 until 6) assertEquals(0, GtUv.transform(side, UvMode.COPIED_BLOCK, true, 2, 3, false), sideNames[side])
    }

    @Test
    fun `extFacing layers without an alignment flip east, down and north`() {
        val ops = (0 until 6).map { GtUv.transform(it, UvMode.EXT_FACING, false, 0, 0, false) }
        assertEquals(listOf(GtUv.NEG_U, 0, GtUv.NEG_V, 0, 0, GtUv.NEG_U), ops)
    }

    private fun handedness(op: Int) = Integer.bitCount(op) % 2

    @Test
    fun `ExtendedFacing rotations turn the texture without mirroring it`() {
        for (side in 0 until 6) {
            val normal = GtUv.transform(side, UvMode.EXT_FACING, true, 0, 0, false)
            for (rotation in 0 until 4) {
                val op = GtUv.transform(side, UvMode.EXT_FACING, true, rotation, 0, false)
                assertEquals(handedness(normal), handedness(op), "${sideNames[side]} rotation $rotation")
                // a horizontal flip mirrors
                val flipped = GtUv.transform(side, UvMode.EXT_FACING, true, rotation, 1, false)
                assertEquals(1 - handedness(normal), handedness(flipped), "${sideNames[side]} rotation $rotation flipped")
                // GT ignores the vertical flip (Flip.VERTICAL) for UVs
                assertEquals(op, GtUv.transform(side, UvMode.EXT_FACING, true, rotation, 2, false))
            }
            // upside down is a half turn of the normal orientation
            assertEquals(normal xor GtUv.NEG_U xor GtUv.NEG_V, GtUv.transform(side, UvMode.EXT_FACING, true, 2, 0, false), sideNames[side])
        }
        // clockwise on the south face: sprite top-left moves to the top-right corner
        val cw = GtUv.transform(Side.SOUTH, UvMode.EXT_FACING, true, 1, 0, false)
        assertEquals(0f to 1f, uv(Side.SOUTH, 0f, 1f, 1f, cw))
        assertEquals(0f to 0f, uv(Side.SOUTH, 1f, 1f, 1f, cw))
        // clockwise and counter-clockwise are inverse quarter turns on every side
        for (side in 0 until 6) {
            val a = GtUv.transform(side, UvMode.EXT_FACING, true, 1, 0, true)
            val b = GtUv.transform(side, UvMode.EXT_FACING, true, 3, 0, true)
            assertEquals(a xor GtUv.NEG_U xor GtUv.NEG_V, b, sideNames[side])
        }
    }
}
