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
import de.bluecolored.bluemap.gtnh.render.mods.ModTestKit.Cell
import de.bluecolored.bluemap.gtnh.render.mods.ModTestKit.Companion.state
import de.bluecolored.bluemap.gtnh.render.mods.chisel.ChiselCtm
import de.bluecolored.bluemap.gtnh.render.mods.chisel.Neighbours
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.abs

class ChiselCtmTest {
    private fun neighbours(vararg offsets: Triple<Int, Int, Int>): Neighbours {
        val set = offsets.toSet()
        return Neighbours { dx, dy, dz -> Triple(dx, dy, dz) in set }
    }

    @Test
    fun `an isolated block shows the base tile quarters on every face`() {
        for (face in Direction.entries) {
            assertArrayEquals(intArrayOf(18, 19, 17, 16), ChiselCtm.ctmIndices(face, neighbours()), "$face")
        }
    }

    @Test
    fun `a horizontal row uses the horizontal strip, its end the strip's border`() {
        val row = neighbours(Triple(-1, 0, 0), Triple(1, 0, 0))
        // south face: screen right = +x; quadrants BL, BR, TR, TL = 4+8, 5+8, 1+8, 0+8
        assertArrayEquals(intArrayOf(12, 13, 9, 8), ChiselCtm.ctmIndices(Direction.SOUTH, row))
        // north face: screen right = -x, so the same row is connected on both sides as well
        assertArrayEquals(intArrayOf(12, 13, 9, 8), ChiselCtm.ctmIndices(Direction.NORTH, row))
        // the east end of the row (only -x connected): on the south face that is the left side
        val end = neighbours(Triple(-1, 0, 0))
        assertArrayEquals(intArrayOf(12, 19, 17, 8), ChiselCtm.ctmIndices(Direction.SOUTH, end))
        assertArrayEquals(intArrayOf(18, 13, 9, 16), ChiselCtm.ctmIndices(Direction.NORTH, end))
        // the row's top faces connect along x too, the west/east faces see nothing in their plane
        assertArrayEquals(intArrayOf(12, 13, 9, 8), ChiselCtm.ctmIndices(Direction.UP, row))
        assertArrayEquals(intArrayOf(18, 19, 17, 16), ChiselCtm.ctmIndices(Direction.EAST, row))
    }

