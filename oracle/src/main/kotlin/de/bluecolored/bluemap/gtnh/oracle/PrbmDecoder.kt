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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a hires tile model written by BlueMap's `PRBMWriter` the way the webapp's `PRBMLoader.js` does
 * (WebGL attribute normalisation included), so the rasteriser sees exactly the quantised vertex colours, AO and
 * light values and the material groups the browser gets.
 */
object PrbmDecoder {
    private const val FLOAT32 = 1
    private const val INT8 = 3
    private const val INT16 = 4
    private const val INT32 = 6
    private const val UINT8 = 7
    private const val UINT16 = 8
    private const val UINT32 = 10
    private val ENCODINGS = setOf(FLOAT32, INT8, INT16, INT32, UINT8, UINT16, UINT32)

    fun decode(bytes: ByteArray): RasterMesh {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && bytes[0].toInt() == 1) { "Unsupported PRBM version ${bytes.getOrNull(0)}" }
        val flags = bytes[1].toInt() and 0xFF
        require(flags shr 7 and 1 == 0) { "Indexed PRBM geometry is not supported" }
        require(flags shr 5 and 1 == 0) { "Big-endian PRBM is not supported" }
        val attributeCount = flags and 0x1F
        val values = u24(bytes, 2)
        require(values % 3 == 0) { "PRBM vertex count $values is not a multiple of 3" }

        val attributes = HashMap<String, FloatArray>()
        var pos = 8
        repeat(attributeCount) {
            val nameStart = pos
            while (bytes[pos].toInt() != 0) pos++
            val name = String(bytes, nameStart, pos - nameStart, Charsets.US_ASCII)
            pos++
            val attributeFlags = bytes[pos++].toInt() and 0xFF
            val normalized = attributeFlags shr 6 and 1 == 1
            val cardinality = (attributeFlags shr 4 and 3) + 1
            val encoding = attributeFlags and 0x0F
            require(encoding in ENCODINGS) { "Unsupported PRBM encoding $encoding of attribute '$name'" }
            pos = align4(pos)
            val start = pos
            val count = cardinality * values
            attributes[name] = FloatArray(count) { element(buffer, start, it, encoding, normalized) }
            pos += count * bytesPerElement(encoding)
        }

        val materials = IntArray(values / 3) { -1 }
        pos = align4(pos)
        while (pos + 4 <= bytes.size) {
            val material = buffer.getInt(pos)
            pos += 4
            if (material == -1) break
            val start = buffer.getInt(pos)
            val count = buffer.getInt(pos + 4)
            pos += 8
            for (vertex in start until start + count step 3) materials[vertex / 3] = material
        }

        fun attribute(name: String): FloatArray = requireNotNull(attributes[name]) { "PRBM attribute '$name' is missing" }
        return RasterMesh(
            positions = attribute("position"),
            uvs = attribute("uv"),
            colors = attribute("color"),
            aos = attribute("ao"),
            sunlight = attribute("sunlight"),
            blocklight = attribute("blocklight"),
            materials = materials,
        )
    }

    private fun element(
        buffer: ByteBuffer,
        pos: Int,
        i: Int,
        encoding: Int,
        normalized: Boolean,
    ): Float =
        when (encoding) {
            FLOAT32 -> buffer.getFloat(pos + i * 4)
            INT8 -> signed(buffer.get(pos + i).toInt(), 127f, normalized)
            INT16 -> signed(buffer.getShort(pos + i * 2).toInt(), 32767f, normalized)
            INT32 -> buffer.getInt(pos + i * 4).toFloat()
            UINT8 -> unsigned(buffer.get(pos + i).toLong() and 0xFF, 255f, normalized)
            UINT16 -> unsigned(buffer.getShort(pos + i * 2).toLong() and 0xFFFF, 65535f, normalized)
            else -> unsigned(buffer.getInt(pos + i * 4).toLong() and 0xFFFFFFFFL, 4294967295f, normalized)
        }

    private fun u24(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or ((bytes[offset + 2].toInt() and 0xFF) shl 16)

    private fun align4(pos: Int): Int = (pos + 3) and 3.inv()

    /** WebGL signed normalisation: `max(c / (2^(b-1) - 1), -1)`. */
    private fun signed(
        value: Int,
        max: Float,
        normalized: Boolean,
    ): Float = if (normalized) (value / max).coerceAtLeast(-1f) else value.toFloat()

    private fun unsigned(
        value: Long,
        max: Float,
        normalized: Boolean,
    ): Float = if (normalized) value / max else value.toFloat()

    private fun bytesPerElement(encoding: Int): Int =
        when (encoding) {
            INT8, UINT8 -> 1
            INT16, UINT16 -> 2
            else -> 4
        }
}
