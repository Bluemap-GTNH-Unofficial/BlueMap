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
import de.bluecolored.bluemap.gtnh.render.mods.chisel.ChiselCtm
import de.bluecolored.bluemap.gtnh.render.mods.chisel.Neighbours
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Golden values for [ChiselCtm.column] and [ChiselCtm.horizontalCell], recorded from the implementation they replaced:
 * every combination of the neighbours either function looks at.
 */
class ChiselCtmGoldenTest {
    private class Probe(
        private val offsets: List<Triple<Int, Int, Int>>,
    ) : Neighbours {
        private val bitOf = IntArray(27) { -1 }.also { bits -> offsets.forEachIndexed { i, (x, y, z) -> bits[slot(x, y, z)] = i } }
        val outside = HashSet<Triple<Int, Int, Int>>()
        var mask = 0

        private fun slot(
            dx: Int,
            dy: Int,
            dz: Int,
        ) = (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)

        override fun same(
            dx: Int,
            dy: Int,
            dz: Int,
        ): Boolean {
            val bit = if (dx in -1..1 && dy in -1..1 && dz in -1..1) bitOf[slot(dx, dy, dz)] else -1
            if (bit < 0) {
                outside.add(Triple(dx, dy, dz))
                return false
            }
            return mask and (1 shl bit) != 0
        }
    }

    @Test
    fun `pillar selection matches the recorded output for every relevant neighbourhood`() {
        val probe = Probe(COLUMN_OFFSETS)
        val cells = IntArray(6)
        val rotations = IntArray(6)
        val digest = MessageDigest.getInstance("SHA-256")
        val counts = sortedMapOf<String, Int>()
        val bytes = ByteArray(12)
        for (mask in 0 until (1 shl COLUMN_OFFSETS.size)) {
            probe.mask = mask
            ChiselCtm.column(probe, cells, rotations)
            for (i in 0 until 6) {
                bytes[i] = cells[i].toByte()
                bytes[6 + i] = rotations[i].toByte()
            }
            digest.update(bytes)
            val key = "${cells.joinToString(",")}|${rotations.joinToString(",")}"
            counts[key] = (counts[key] ?: 0) + 1
        }
        // only these 18 offsets are ever looked at, so the enumeration covers every neighbourhood
        assertEquals(emptySet<Triple<Int, Int, Int>>(), probe.outside)
        // cells then rotations per face in Direction order (UP, DOWN, NORTH, SOUTH, WEST, EAST) -> configurations
        assertEquals(
            sortedMapOf(
                "-1,-1,0,0,0,0|0,0,0,0,0,0" to 47089,
                "-1,-1,1,1,1,1|0,0,0,0,0,0" to 65536,
                "-1,-1,2,2,2,2|0,0,0,0,0,0" to 65536,
                "-1,-1,3,3,3,3|0,0,0,0,0,0" to 65536,
                "1,1,-1,-1,1,1|0,0,0,0,3,1" to 1519,
                "1,1,1,1,-1,-1|1,1,3,1,0,0" to 7168,
                "2,2,-1,-1,2,2|0,0,0,0,3,1" to 49,
                "2,2,2,2,-1,-1|1,1,3,1,0,0" to 1024,
                "3,3,-1,-1,3,3|0,0,0,0,3,1" to 1519,
                "3,3,3,3,-1,-1|1,1,3,1,0,0" to 7168,
            ),
            counts,
        )
        // configuration order: mask bit i set = COLUMN_OFFSETS[i] is the same block; 12 bytes (cells, rotations) each
        assertEquals(
            "ca2c65d094f6d6714395602ce8a3c6bef0f10e2458bc697aeda8de404d6d7a16",
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `horizontal selection matches the recorded output for every in-plane neighbourhood`() {
        val probe = Probe(PLANE_OFFSETS)
        // per face, character `mask` = cell for neighbourhood `mask` (bit i set = PLANE_OFFSETS[i] is the same block)
        val expected =
            mapOf(
                Direction.UP to "-".repeat(256),
                Direction.DOWN to "-".repeat(256),
                Direction.NORTH to
                    "0000000020202020333300001313202000000000202020203333000013132020" +
                    "0000000020202020333300001313202000000000202020203333000013132020" +
                    "0000000020202020333300001313202000000000202020203333000013132020" +
                    "0000000020202020333300001313202000000000202020203333000013132020",
                Direction.SOUTH to
                    "0000000033333333222222221111111100000000000000002222222222222222" +
                    "0000000033333333222222221111111100000000000000002222222222222222" +
                    "0000000033333333000000003333333300000000000000000000000000000000" +
                    "0000000033333333000000003333333300000000000000000000000000000000",
                Direction.WEST to
                    "0030003000300030003000300030003000300030003000300030003000300030" +
                    "2212221222122212221222122212221200300030003000300030003000300030" +
                    "0030003000300030003000300030003000300030003000300030003000300030" +
                    "2212221222122212221222122212221200300030003000300030003000300030",
                Direction.EAST to
                    "0022000000220000002200000022000000220000002200000022000000220000" +
                    "3311333333113333331133333311333333113333331133333311333333113333" +
                    "0022000000220000002200000022000000220000002200000022000000220000" +
                    "0022000000220000002200000022000000220000002200000022000000220000",
            )
        for (face in Direction.entries) {
            val actual = StringBuilder()
            for (mask in 0 until (1 shl PLANE_OFFSETS.size)) {
                probe.mask = mask
                val cell = ChiselCtm.horizontalCell(face, probe)
                actual.append(if (cell < 0) '-' else '0' + cell)
            }
            assertEquals(expected[face], actual.toString(), "$face")
        }
        assertEquals(emptySet<Triple<Int, Int, Int>>(), probe.outside)
    }

    private companion object {
        val COLUMN_OFFSETS =
            listOf(
                Triple(0, 1, 0),
                Triple(0, -1, 0),
                Triple(1, 0, 0),
                Triple(-1, 0, 0),
                Triple(1, 1, 0),
                Triple(1, -1, 0),
                Triple(-1, 1, 0),
                Triple(-1, -1, 0),
                Triple(0, 0, 1),
                Triple(0, 0, -1),
                Triple(0, 1, 1),
                Triple(0, -1, 1),
                Triple(1, 0, 1),
                Triple(-1, 0, 1),
                Triple(0, 1, -1),
                Triple(0, -1, -1),
                Triple(1, 0, -1),
                Triple(-1, 0, -1),
            )
        val PLANE_OFFSETS =
            listOf(
                Triple(-1, 0, -1),
                Triple(0, 0, -1),
                Triple(1, 0, -1),
                Triple(-1, 0, 0),
                Triple(1, 0, 0),
                Triple(-1, 0, 1),
                Triple(0, 0, 1),
                Triple(1, 0, 1),
            )
    }
}
