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
package de.bluecolored.bluemap.gtnh.render.gregtech

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
 * Shared part of the GregTech renderers: draws layered GT faces on axis-aligned boxes with GT's UV orientation,
 * tint, glow (full block light) and per-layer z-order. Lighting, cave removal, culling and the map colour follow
 * [QuadModelRenderer]. When the dump has nothing for a block, the variant's own model is drawn (the exporter's
 * static capture), so a GT block is never invisible.
 */
abstract class GtBlockRenderer(
    resourcePack: ResourcePack,
    private val textureGallery: TextureGallery,
    private val renderSettings: RenderSettings,
) : BlockRenderer {
    protected val data: GtData? = resourcePack.getExtension(GregTechExtension.TYPE)?.data
    private val textures = resourcePack.textures
    private val quadModels: QuadModelExtension? = resourcePack.getExtension(QuadModelExtension.TYPE)
    private val quadRenderer by lazy { QuadModelRenderer(resourcePack, textureGallery, renderSettings) }
    private val modelRenderer by lazy { BlockRendererType.DEFAULT.create(resourcePack, textureGallery, renderSettings) }

    private val mapColor = Color()
    private var mapColorOpacity = 0f
    private val uvs = FloatArray(8)

    final override fun render(block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color) {
        val data = data
        val modelStart = blockModel.start
        mapColorOpacity = 0f
        if (data == null || !render(data, block, blockModel, color)) {
            blockModel.initialize(modelStart)
            renderVariantModel(block, variant, blockModel, color)
            return
        }
        if (color.a > 0) {
            color.flatten().straight()
            color.a = mapColorOpacity
        }
        blockModel.initialize(modelStart)
    }

    /** Draws the block from the GT dump; false to fall back to the variant's model. */
    protected abstract fun render(data: GtData, block: BlockNeighborhood, blockModel: TileModelView, color: Color): Boolean

    private fun renderVariantModel(block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color) {
        if (quadModels?.get(variant.model) != null) {
            quadRenderer.render(block, variant, blockModel, color)
        } else {
            modelRenderer.render(block, variant, blockModel, color)
        }
    }

    /** The six sides of a full cube. */
    protected fun renderCube(data: GtData, block: BlockNeighborhood, blockModel: TileModelView, color: Color, sides: GtSides, uv: UvContext = UvContext.NONE) {
        for (side in 0 until 6) renderFace(data, block, blockModel, color, side, Box.FULL, sides.getOrNull(side) ?: continue, uv)
    }

    protected fun renderFace(
        data: GtData,
        block: BlockNeighborhood,
        blockModel: TileModelView,
        color: Color,
        side: Int,
        box: Box,
        face: GtFace,
        uv: UvContext = UvContext.NONE,
    ) {
        if (face.isEmpty()) return
        if (renderSettings.isRenderTopOnly && side != Side.UP) return

        val dx = Side.dx(side)
        val dy = Side.dy(side)
        val dz = Side.dz(side)
        val faced = block.getNeighborBlock(dx, dy, dz)
        if (box.touchesBoundary(side)) {
            val properties = faced.properties
            if (properties.isCulling) return
            if (properties.cullingIdentical && faced.blockState == block.blockState) return
        }

        val ownLight = block.lightData
        val facedLight = faced.lightData
        val sunLight = max(ownLight.skyLight, facedLight.skyLight)
        val blockLight = max(ownLight.blockLight, facedLight.blockLight)
        if (block.isRemoveIfCave &&
            (if (renderSettings.isCaveDetectionUsesBlockLight) max(blockLight, sunLight) else sunLight) == 0
        ) {
            return
        }

        val corners = box.corners(side)
        var layerIndex = 0
        for (layer in face) {
            if (layer.glow && !data.renderGlowTextures) continue
            val op = GtUv.transform(side, layer.uv, uv.alignment, uv.rotation, uv.flip, data.fixedBottomFaceUV)
            for (i in 0 until 4) GtUv.uv(side, corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2], op, uvs, i * 2)
            val light = if (layer.glow) 15 else blockLight
            layer.texture?.let {
                quad(blockModel, color, side, corners, layerIndex++, it, layer.rgb, light, sunLight)
            }
            layer.overlay?.let {
                quad(blockModel, color, side, corners, layerIndex++, it, GtLayer.WHITE, light, sunLight)
            }
        }
    }

    private fun quad(
        blockModel: TileModelView,
        color: Color,
        side: Int,
        corners: FloatArray,
        layerIndex: Int,
        texture: Key,
        rgb: Int,
        blockLight: Int,
        sunLight: Int,
    ) {
        // stacked layers are coplanar in GT; lift each one a little so the webapp draws them in order
        val lift = layerIndex * LAYER_LIFT
        val ox = Side.dx(side) * lift
        val oy = Side.dy(side) * lift
        val oz = Side.dz(side) * lift

        blockModel.initialize()
        blockModel.add(2)
        val tileModel = blockModel.tileModel
        val face1 = blockModel.start
        val face2 = face1 + 1
        val c = corners
        tileModel.setPositions(
            face1,
            c[0] + ox, c[1] + oy, c[2] + oz,
            c[3] + ox, c[4] + oy, c[5] + oz,
            c[6] + ox, c[7] + oy, c[8] + oz,
        )
        tileModel.setPositions(
            face2,
            c[0] + ox, c[1] + oy, c[2] + oz,
            c[6] + ox, c[7] + oy, c[8] + oz,
            c[9] + ox, c[10] + oy, c[11] + oz,
        )
        val t = uvs
        tileModel.setUvs(face1, t[0], t[1], t[2], t[3], t[4], t[5])
        tileModel.setUvs(face2, t[0], t[1], t[4], t[5], t[6], t[7])

        val textureId = textureGallery.get(texture)
        tileModel.setMaterialIndex(face1, textureId)
        tileModel.setMaterialIndex(face2, textureId)

        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        tileModel.setColor(face1, r, g, b)
        tileModel.setColor(face2, r, g, b)
        tileModel.setBlocklight(face1, blockLight)
        tileModel.setBlocklight(face2, blockLight)
        tileModel.setSunlight(face1, sunLight)
        tileModel.setSunlight(face2, sunLight)
        tileModel.setAOs(face1, 1f, 1f, 1f)
        tileModel.setAOs(face2, 1f, 1f, 1f)

        if (side == Side.UP) {
            val resource = textures[texture] ?: return
            mapColor.set(resource.colorPremultiplied)
            var light = max(sunLight / 15f, blockLight / 15f)
            light = (1 - renderSettings.ambientLight) * light + renderSettings.ambientLight
            mapColor.r *= r * light
            mapColor.g *= g * light
            mapColor.b *= b * light
            if (mapColor.a > mapColorOpacity) mapColorOpacity = mapColor.a
            color.add(mapColor)
        }
    }

    /** ExtendedFacing of the tile entity, for `extFacing` layers. */
    data class UvContext(val alignment: Boolean, val rotation: Int, val flip: Int) {
        companion object {
            val NONE = UvContext(false, 0, 0)
        }
    }

    companion object {
        /** Offset between stacked layers, in blocks. */
        const val LAYER_LIFT = 1f / 512f
    }
}