    @Test
    fun `a wall interior is fully connected, a missing diagonal makes an inner corner`() {
        val all = ArrayList<Triple<Int, Int, Int>>()
        for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) all.add(Triple(dx, dy, 0))
        assertArrayEquals(intArrayOf(4, 5, 1, 0), ChiselCtm.ctmIndices(Direction.SOUTH, neighbours(*all.toTypedArray())))
        val noTopRight = neighbours(*all.filter { it != Triple(1, 1, 0) }.toTypedArray())
        // top-right quadrant: top and right connected, diagonal not -> 1 + 2 + 8
        assertArrayEquals(intArrayOf(4, 5, 11, 0), ChiselCtm.ctmIndices(Direction.SOUTH, noTopRight))
    }

    @Test
    fun `a neighbour whose face is covered by the same block does not connect (obscured face)`() {
        // block to the east, and another one in front of it (south): the east neighbour's south face is hidden
        val obscured = neighbours(Triple(1, 0, 0), Triple(1, 0, 1))
        assertArrayEquals(intArrayOf(18, 19, 17, 16), ChiselCtm.ctmIndices(Direction.SOUTH, obscured))
    }

    @Test
    fun `top face screen up is north, bottom face screen up is south`() {
        val north = neighbours(Triple(0, 0, -1))
        // UP: north is "top" -> TR/TL quadrants vertical: 1+2, 0+2
        assertArrayEquals(intArrayOf(18, 19, 3, 2), ChiselCtm.ctmIndices(Direction.UP, north))
        // DOWN: north is "bottom" -> BL/BR vertical: 4+2, 5+2
        assertArrayEquals(intArrayOf(6, 7, 17, 16), ChiselCtm.ctmIndices(Direction.DOWN, north))
    }

    @Test
    fun `position variation follows Chisel's getVIcon`() {
        // south face, y=0 -> row size-1; north/east reverse the horizontal index
        assertEquals(6, ChiselCtm.variationCell(3, 0, 0, 0, Direction.SOUTH))
        assertEquals(8, ChiselCtm.variationCell(3, 0, 0, 0, Direction.NORTH))
        assertEquals(1 + 0 * 3, ChiselCtm.variationCell(3, 1, 2, 5, Direction.SOUTH))
        assertEquals(1 + 2 * 3, ChiselCtm.variationCell(3, 1, 7, 2, Direction.UP))
        // negative coordinates wrap like Chisel's "(m < 0) ? m + size : m"
        assertEquals(ChiselCtm.variationCell(3, 2, 0, 0, Direction.SOUTH), ChiselCtm.variationCell(3, -1, 0, 0, Direction.SOUTH))
        // neighbouring blocks along a face use consecutive cells
        assertEquals(ChiselCtm.variationCell(2, 0, 4, 0, Direction.WEST) + 1, ChiselCtm.variationCell(2, 0, 4, 1, Direction.WEST))
    }

    @Test
    fun `random tiles are stable per position and use the whole sheet`() {
        val cells = HashSet<Int>()
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val cell = ChiselCtm.randomCell(4, x, 64, z)
                assertEquals(cell, ChiselCtm.randomCell(4, x, 64, z))
                assertTrue(cell in 0 until 16)
                cells.add(cell)
            }
        }
        assertEquals(16, cells.size)
        // the reference: Chisel seeds java.util.Random with an MC 1.8 coordinate hash and draws a boolean first
        val l0 = (5 * 3129871).toLong() xor (7 * 116129781L) xor 64L
        val rand = java.util.Random(l0 * l0 * 42317861L + l0 * 11L).also { it.nextBoolean() }
        val col = rand.nextInt(3)
        val row = rand.nextInt(3)
        assertEquals(col + row * 3, ChiselCtm.randomCell(3, 5, 64, 7))
    }

    @Test
    fun `pillars connect vertically first, horizontal pillars rotate the sheet along their axis`() {
        val cells = IntArray(6)
        val rotations = IntArray(6)
        ChiselCtm.column(neighbours(Triple(0, 1, 0), Triple(0, -1, 0)), cells, rotations)
        assertEquals(listOf(-1, -1, 2, 2, 2, 2), cells.toList(), "UP, DOWN ends; sides middle cell (0, 1)")
        assertEquals(listOf(0, 0, 0, 0, 0, 0), rotations.toList())

        ChiselCtm.column(neighbours(Triple(0, 1, 0)), cells, rotations)
        assertEquals(3, cells[Direction.SOUTH.ordinal], "connected above: (1, 1)")

        ChiselCtm.column(neighbours(Triple(1, 0, 0)), cells, rotations)
        assertEquals(-1, cells[Direction.EAST.ordinal])
        assertEquals(-1, cells[Direction.WEST.ordinal])
        assertEquals(3, cells[Direction.SOUTH.ordinal], "connected towards +x, the sheet's up")
        // the sheet's top must point east: south face right = +x (1 turn), north face right = -x (3 turns)
        assertEquals(1, rotations[Direction.SOUTH.ordinal])
        assertEquals(3, rotations[Direction.NORTH.ordinal])
        assertEquals(1, rotations[Direction.UP.ordinal])

        // an x neighbour that is itself part of a vertical pillar does not make this one horizontal
        ChiselCtm.column(neighbours(Triple(1, 0, 0), Triple(1, 1, 0)), cells, rotations)
        assertEquals(listOf(-1, -1, 0, 0, 0, 0), cells.toList())
    }

    @Test
    fun `horizontal connections on the sides, top texture on top and bottom`() {
        assertEquals(-1, ChiselCtm.horizontalCell(Direction.UP, neighbours()))
        assertEquals(0, ChiselCtm.horizontalCell(Direction.NORTH, neighbours()))
        val both = neighbours(Triple(-1, 0, 0), Triple(1, 0, 0))
        assertEquals(1, ChiselCtm.horizontalCell(Direction.NORTH, both))
        // only -x: screen right of the north face (cell (0, 1)), screen left of the south face (cell (1, 1))
        assertEquals(2, ChiselCtm.horizontalCell(Direction.NORTH, neighbours(Triple(-1, 0, 0))))
        assertEquals(3, ChiselCtm.horizontalCell(Direction.SOUTH, neighbours(Triple(-1, 0, 0))))
    }

    @TempDir
    lateinit var root: Path

    @Test
    fun `renders CTM quadrants from chisel json through the real pack, falls back to the model without data`() {
        val kit = ModTestKit(root)
        kit.png("assets/chisel/textures/block/factory/dots.png", 0x808080)
        kit.png("assets/chisel/textures/block/factory/dots-ctm.png", 0x404040, 32, 32)
        kit.png("assets/chisel/textures/block/factory/plating-v9.png", 0x202020, 48, 48)
        kit.cubeModel("assets/chisel/models/block/factoryblock/bbox_0.json", "chisel:block/factory/dots")
        kit.file(
            "assets/chisel/blockstates/factoryblock.json",
            """{"variants": {
                "meta=0": {"renderer": "gtnh:chisel_ctm", "model": "chisel:block/factoryblock/bbox_0"},
                "meta=1": {"renderer": "gtnh:chisel_ctm", "model": "chisel:block/factoryblock/bbox_0"},
                "meta=2": {"renderer": "gtnh:chisel_ctm", "model": "chisel:block/factoryblock/bbox_0"}}}""",
        )
        kit.file(
            "gtnh/mods/chisel.json",
            """{"format": 1, "blocks": {"chisel:factoryblock": {
                "0": {"type": "CTMX", "fullCube": true, "textures": {"base": "chisel:block/factory/dots", "ctm": "chisel:block/factory/dots-ctm"}},
                "1": {"type": "V9", "fullCube": true, "textures": {"sheet": "chisel:block/factory/plating-v9"}}}}}""",
        )
        val ctm = state("chisel:factoryblock", 0)
        assertSame(ModRenderers.CHISEL_CTM, kit.render(Cell(ctm)).variant.renderer)

        // alone: 6 faces x 4 quadrants x 2 triangles, base texture only
        val alone = kit.render(Cell(ctm)).triangles
        assertEquals(48, alone.size)
        assertTrue(alone.all { it.texture == kit.id(Key("chisel", "block/factory/dots")) })

        // neighbour to the east: the shared face is culled? no (not a culling model) - but faces facing it connect
        val row = kit.render(Cell(ctm), Triple(1, 0, 0) to Cell(ctm)).triangles
        val south = row.filter { it.facing == Triple(0, 0, 1) }
        assertEquals(8, south.size)
        val sheet = kit.id(Key("chisel", "block/factory/dots-ctm"))
        val right = south.filter { it.center(0) > 0.5f }
        assertTrue(right.all { it.texture == sheet }, "the right half connects")
        // BR quadrant = cell 13 (col 1, row 3) of the 4x4 sheet
        val br = right.filter { it.center(1) < 0.5f }
        assertTrue(br.all { abs(it.uvCenter(0) - 0.375f) < 0.1f && abs(it.uvCenter(1) - 0.875f) < 0.1f })
        assertTrue(south.filter { it.center(0) < 0.5f }.all { it.texture == kit.id(Key("chisel", "block/factory/dots")) })

        // V9: one face per side showing one ninth of the sheet
        val v9 = kit.render(Cell(state("chisel:factoryblock", 1))).triangles
        assertEquals(12, v9.size)
        for (t in v9) {
            val us = listOf(t.uvs[0], t.uvs[2], t.uvs[4])
            assertTrue(us.max() - us.min() <= 1f / 3 + 1e-4f, "one cell wide")
        }

        // meta 2 has no chisel.json entry: the variant's model is drawn (6 full faces)
        val plain = kit.render(Cell(state("chisel:factoryblock", 2))).triangles
        assertEquals(12, plain.size)
    }
}
