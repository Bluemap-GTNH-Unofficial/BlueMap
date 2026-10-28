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

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.gtnh.render.QuadModelExtension
import de.bluecolored.bluemap.gtnh.render.QuadModelRenderer
import kotlin.math.max

/**
 * Shared part of the mod renderers (Chisel, Iron Chests, BiblioCraft, AE2, EnderIO): draws [MeshQuad]s with the
 * lighting, cave removal, culling and map colour of [QuadModelRenderer]. When a renderer cannot draw a block
 * (no exporter data, unknown tile entity), the variant's own model is drawn instead (the exporter's static capture
 * or bounding box), so a block is never invisible.
 */
abstract class ModBlockRenderer(
    private val resourcePack: ResourcePack,
    private val textureGallery: TextureGallery,
    private val renderSettings: RenderSettings,
) : BlockRenderer {
    protected val assets: ModAssets? = resourcePack.getExtension(ModAssets.TYPE)
    private val textures = resourcePack.textures
    private val quadModels: QuadModelExtension? = resourcePack.getExtension(QuadModelExtension.TYPE)
    private val quadRenderer by lazy { QuadModelRenderer(resourcePack, textureGallery, renderSettings) }
    private val modelRenderer by lazy { BlockRendererType.DEFAULT.create(resourcePack, textureGallery, renderSettings) }

    private val blockColorCalculator = resourcePack.createBlockColorCalculator()
    private val mapColor = Color()
    private val tintColor = Color()
    private var mapColorOpacity = 0f

    final override fun render(block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color) {
        val modelStart = blockModel.start
        mapColorOpacity = 0f
        delegated = false
        tintColor.set(0f, 0f, 0f, -1f, true)
        val assets = assets
        if (assets == null || !render(assets, block, variant, blockModel, color)) {
            blockModel.initialize(modelStart)
            color.set(0f, 0f, 0f, 0f, true)
            ModRenderStats.fallback(this)
            renderVariantModel(block, variant, blockModel, color)
            return
        }
        ModRenderStats.rendered(this)
        // a delegated renderer (renderState) has already finished the map colour
        if (color.a > 0 && !delegated) {
            color.flatten().straight()
            color.a = mapColorOpacity
        }
        blockModel.initialize(modelStart)
    }

    private var delegated = false

    /** Draws the block; false to fall back to the variant's model. */
    protected abstract fun render(assets: ModAssets, block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color): Boolean

    private val delegates = HashMap<BlockRendererType, de.bluecolored.bluemap.core.map.hires.block.BlockRenderer>()

    /**
     * Draws [state]'s own blockstate variants at this block's position (a facade showing another block), with this
     * block's light and neighbours. False if the pack has nothing for the state.
     */
    protected fun renderState(state: de.bluecolored.bluemap.core.world.BlockState, block: BlockNeighborhood, blockModel: TileModelView, color: Color): Boolean {
        val resource = resourcePack.getBlockState(state) ?: return false
        var drawn = false
        resource.forEach(state, block.x, block.y, block.z) { v ->
            val renderer = delegates.getOrPut(v.renderer) { v.renderer.create(resourcePack, textureGallery, renderSettings) }
            val start = blockModel.start
            renderer.render(block, v, blockModel, color)
            blockModel.initialize(start)
            drawn = true
            delegated = true
        }
        return drawn
    }

    /**
     * Draws the variant's own model (the exporter's static capture) besides the renderer's quads, for blocks that
     * render as a normal block and have a TESR on top. Call it after the [emit]s: it finishes the map colour.
     */
    protected fun renderOwnModel(block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color) {
        // a model transform must only move the model's own faces
        blockModel.initialize()
        renderVariantModel(block, variant, blockModel, color)
        delegated = true
    }

    private fun renderVariantModel(block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color) {
        if (quadModels?.get(variant.model) != null) {
            quadRenderer.render(block, variant, blockModel, color)
        } else {
            modelRenderer.render(block, variant, blockModel, color)
        }
    }

    protected fun emit(mesh: Mesh, block: BlockNeighborhood, blockModel: TileModelView, color: Color) {
        for (quad in mesh.quads) emit(quad, block, blockModel, color)
    }

    /** Draws one quad unless it is culled (by its [MeshQuad.cullface] neighbour, top-only or cave removal). */
    protected fun emit(quad: MeshQuad, block: BlockNeighborhood, blockModel: TileModelView, color: Color) {
        if (renderSettings.isRenderTopOnly && quad.normalY < 0.01f) return
        val cullface = quad.cullface
        if (cullface != null) {
            val v = cullface.toVector()
            val neighbor = block.getNeighborBlock(v.x, v.y, v.z)
            val properties = neighbor.properties
            if (properties.isCulling) return
            if (properties.cullingIdentical && neighbor.blockState == block.blockState) return
        }

        val f = quad.facing.toVector()
        val faced = block.getNeighborBlock(f.x, f.y, f.z)
        val ownLight = block.lightData
        val facedLight = faced.lightData
        val sunLight = max(ownLight.skyLight, facedLight.skyLight)
        val blockLight = max(ownLight.blockLight, facedLight.blockLight)
        if (block.isRemoveIfCave &&
            (if (renderSettings.isCaveDetectionUsesBlockLight) max(blockLight, sunLight) else sunLight) == 0
        ) {
            return
        }
        val light = if (quad.glow) 15 else blockLight

        val triangle = quad.isTriangle
        blockModel.initialize()
        blockModel.add(if (triangle) 1 else 2)
        val tileModel = blockModel.tileModel
        val face1 = blockModel.start
        val p = quad.positions
        val t = quad.uvs
        val textureId = textureGallery.get(quad.texture)
        var r = (quad.rgb shr 16 and 0xFF) / 255f
        var g = (quad.rgb shr 8 and 0xFF) / 255f
        var b = (quad.rgb and 0xFF) / 255f
        if (quad.tint) {
            if (tintColor.a < 0) blockColorCalculator.getBlockColor(block, tintColor)
            r *= tintColor.r
            g *= tintColor.g
            b *= tintColor.b
        }
        tileModel.setPositions(face1, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8])
        tileModel.setUvs(face1, t[0], t[1], t[2], t[3], t[4], t[5])
        tileModel.setMaterialIndex(face1, textureId)
        tileModel.setColor(face1, r, g, b)
        tileModel.setBlocklight(face1, light)
        tileModel.setSunlight(face1, sunLight)
        tileModel.setAOs(face1, 1f, 1f, 1f)
        if (!triangle) {
            val face2 = face1 + 1
            tileModel.setPositions(face2, p[0], p[1], p[2], p[6], p[7], p[8], p[9], p[10], p[11])
            tileModel.setUvs(face2, t[0], t[1], t[4], t[5], t[6], t[7])
            tileModel.setMaterialIndex(face2, textureId)
            tileModel.setColor(face2, r, g, b)
            tileModel.setBlocklight(face2, light)
            tileModel.setSunlight(face2, sunLight)
            tileModel.setAOs(face2, 1f, 1f, 1f)
        }

        if (quad.normalY > 0.01f) {
            val resource = textures[quad.texture] ?: return
            mapColor.set(resource.colorPremultiplied)
            var l = max(sunLight / 15f, light / 15f)
            l = (1 - renderSettings.ambientLight) * l + renderSettings.ambientLight
            mapColor.r *= r * l
            mapColor.g *= g * l
            mapColor.b *= b * l
            if (mapColor.a > mapColorOpacity) mapColorOpacity = mapColor.a
            color.add(mapColor)
        }
    }

    /** The block's `meta` property (the 1.7 metadata), or -1. */
    protected fun meta(block: BlockNeighborhood): Int = block.blockState.properties["meta"]?.toIntOrNull() ?: 0

    protected companion object {
        fun key(value: String): Key = Key.parse(value)
    }
}