/**
 * `gtnh:gt_machine`: `gt.blockmachines` from the tile entity (`BaseMetaTileEntity` / `BaseMetaPipeEntity`):
 * machines, hatches, controllers as layered cubes; pipes, cables and MTE frames by thickness and connections.
 * An unknown or missing MTE becomes a ULV machine casing.
 */
class GtMachineRenderer(resourcePack: ResourcePack, textureGallery: TextureGallery, renderSettings: RenderSettings) :
    GtBlockRenderer(resourcePack, textureGallery, renderSettings) {
    override fun render(data: GtData, block: BlockNeighborhood, blockModel: TileModelView, color: Color): Boolean {
        val te = block.blockEntity as? GtTileEntity
        val mte = te?.let { data.mtes[it.mteId] }
        if (te != null && mte != null) {
            val pipe = mte.pipe
            if (pipe != null) {
                val connections = te.connections and 0x3F
                val colour = te.colourIndex
                for (face in GtPipeGeometry.faces(pipe.thickness, connections)) {
                    renderFace(data, block, blockModel, color, face.side, face.box, GtFaces.pipe(data, pipe, connections, face.side, colour))
                }
                return true
            }
            val sides = GtFaces.machine(data, mte, te)
            if (sides != null && sides.any { it.isNotEmpty() }) {
                renderCube(data, block, blockModel, color, sides, UvContext(mte.alignment, te.rotation, te.flip))
                return true
            }
        }
        renderCube(data, block, blockModel, color, GtFaces.casingFallback(data) ?: return false)
        return true
    }
}

/** `gtnh:gt_ore`: `gt.blockores2..7` by meta: stone background plus the material's tinted ore overlay. */
class GtOreRenderer(resourcePack: ResourcePack, textureGallery: TextureGallery, renderSettings: RenderSettings) :
    GtBlockRenderer(resourcePack, textureGallery, renderSettings) {
    override fun render(data: GtData, block: BlockNeighborhood, blockModel: TileModelView, color: Color): Boolean {
        val state = block.blockState
        val meta = state.properties["meta"]?.toIntOrNull() ?: return false
        val face = GtFaces.ore(data, state.id, meta) ?: return false
        renderCube(data, block, blockModel, color, List(6) { face })
        return true
    }
}

/** `gtnh:gt_frame`: `gt.blockframes`, the material's (`meta & 0xFFF`) tinted frame icon on all sides. */
class GtFrameRenderer(resourcePack: ResourcePack, textureGallery: TextureGallery, renderSettings: RenderSettings) :
    GtBlockRenderer(resourcePack, textureGallery, renderSettings) {
    override fun render(data: GtData, block: BlockNeighborhood, blockModel: TileModelView, color: Color): Boolean {
        val meta = block.blockState.properties["meta"]?.toIntOrNull() ?: return false
        val face = GtFaces.frame(data, meta) ?: return false
        renderCube(data, block, blockModel, color, List(6) { face })
        return true
    }
}
