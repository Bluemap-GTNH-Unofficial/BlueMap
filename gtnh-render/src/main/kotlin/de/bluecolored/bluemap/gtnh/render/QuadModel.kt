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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.resources.ResourcePath
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture
import de.bluecolored.bluemap.core.util.Direction
import java.io.Reader
import kotlin.math.abs

/**
 * A block model made of arbitrary quads, captured from the 1.7.10 client by the GTNH exporter.
 *
 * File format (`assets/<ns>/models/gtnh_quads/<...>.json`; see the gtnh-exporter repository's README):
 * ```json
 * {
 *   "textures": { "t0": "<ns>:block/<path>" },
 *   "gtnh_quads": [
 *     { "texture": "#t0", "vertices": [[x, y, z, u, v], [..], [..], [..]],
 *       "color": "#rrggbb", "tint": false, "cullface": "north", "light_emission": 0 }
 *   ]
 * }
 * ```
 * Positions are block pixels (0..16, may exceed the block), uv are sprite pixels (0..16). Vertices are in 1.7
 * tessellator order (counter-clockwise seen from the visible side). `color` (default white) is the static vertex
 * color with the vanilla directional face shading removed; `tint` multiplies the block's color calculator on top.
 * The file is also a valid vanilla model (only `textures` is read by BlueMap's model loader), so its textures are
 * loaded into the atlas like any other model's.
 *
 * Quads are in the client's draw order: the client draws a later quad over an earlier coplanar one (`GL_LEQUAL`),
 * e.g. a Werkstoff block's material icon over its iron base. The webapp draws coplanar quads in texture order
 * instead, so [hidden] tells renderers which quads a later opaque quad hides.
 */
