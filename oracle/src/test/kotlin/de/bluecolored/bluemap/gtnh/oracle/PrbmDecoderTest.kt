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

import de.bluecolored.bluemap.core.map.hires.ArrayTileModel
import de.bluecolored.bluemap.core.map.hires.PRBMWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PrbmDecoderTest {
    @Test
    fun `decodes the sorted tile model with the webapp's quantisation`() {
        val model = ArrayTileModel(1)
        model.add(3)
        for (face in 0 until 3) {
            val z = face.toFloat()
            model.setPositions(face, 0f, 0f, z, 1f, 0f, z, 1f, 1f, z)
            model.setUvs(face, 0f, 1f, 1f, 1f, 1f, 0f)
            model.setAOs(face, 1f, 0.5f, 0.25f)
            model.setSunlight(face, 15 - face)
            model.setBlocklight(face, face)
        }
        model.setColor(0, 0.999f, 0.5f, 0.2f)
        model.setColor(1, 1f, 1f, 1f)
        model.setColor(2, 1.2f, 0f, 1f)
        model.setMaterialIndex(0, 7)
        model.setMaterialIndex(1, 3)
        model.setMaterialIndex(2, 7)
        model.sort()

        val bytes = ByteArrayOutputStream()
        PRBMWriter(bytes).use { it.write(model) }
        val mesh = PrbmDecoder.decode(bytes.toByteArray())

        // stable sort by material: face 1 first, then faces 0 and 2 in their order
        assertArrayEquals(intArrayOf(3, 7, 7), mesh.materials)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f), mesh.positions.copyOfRange(0, 9))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f), mesh.positions.copyOfRange(9, 18))
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f), mesh.uvs.copyOfRange(0, 6))

        // vertex colour and AO arrive as normalised bytes: (int) (value * 255) & 0xFF; colours repeat per vertex
        assertArrayEquals(FloatArray(9) { 1f }, mesh.colors.copyOfRange(0, 9))
        val second = floatArrayOf(254 / 255f, 127 / 255f, 51 / 255f)
        assertArrayEquals(second + second + second, mesh.colors.copyOfRange(9, 18))
        assertArrayEquals(floatArrayOf(50 / 255f, 0f, 1f), mesh.colors.copyOfRange(18, 21), "1.2 wraps around like in the webapp")
        assertArrayEquals(floatArrayOf(1f, 127 / 255f, 63 / 255f), mesh.aos.copyOfRange(0, 3))

        // light per vertex, unnormalised
        assertArrayEquals(floatArrayOf(14f, 14f, 14f, 15f, 15f, 15f, 13f, 13f, 13f), mesh.sunlight)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f, 2f, 2f, 2f), mesh.blocklight)
        assertEquals(3, mesh.triangleCount)
    }

    @Test
    fun `an empty model decodes to no triangles`() {
        val bytes = ByteArrayOutputStream()
        PRBMWriter(bytes).use { it.write(ArrayTileModel(1)) }
        assertEquals(0, PrbmDecoder.decode(bytes.toByteArray()).triangleCount)
    }
}
