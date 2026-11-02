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

/** Builds [RasterMesh]es for tests; quads are split like BlueMap's `ResourceModelRenderer` does it. */
class MeshBuilder {
    private val positions = ArrayList<Float>()
    private val uvs = ArrayList<Float>()
    private val colors = ArrayList<Float>()
    private val aos = ArrayList<Float>()
    private val sunlight = ArrayList<Float>()
    private val blocklight = ArrayList<Float>()
    private val materials = ArrayList<Int>()

    class Vertex(
        val x: Double,
        val y: Double,
        val z: Double,
        val u: Double = 0.0,
        val v: Double = 0.0,
        val ao: Double = 1.0,
    )

    fun triangle(
        a: Vertex,
        b: Vertex,
        c: Vertex,
        material: Int,
        color: DoubleArray = WHITE,
        sun: Int = 15,
        block: Int = 0,
    ): MeshBuilder {
        for (vertex in listOf(a, b, c)) {
            positions += listOf(vertex.x.toFloat(), vertex.y.toFloat(), vertex.z.toFloat())
            uvs += listOf(vertex.u.toFloat(), vertex.v.toFloat())
            colors += color.map { it.toFloat() }
            aos += vertex.ao.toFloat()
            sunlight += sun.toFloat()
            blocklight += block.toFloat()
        }
        materials += material
        return this
    }

    /** Quad `c0 c1 c2 c3` (counter-clockwise seen from the front) as the triangles `c0 c1 c2` and `c0 c2 c3`. */
    fun quad(
        c0: Vertex,
        c1: Vertex,
        c2: Vertex,
        c3: Vertex,
        material: Int,
        color: DoubleArray = WHITE,
        sun: Int = 15,
        block: Int = 0,
    ): MeshBuilder = triangle(c0, c1, c2, material, color, sun, block).triangle(c0, c2, c3, material, color, sun, block)

    /**
     * The unit cube with BlueMap's corner order and default UVs per face (corner 0 gets uv (0, 1), 1 (1, 1),
     * 2 (1, 0), 3 (0, 0)); material = [materialOfFace] of the contract face id (down 1 .. east 6).
     */
    fun cube(materialOfFace: (Int) -> Int = { it }): MeshBuilder {
        val c = arrayOf(xyz(0, 0, 0), xyz(0, 0, 1), xyz(1, 0, 0), xyz(1, 0, 1), xyz(0, 1, 0), xyz(0, 1, 1), xyz(1, 1, 0), xyz(1, 1, 1))
        val faces =
            mapOf(
                FaceIds.DOWN to intArrayOf(0, 2, 3, 1),
                FaceIds.UP to intArrayOf(5, 7, 6, 4),
                FaceIds.NORTH to intArrayOf(2, 0, 4, 6),
                FaceIds.SOUTH to intArrayOf(1, 3, 7, 5),
                FaceIds.WEST to intArrayOf(0, 1, 5, 4),
                FaceIds.EAST to intArrayOf(3, 2, 6, 7),
            )
        for ((face, corners) in faces) {
            fun corner(
                i: Int,
                u: Double,
                v: Double,
            ) = Vertex(c[corners[i]][0], c[corners[i]][1], c[corners[i]][2], u, v)
            quad(corner(0, 0.0, 1.0), corner(1, 1.0, 1.0), corner(2, 1.0, 0.0), corner(3, 0.0, 0.0), materialOfFace(face))
        }
        return this
    }

    fun build(): RasterMesh =
        RasterMesh(
            positions.toFloatArray(),
            uvs.toFloatArray(),
            colors.toFloatArray(),
            aos.toFloatArray(),
            sunlight.toFloatArray(),
            blocklight.toFloatArray(),
            materials.toIntArray(),
        )

    companion object {
        val WHITE = doubleArrayOf(1.0, 1.0, 1.0)

        private fun xyz(
            x: Int,
            y: Int,
            z: Int,
        ) = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())

        /** A texture of one colour (straight ARGB). */
        fun solid(
            argb: Int,
            halfTransparent: Boolean = false,
            size: Int = 16,
        ): RasterTexture = RasterTexture(size, size, IntArray(size * size) { argb }, halfTransparent)
    }
}
