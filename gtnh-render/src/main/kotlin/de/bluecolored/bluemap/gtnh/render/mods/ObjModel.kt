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

import de.bluecolored.bluemap.core.util.Key
import java.io.BufferedReader
import java.io.Reader

/**
 * A Wavefront model as Forge 1.7.10's `WavefrontObject` reads it: vertices, texture coordinates (v flipped,
 * `1 - v`) and polygon faces per group (`g`/`o`). Faces with 3 or 4 vertices are kept; larger polygons are fanned.
 */
class ObjModel(
    val groups: Map<String, List<Face>>,
) {
    /** [positions]: 3 per vertex (model units), [uvs]: 2 per vertex (0..1, v already flipped). */
    class Face(
        val positions: FloatArray,
        val uvs: FloatArray,
    ) {
        val vertexCount: Int get() = positions.size / 3
    }

    /** The faces of [names] (all groups when null) as mesh quads in model space with one texture. */
    fun mesh(
        texture: Key,
        names: Collection<String>? = null,
        rgb: Int = MeshQuad.WHITE,
    ): Mesh {
        val quads = ArrayList<MeshQuad>()
        val selected = if (names == null) groups.values else names.mapNotNull { groups[it] }
        for (faces in selected) for (face in faces) quads.add(face.toQuad(texture, rgb))
        return Mesh(quads)
    }

    companion object {
        fun parse(reader: Reader): ObjModel {
            val vertices = ArrayList<FloatArray>()
            val textureCoords = ArrayList<FloatArray>()
            val groups = LinkedHashMap<String, MutableList<Face>>()
            var current = groups.getOrPut("default") { ArrayList() }
            BufferedReader(reader).lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val tokens = line.split(WHITESPACE)
                when (tokens[0]) {
                    "v" -> {
                        vertices.add(floatArrayOf(tokens[1].toFloat(), tokens[2].toFloat(), tokens[3].toFloat()))
                    }

                    "vt" -> {
                        textureCoords.add(floatArrayOf(tokens[1].toFloat(), 1f - tokens.getOrElse(2) { "0" }.toFloat()))
                    }

                    "g", "o" -> {
                        current = groups.getOrPut(tokens.getOrElse(1) { "default" }) { ArrayList() }
                    }

                    "f" -> {
                        val refs =
                            tokens.drop(1).map { ref ->
                                val parts = ref.split('/')
                                val v = index(parts[0].toInt(), vertices.size)
                                val t = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { index(it.toInt(), textureCoords.size) }
                                v to t
                            }
                        // fan polygons into triangles/quads the way GL_POLYGON would
                        if (refs.size <= 4) {
                            current.add(face(refs, vertices, textureCoords))
                        } else {
                            for (i in 1 until refs.size - 1) {
                                current.add(
                                    face(listOf(refs[0], refs[i], refs[i + 1]), vertices, textureCoords),
                                )
                            }
                        }
                    }
                }
            }
            return ObjModel(groups.filterValues { it.isNotEmpty() })
        }

        private fun face(
            refs: List<Pair<Int, Int?>>,
            vertices: List<FloatArray>,
            textureCoords: List<FloatArray>,
        ): Face {
            val n = refs.size
            val p = FloatArray(n * 3)
            val uv = FloatArray(n * 2)
            refs.forEachIndexed { i, (v, t) ->
                vertices[v].copyInto(p, i * 3)
                if (t != null) textureCoords[t].copyInto(uv, i * 2)
            }
            return Face(p, uv)
        }

        private fun index(
            i: Int,
            size: Int,
        ): Int = if (i < 0) size + i else i - 1

        private val WHITESPACE = Regex("\\s+")
    }
}

private fun ObjModel.Face.toQuad(
    texture: Key,
    rgb: Int,
): MeshQuad {
    val p = FloatArray(12)
    val uv = FloatArray(8)
    val n = vertexCount
    for (i in 0 until 4) {
        val src = if (i < n) i else n - 1
        positions.copyInto(p, i * 3, src * 3, src * 3 + 3)
        uvs.copyInto(uv, i * 2, src * 2, src * 2 + 2)
    }
    return MeshQuad(p, uv, texture, rgb, isTriangle = n == 3)
}
