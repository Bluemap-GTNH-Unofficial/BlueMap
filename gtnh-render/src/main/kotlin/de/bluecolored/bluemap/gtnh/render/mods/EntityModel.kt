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

/**
 * Boxes of a 1.7 `ModelBase` (`ModelRenderer.addBox` with a texture offset): the standard box UV unwrap of
 * `ModelBox`/`TexturedQuad`, in model space (pixels, y down, as the model's own coordinates). Apply the tile
 * entity renderer's GL transform afterwards to get block space.
 */
object EntityModel {
    /**
     * The six faces of a box at [x], [y], [z] (already offset by the part's rotation point) with size [dx]×[dy]×[dz],
     * texture offset [u], [v] on a [texW]×[texH] texture.
     */
    fun box(
        texture: Key,
        u: Float, v: Float,
        x: Float, y: Float, z: Float,
        dx: Float, dy: Float, dz: Float,
        texW: Float = 64f, texH: Float = 64f,
    ): List<MeshQuad> {
        val x2 = x + dx
        val y2 = y + dy
        val z2 = z + dz
        val v7 = floatArrayOf(x, y, z)
        val v0 = floatArrayOf(x2, y, z)
        val v1 = floatArrayOf(x2, y2, z)
        val v2 = floatArrayOf(x, y2, z)
        val v3 = floatArrayOf(x, y, z2)
        val v4 = floatArrayOf(x2, y, z2)
        val v5 = floatArrayOf(x2, y2, z2)
        val v6 = floatArrayOf(x, y2, z2)
        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, u1: Float, v1: Float, u2: Float, v2: Float): MeshQuad {
            val p = FloatArray(12)
            a.copyInto(p, 0)
            b.copyInto(p, 3)
            c.copyInto(p, 6)
            d.copyInto(p, 9)
            // TexturedQuad: vertex 0 (u2, v1), 1 (u1, v1), 2 (u1, v2), 3 (u2, v2)
            val uv = floatArrayOf(u2 / texW, v1 / texH, u1 / texW, v1 / texH, u1 / texW, v2 / texH, u2 / texW, v2 / texH)
            return MeshQuad(p, uv, texture)
        }
        return listOf(
            quad(v4, v0, v1, v5, u + dz + dx, v + dz, u + dz + dx + dz, v + dz + dy),
            quad(v7, v3, v6, v2, u, v + dz, u + dz, v + dz + dy),
            quad(v4, v3, v7, v0, u + dz, v, u + dz + dx, v + dz),
            quad(v1, v2, v6, v5, u + dz + dx, v + dz, u + dz + dx + dx, v),
            quad(v0, v7, v2, v1, u + dz, v + dz, u + dz + dx, v + dz + dy),
            quad(v3, v4, v5, v6, u + dz + dx + dz, v + dz, u + dz + dx + dz + dx, v + dz + dy),
        )
    }

    /** Model pixels -> model units (`ModelRenderer.render(1/16)`). */
    val PIXELS: Transform = Transform.scale(1 / 16f, 1 / 16f, 1 / 16f)
}