class QuadModel(
    val textures: List<ResourcePath<Texture>>,
    val quads: List<Quad>,
) {
    class Quad(
        /** 4 vertices × (x, y, z) in block units (0..1). */
        val positions: FloatArray,
        /** 4 vertices × (u, v) in sprite units (0..1). */
        val uvs: FloatArray,
        /** Index into [QuadModel.textures]. */
        val texture: Int,
        val r: Float,
        val g: Float,
        val b: Float,
        val tint: Boolean,
        val cullface: Direction?,
        val lightEmission: Int,
        /** Axis direction closest to the face normal (used for neighbour lighting). */
        val facing: Direction,
        /** Y component of the unit face normal. */
        val normalY: Float,
    )

    /**
     * Per quad, the later quads that cover it completely: in the same axis-aligned plane, facing the same way, an
     * axis-aligned rectangle containing it.
     */
    private val coveredBy: List<IntArray> by lazy {
        quads.indices.map { i -> (i + 1 until quads.size).filter { covers(quads[it], quads[i]) }.toIntArray() }
    }

    /**
     * Which quads the client never shows because a later quad covering them is drawn with a fully opaque texture
     * ([opaque] of a texture index).
     */
    fun hidden(opaque: (Int) -> Boolean): BooleanArray = BooleanArray(quads.size) { i -> coveredBy[i].any { opaque(quads[it].texture) } }

    companion object {
        const val QUADS_FIELD = "gtnh_quads"

        fun parse(reader: Reader): QuadModel = parse(JsonParser.parseReader(reader).asJsonObject)

        fun parse(json: JsonObject): QuadModel {
            val textureNames = HashMap<String, Int>()
            val textures = ArrayList<ResourcePath<Texture>>()
            json.getAsJsonObject("textures")?.entrySet()?.forEach { (name, value) ->
                textureNames[name] = textures.size
                textures.add(ResourcePath(value.asString))
            }

            val quadsJson = requireNotNull(json.getAsJsonArray(QUADS_FIELD)) { "missing '$QUADS_FIELD'" }
            val quads = ArrayList<Quad>(quadsJson.size())
            for (element in quadsJson) {
                val q = element.asJsonObject
                val vertices = q.getAsJsonArray("vertices")
                require(vertices.size() == 4) { "a quad needs 4 vertices, got ${vertices.size()}" }
                val positions = FloatArray(12)
                val uvs = FloatArray(8)
                for (i in 0 until 4) {
                    val v = vertices[i].asJsonArray
                    require(v.size() == 5) { "a vertex is [x, y, z, u, v]" }
                    positions[i * 3] = v[0].asFloat / 16f
                    positions[i * 3 + 1] = v[1].asFloat / 16f
                    positions[i * 3 + 2] = v[2].asFloat / 16f
                    uvs[i * 2] = v[3].asFloat / 16f
                    uvs[i * 2 + 1] = v[4].asFloat / 16f
                }

                val textureRef = q.get("texture").asString
                val texture =
                    if (textureRef.startsWith("#")) {
                        requireNotNull(textureNames[textureRef.substring(1)]) { "unknown texture variable $textureRef" }
                    } else {
                        textureNames.getOrPut(textureRef) {
                            textures.add(ResourcePath(textureRef))
                            textures.size - 1
                        }
                    }

                val rgb = q.get("color")?.asString?.let(::parseRgb) ?: 0xFFFFFF
                val cullface = q.get("cullface")?.asString?.let { Direction.fromString(it) }
                val normal = normal(positions)

                quads.add(
                    Quad(
                        positions = positions,
                        uvs = uvs,
                        texture = texture,
                        r = (rgb shr 16 and 0xFF) / 255f,
                        g = (rgb shr 8 and 0xFF) / 255f,
                        b = (rgb and 0xFF) / 255f,
                        tint = q.get("tint")?.asBoolean ?: false,
                        cullface = cullface,
                        lightEmission = q.get("light_emission")?.asInt ?: 0,
                        facing = nearestDirection(normal),
                        normalY = normal[1],
                    ),
                )
            }
            return QuadModel(textures, quads)
        }

        /** Whether [top] covers [bottom] completely: see [coveredBy]. */
        private fun covers(
            top: Quad,
            bottom: Quad,
        ): Boolean {
            if (top.facing != bottom.facing) return false
            val plane = axis(top.facing)
            val t = top.positions
            val b = bottom.positions
            for (i in 0 until 4) {
                if (abs(t[i * 3 + plane] - t[plane]) > EPS || abs(b[i * 3 + plane] - t[plane]) > EPS) return false
            }
            for (axis in 0 until 3) {
                if (axis == plane) continue
                val min = min(t, axis)
                val max = max(t, axis)
                // the top quad must be the rectangle of its bounds, and the bottom one inside it
                for (i in 0 until 4) {
                    val c = t[i * 3 + axis]
                    if (abs(c - min) > EPS && abs(c - max) > EPS) return false
                }
                if (min(b, axis) < min - EPS || max(b, axis) > max + EPS) return false
            }
            return true
        }

        private fun axis(direction: Direction): Int =
            when (direction) {
                Direction.WEST, Direction.EAST -> 0
                Direction.DOWN, Direction.UP -> 1
                Direction.NORTH, Direction.SOUTH -> 2
            }

        private fun min(
            p: FloatArray,
            axis: Int,
        ): Float = minOf(p[axis], p[3 + axis], p[6 + axis], p[9 + axis])

        private fun max(
            p: FloatArray,
            axis: Int,
        ): Float = maxOf(p[axis], p[3 + axis], p[6 + axis], p[9 + axis])

        private const val EPS = 1e-4f

        private fun parseRgb(value: String): Int {
            val hex = value.removePrefix("#")
            require(hex.length == 6) { "color must be #rrggbb, got $value" }
            return hex.toInt(16)
        }

        /** Unit normal of a (possibly non-planar) quad from its diagonals; tessellator CCW order gives the front. */
        internal fun normal(p: FloatArray): FloatArray {
            val ax = p[6] - p[0]
            val ay = p[7] - p[1]
            val az = p[8] - p[2]
            val bx = p[9] - p[3]
            val by = p[10] - p[4]
            val bz = p[11] - p[5]
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-9f) return floatArrayOf(0f, 1f, 0f)
            nx /= len
            ny /= len
            nz /= len
            return floatArrayOf(nx, ny, nz)
        }

        internal fun nearestDirection(n: FloatArray): Direction {
            val ax = abs(n[0])
            val ay = abs(n[1])
            val az = abs(n[2])
            return when {
                ay >= ax && ay >= az -> if (n[1] >= 0) Direction.UP else Direction.DOWN
                ax >= az -> if (n[0] >= 0) Direction.EAST else Direction.WEST
                else -> if (n[2] >= 0) Direction.SOUTH else Direction.NORTH
            }
        }
    }
}
