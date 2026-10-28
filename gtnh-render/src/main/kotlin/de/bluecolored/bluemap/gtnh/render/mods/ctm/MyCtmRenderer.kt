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
package de.bluecolored.bluemap.gtnh.render.mods.ctm

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant
import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.gtnh.render.mods.FaceTexture
import de.bluecolored.bluemap.gtnh.render.mods.Faces
import de.bluecolored.bluemap.gtnh.render.mods.MeshQuad
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModBlockRenderer
import de.bluecolored.bluemap.gtnh.render.mods.ModelFaces
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * `gtnh:ctm`: blocks whose model (a `gtnh_quads` capture or a JSON model of unrotated elements) uses a MyCTMLib
 * `compact` texture (`myctmlib.json`, e.g. Modernity-GTNH). The quads covering a whole block face with such a
 * texture are replaced by the four connected quadrants; everything else is drawn as in the model. A neighbour
 * connects when it is the same block state or shows a matching texture on the same side. Without MyCTMLib data,
 * or for transformed variants, the variant's model is drawn unchanged.
 */
class MyCtmRenderer(private val resourcePack: ResourcePack, textureGallery: TextureGallery, renderSettings: RenderSettings) :
    ModBlockRenderer(resourcePack, textureGallery, renderSettings) {
    private val modelFaces = ModelFaces(resourcePack)
    private val indices = IntArray(4)

    /** A block face drawn with connected quadrants. */
    private class CtmFace(val face: Direction, val texture: MyCtmTexture, val rgb: Int, val tint: Boolean) {
        /** Quadrant quads by `quadrant * 25 + index`. */
        val quads = arrayOfNulls<MeshQuad>(4 * 25)
    }

    private class Prepared(val plain: List<MeshQuad>, val faces: List<CtmFace>)

    override fun render(assets: ModAssets, block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color): Boolean {
        val data = assets.myCtm ?: return false
        if (variant.isTransformed) return false
        val prepared = prepared(data, variant.model) ?: return false
        for (quad in prepared.plain) emit(quad, block, blockModel, color)
        val state = block.blockState
        for (face in prepared.faces) {
            val neighbours = MyCtm.FaceNeighbours { dx, dy, dz ->
                val neighbor = block.getNeighborBlock(dx, dy, dz).blockState
                neighbor == state || data.matches(face.texture, modelFaces.face(neighbor, face.face)?.texture)
            }
            MyCtm.indices(face.face, neighbours, face.texture.alt != null, indices)
            for (q in 0 until 4) emit(quadrant(face, q, indices[q]), block, blockModel, color)
        }
        return true
    }

    private fun quadrant(face: CtmFace, q: Int, index: Int): MeshQuad {
        val slot = q * 25 + index
        face.quads[slot]?.let { return it }
        val tex = face.texture
        val (texture, cells, cell) = when {
            index <= 16 -> Triple(tex.connection, 4, index - 1)
            index <= 20 -> Triple(tex.base, 2, index - 17)
            else -> Triple(tex.alt ?: tex.base, 2, index - 21)
        }
        val col = cell % cells
        val row = cell / cells
        val s = 1f / cells
        val region = MyCtm.QUADRANTS[q]
        val quad = Faces.region(
            face.face, ZERO, ONE, region[0], region[1], region[2], region[3],
            FaceTexture(texture, col * s, row * s, (col + 1) * s, (row + 1) * s, rgb = face.rgb),
            legacyBottom = true,
        ).let { if (face.tint) MeshQuad(it.positions, it.uvs, it.texture, it.rgb, tint = true) else it }
        face.quads[slot] = quad
        return quad
    }

    private val preparedCache = ConcurrentHashMap<Key, Any>()

    private fun prepared(data: MyCtmData, model: Key): Prepared? {
        val cached = preparedCache.computeIfAbsent(model) { prepare(data, it) ?: NONE }
        return cached as? Prepared
    }

    private fun prepare(data: MyCtmData, modelKey: Key): Prepared? {
        val quads = modelFaces.quads(modelKey) ?: return null
        val plain = ArrayList<MeshQuad>()
        val groups = LinkedHashMap<Pair<Direction, Key>, MutableList<MeshQuad>>()
        for (quad in quads) {
            val face = quad.cullface
            val texture = data[quad.texture]
            if (face == null || texture == null || !texture.isCompact) {
                plain.add(quad)
                continue
            }
            groups.getOrPut(face to quad.texture) { ArrayList() }.add(quad)
        }
        val faces = ArrayList<CtmFace>()
        for ((key, group) in groups) {
            val first = group[0]
            val uniform = group.all { it.rgb == first.rgb && it.tint == first.tint }
            // the captured pieces must cover the whole face (MyCTMLib may capture a face as quarters)
            if (uniform && abs(group.sumOf { ModelFaces.area(it).toDouble() } - 1.0) < 1e-3) {
                faces.add(CtmFace(key.first, data[key.second]!!, first.rgb, first.tint))
            } else {
                plain.addAll(group)
            }
        }
        if (faces.isEmpty()) return null
        return Prepared(plain, faces)
    }

    private companion object {
        val ZERO = floatArrayOf(0f, 0f, 0f)
        val ONE = floatArrayOf(1f, 1f, 1f)
        val NONE = Any()

    }
}
