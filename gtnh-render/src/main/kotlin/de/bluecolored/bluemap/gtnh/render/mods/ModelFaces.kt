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

import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model
import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.gtnh.legacy.RegistryBlockStateMapper
import de.bluecolored.bluemap.gtnh.legacy.VanillaFlattening
import de.bluecolored.bluemap.gtnh.render.QuadModel
import de.bluecolored.bluemap.gtnh.render.QuadModelExtension
import java.util.concurrent.ConcurrentHashMap

/**
 * Block states' models as [MeshQuad]s and the texture each state shows on a block side, for renderers that draw
 * another block's look (connected-texture neighbours, AE2 / EnderIO facades). Captured `gtnh_quads` and JSON
 * models of unrotated elements are understood.
 */
class ModelFaces(private val pack: ResourcePack) {
    private val quadModels: QuadModelExtension? = pack.getExtension(QuadModelExtension.TYPE)
    private val faceTextures = ConcurrentHashMap<BlockState, Array<FaceLook?>>()

    /** A side's look: its texture and whether the block colour (biome tint) applies. */
    data class FaceLook(val texture: Key, val tint: Boolean)

    /** The quads of a model (null if it is neither a quad model nor a JSON model of unrotated elements). */
    fun quads(model: Key): List<MeshQuad>? =
        quadModels?.get(model)?.let(::fromQuadModel) ?: pack.models[model]?.let(::fromModel)

    /** What [state] shows on its [face] side: the largest quad of its model on that block face. */
    fun face(state: BlockState, face: Direction): FaceLook? {
        val looks = faceTextures.computeIfAbsent(state) { s ->
            val result = arrayOfNulls<FaceLook>(6)
            val resource = pack.getBlockState(s) ?: return@computeIfAbsent result
            var modelKey: Key? = null
            resource.forEach(s, 0, 0, 0) { v -> if (modelKey == null) modelKey = v.model }
            val quads = modelKey?.let(::quads) ?: return@computeIfAbsent result
            for (dir in Direction.entries) {
                result[dir.ordinal] = quads.filter { it.cullface == dir }.maxByOrNull(::area)?.let { FaceLook(it.texture, it.tint) }
            }
            result
        }
        return looks[face.ordinal]
    }

    private fun fromQuadModel(model: QuadModel): List<MeshQuad> = model.quads.map { q ->
        val rgb = (Math.round(q.r * 255) shl 16) or (Math.round(q.g * 255) shl 8) or Math.round(q.b * 255)
        MeshQuad(q.positions, q.uvs, model.textures[q.texture], rgb, glow = q.lightEmission >= 15, tint = q.tint)
    }

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
                val quad = Faces.face(dir, from, to, FaceTexture(texture, uv.x / 16f, uv.y / 16f, uv.z / 16f, uv.w / 16f, face.rotation / 90))
                quads.add(if (face.tintindex >= 0) MeshQuad(quad.positions, quad.uvs, quad.texture, tint = true) else quad)
            }
        }
        return quads
    }

    companion object {
        /** Area of a planar quad (half the diagonals' cross product; also right for a triangle). */
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
            return kotlin.math.sqrt(cx * cx + cy * cy + cz * cz) / 2f
        }

        /**
         * The BlueMap block state of a 1.7 block named in NBT (`modid:name` + meta), exactly as the legacy loader maps
         * placed blocks: vanilla blocks through the 1.13 flattening table, modded ones to `N(modid):N(name)[meta]`.
         */
        fun legacyState(registryName: String, meta: Int): BlockState? {
            val name = if (':' in registryName) registryName else "minecraft:$registryName"
            if (name.startsWith("minecraft:")) return VanillaFlattening.state(name, meta)
            return RegistryBlockStateMapper.identityState(name, meta)
        }
    }
}
