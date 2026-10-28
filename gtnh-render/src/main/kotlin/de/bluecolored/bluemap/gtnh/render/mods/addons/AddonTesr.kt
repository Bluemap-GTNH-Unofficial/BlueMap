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
package de.bluecolored.bluemap.gtnh.render.mods.addons

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererFactory
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.MeshQuad
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModBlockRenderer
import de.bluecolored.bluemap.gtnh.render.mods.ModRenderStats

/**
 * A static port of one community addon's `TileEntitySpecialRenderer`: the geometry its TESR draws for a block
 * at time 0, chosen from the tile entity's saved NBT (and the block's meta / neighbours where the TESR uses them).
 */
abstract class AddonTesr<T : MCABlockEntity>(
    /** Tile entity ids (`GameRegistry.registerTileEntity`) the TESR is bound to. */
    val ids: List<String>,
    private val entityClass: Class<T>,
) {
    /** Raw jar textures the TESR binds (`<domain>:<path>.png`), added to the atlas. */
    abstract val rawTextures: Set<String>

    /** The block also renders as a normal block, under the TESR: its exported model is drawn as well. */
    open val drawsBlockModel: Boolean get() = false

    /** The TESR's geometry in block space, or null when the pack lacks its assets (the variant model is drawn). */
    abstract fun mesh(assets: ModAssets, entity: T, block: BlockNeighborhood): Mesh?

    val blockEntityTypes: List<BlockEntityType> get() = ids.map { BlockEntityType.Impl(Key.minecraft(it), entityClass) }

    @Suppress("UNCHECKED_CAST")
    internal fun meshOf(assets: ModAssets, entity: MCABlockEntity, block: BlockNeighborhood): Mesh? =
        if (entityClass.isInstance(entity)) mesh(assets, entity as T, block) else null
}

/**
 * `gtnh:addon_tesr`: blocks of community addons (Twist Space Technology, GT: Not Leisure, Box++, 123Technology)
 * whose look comes from a TESR. The exporter selects it by tile entity class; the tile entity id picks the port.
 */
class AddonTesrRenderer(resourcePack: ResourcePack, textureGallery: TextureGallery, renderSettings: RenderSettings) :
    ModBlockRenderer(resourcePack, textureGallery, renderSettings) {
    override fun render(assets: ModAssets, block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color): Boolean {
        val entity = block.blockEntity as? MCABlockEntity ?: return false
        val tesr = AddonTesrs.byId[entity.id] ?: return false
        val mesh = tesr.meshOf(assets, entity, block) ?: return false
        emit(mesh, block, blockModel, color)
        if (tesr.drawsBlockModel) renderOwnModel(block, variant, blockModel, color)
        // per port, next to the renderer's own rendered/fallback counts
        ModRenderStats.rendered(tesr)
        return true
    }
}

/** The addon TESR ports, their renderer type, block entity types and textures. */
object AddonTesrs {
    val ALL: List<AddonTesr<*>> = TstTesrs.ALL + GtnlTesrs.ALL + BoxTesrs.ALL + OthTesrs.ALL

    internal val byId: Map<Key, AddonTesr<*>> = ALL.flatMap { tesr -> tesr.ids.map { Key.minecraft(it) to tesr } }.toMap()

    @JvmField
    val TYPE: BlockRendererType = BlockRendererType.Impl(Key("gtnh", "addon_tesr"), BlockRendererFactory(::AddonTesrRenderer))

    val BLOCK_ENTITY_TYPES: List<BlockEntityType> get() = ALL.flatMap { it.blockEntityTypes }

    val RAW_TEXTURES: Set<String> get() = ALL.flatMapTo(HashSet()) { it.rawTextures }

    /** A plain white texture the exporter adds, for untextured (`GL_TEXTURE_2D` off) TESR geometry. */
    const val WHITE = "gtnh:textures/misc/white.png"
}

/** The mesh drawn with full block light (`setLightmapTextureCoords(240, 240)`). */
internal fun Mesh.glowing(): Mesh = Mesh(quads.map { MeshQuad(it.positions, it.uvs, it.texture, it.rgb, true, it.isTriangle, it.tint) })

/** Every quad also from behind, for geometry a TESR draws with `GL_CULL_FACE` off (the map draws front faces only). */
internal fun Mesh.doubleSided(): Mesh = Mesh(quads + quads.map { it.flipped() })

private fun MeshQuad.flipped(): MeshQuad {
    // reversed winding keeps each vertex's uv: 0,3,2,1 (triangles: 0,2,1 with the last repeated)
    val order = if (isTriangle) intArrayOf(0, 2, 1, 1) else intArrayOf(0, 3, 2, 1)
    val p = FloatArray(12)
    val uv = FloatArray(8)
    for ((i, src) in order.withIndex()) {
        positions.copyInto(p, i * 3, src * 3, src * 3 + 3)
        uvs.copyInto(uv, i * 2, src * 2, src * 2 + 2)
    }
    return MeshQuad(p, uv, texture, rgb, glow, isTriangle, tint)
}

/** `0xRRGGBB` of GL colour components 0..1. */
internal fun rgb(r: Float, g: Float, b: Float): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (c(r) shl 16) or (c(g) shl 8) or c(b)
}
