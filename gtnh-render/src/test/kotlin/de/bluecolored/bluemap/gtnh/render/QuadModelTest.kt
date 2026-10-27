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
package de.bluecolored.bluemap.gtnh.render

import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.StringReader

class QuadModelTest {
    private val sample = """
        {
          "textures": { "t0": "~mod:block/top", "t1": "~mod:block/side" },
          "gtnh_quads": [
            { "texture": "#t0", "cullface": "up", "tint": true,
              "vertices": [[16,16,16, 16,16], [16,16,0, 16,0], [0,16,0, 0,0], [0,16,16, 0,16]] },
            { "texture": "#t1", "color": "#804020", "light_emission": 7,
              "vertices": [[0,0,8, 0,16], [16,0,8, 16,16], [16,16,8, 16,0], [0,16,8, 0,0]] },
            { "texture": "~mod:block/extra",
              "vertices": [[0,0,0, 0,0], [16,0,16, 16,0], [16,16,16, 16,16], [0,16,0, 0,16]] }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses quads, textures and attributes`() {
        val model = QuadModel.parse(StringReader(sample))

        assertEquals(listOf(Key("~mod", "block/top"), Key("~mod", "block/side"), Key("~mod", "block/extra")), model.textures)
        assertEquals(3, model.quads.size)

        val top = model.quads[0]
        assertEquals(0, top.texture)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 1f), top.positions)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f), top.uvs)
        assertEquals(Direction.UP, top.cullface)
        assertEquals(Direction.UP, top.facing)
        assertEquals(1f, top.normalY, 1e-6f)
        assertTrue(top.tint)
        assertEquals(1f, top.r)

        val pane = model.quads[1]
        assertEquals(1, pane.texture)
        assertNull(pane.cullface)
        assertFalse(pane.tint)
        assertEquals(Direction.SOUTH, pane.facing) // counter-clockwise seen from +z
        assertEquals(0x80 / 255f, pane.r, 1e-6f)
        assertEquals(0x40 / 255f, pane.g, 1e-6f)
        assertEquals(0x20 / 255f, pane.b, 1e-6f)
        assertEquals(7, pane.lightEmission)

        // inline texture reference, diagonal (crossed-squares style) quad
        val diagonal = model.quads[2]
        assertEquals(2, diagonal.texture)
        assertEquals(0f, diagonal.normalY, 1e-6f)
    }

    @Test
    fun `rejects malformed quads`() {
        assertThrows<IllegalArgumentException> {
            QuadModel.parse(StringReader("""{"gtnh_quads": [{"texture": "#x", "vertices": [[0,0,0,0,0],[1,0,0,0,0],[1,1,0,0,0],[0,1,0,0,0]]}]}"""))
        }
        assertThrows<IllegalArgumentException> {
            QuadModel.parse(StringReader("""{"textures": {"a": "m:b"}, "gtnh_quads": [{"texture": "#a", "vertices": [[0,0,0,0,0],[1,0,0,0,0],[1,1,0,0,0]]}]}"""))
        }
        assertThrows<IllegalArgumentException> { QuadModel.parse(StringReader("""{"textures": {}}""")) }
    }
}
