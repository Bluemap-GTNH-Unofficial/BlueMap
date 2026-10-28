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
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model
import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.gtnh.render.QuadModel
import de.bluecolored.bluemap.gtnh.render.QuadModelExtension
import de.bluecolored.bluemap.gtnh.render.mods.FaceTexture
import de.bluecolored.bluemap.gtnh.render.mods.Faces
import de.bluecolored.bluemap.gtnh.render.mods.MeshQuad
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModBlockRenderer
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
    private val quadModels: QuadModelExtension? = resourcePack.getExtension(QuadModelExtension.TYPE)
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
                neighbor == state || data.matches(face.texture, faceTexture(neighbor, face.face))
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
        val quads = quadModels?.get(modelKey)?.let(::fromQuadModel)
            ?: resourcePack.models[modelKey]?.let(::fromModel)
            ?: return null
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
            if (uniform && abs(group.sumOf { area(it).toDouble() } - 1.0) < 1e-3) {
                faces.add(CtmFace(key.first, data[key.second]!!, first.rgb, first.tint))
            } else {
                plain.addAll(group)
            }
        }
        if (faces.isEmpty()) return null
        return Prepared(plain, faces)
    }

    /** The texture [state] shows on the [face] side (its model's quad on that block face), cached per state. */
    private fun faceTexture(state: BlockState, face: Direction): Key? {
        val textures = faceTextures.computeIfAbsent(state) { s ->
            val result = arrayOfNulls<Key>(6)
            val resource = resourcePack.getBlockState(s) ?: return@computeIfAbsent result
            var modelKey: Key? = null
            resource.forEach(s, 0, 0, 0) { v -> if (modelKey == null) modelKey = v.model }
            val key = modelKey ?: return@computeIfAbsent result
            val quads = quadModels?.get(key)?.let(::fromQuadModel) ?: resourcePack.models[key]?.let(::fromModel) ?: emptyList()
            for (dir in Direction.entries) {
                result[dir.ordinal] = quads.filter { it.cullface == dir }.maxByOrNull(::area)?.texture
            }
            result
        }
        return textures[face.ordinal]
    }

    private val faceTextures = ConcurrentHashMap<BlockState, Array<Key?>>()

    private fun fromQuadModel(model: QuadModel): List<MeshQuad> = model.quads.map { q ->
        val rgb = (Math.round(q.r * 255) shl 16) or (Math.round(q.g * 255) shl 8) or Math.round(q.b * 255)
        MeshQuad(q.positions, q.uvs, model.textures[q.texture], rgb, glow = q.lightEmission >= 15, tint = q.tint)
    }

    /** JSON model elements as quads; null when an element is rotated (not drawn here). */
    private fun fromModel(model: Model): List<MeshQuad>? {
        val elements = model.elements ?: return null
        val quads = ArrayList<MeshQuad>()
        for (element in elements) {
            element.rotation.let { if (it.x != 0f || it.y != 0f || it.z != 0f || it.angle != 0f) return null }
            val from = floatArrayOf(element.from.x / 16f, element.from.y / 16f, element.from.z / 16f)
            val to = floatArrayOf(element.to.x / 16f, element.to.y / 16f, element.to.z / 16f)
            for ((dir, face) in element.faces) {
                val texture: Key = face.texture.getTexturePath(model.textures::get) ?: continue
                val uv = face.uv
                quads.add(
                    Faces.face(dir, from, to, FaceTexture(texture, uv.x / 16f, uv.y / 16f, uv.z / 16f, uv.w / 16f, face.rotation / 90))
                        .let { if (face.tintindex >= 0) MeshQuad(it.positions, it.uvs, it.texture, tint = true) else it },
                )
            }
        }
        return quads
    }

    private companion object {
        val ZERO = floatArrayOf(0f, 0f, 0f)
        val ONE = floatArrayOf(1f, 1f, 1f)
        val NONE = Any()

        /** Area of a planar quad (block units²). */
        fun area(q: MeshQuad): Float {
            val p = q.positions
            val ax = p[6] - p[0]
            val ay = p[7] - p[1]
            val az = p[8] - p[2]
            val bx = p[9] - p[3]
            val by = p[10] - p[4]
            val bz = p[11] - p[5]
            val cx = ay * bz - az * by
            val cy = az * bx - ax * bz
            val cz = ax * by - ay * bx
            // half the diagonals' cross product; for a triangle (4th vertex = 3rd) this is the triangle's area
            return kotlin.math.sqrt(cx * cx + cy * cy + cz * cz) / 2f
        }
    }
}
