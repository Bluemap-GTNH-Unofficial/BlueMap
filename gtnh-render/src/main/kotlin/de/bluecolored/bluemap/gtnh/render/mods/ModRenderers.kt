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

import de.bluecolored.bluemap.core.map.hires.block.BlockRendererFactory
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.render.mods.ae2.Ae2CableBusRenderer
import de.bluecolored.bluemap.gtnh.render.mods.ae2.Ae2CableBusTileEntity
import de.bluecolored.bluemap.gtnh.render.mods.bibliocraft.BiblioCraftRenderer
import de.bluecolored.bluemap.gtnh.render.mods.enderio.EioConduitBundleTileEntity
import de.bluecolored.bluemap.gtnh.render.mods.enderio.EnderIoConduitRenderer
import de.bluecolored.bluemap.gtnh.render.mods.bibliocraft.BiblioTileEntity
import de.bluecolored.bluemap.gtnh.render.mods.chisel.ChiselCtmRenderer
import de.bluecolored.bluemap.gtnh.render.mods.ctm.MyCtmRenderer
import de.bluecolored.bluemap.gtnh.render.mods.ironchest.IronChestRenderer
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType

/**
 * Renderers for the most visible non-GregTech blocks of a GTNH base. The exporter selects them per blockstate
 * variant (`"renderer": "gtnh:<name>"`) and keeps the static capture as the variant's model, which each renderer
 * draws when it cannot do better.
 */
object ModRenderers {
    @JvmField
    val CHISEL_CTM: BlockRendererType = type("chisel_ctm", BlockRendererFactory(::ChiselCtmRenderer))

    @JvmField
    val MYCTMLIB: BlockRendererType = type("ctm", BlockRendererFactory(::MyCtmRenderer))

    @JvmField
    val IRON_CHEST: BlockRendererType = type("iron_chest", BlockRendererFactory(::IronChestRenderer))

    @JvmField
    val BIBLIOCRAFT: BlockRendererType = type("bibliocraft", BlockRendererFactory(::BiblioCraftRenderer))

    @JvmField
    val AE2_CABLE_BUS: BlockRendererType = type("ae2_cable_bus", BlockRendererFactory(::Ae2CableBusRenderer))

    @JvmField
    val ENDERIO_CONDUIT: BlockRendererType = type("enderio_conduit", BlockRendererFactory(::EnderIoConduitRenderer))

    val ALL: List<BlockRendererType> = listOf(CHISEL_CTM, MYCTMLIB, IRON_CHEST, BIBLIOCRAFT, AE2_CABLE_BUS, ENDERIO_CONDUIT)

    /** Raw jar textures the tile-entity renderers draw (added to the atlas when the pack has them). */
    val RAW_TEXTURES: Set<String> by lazy {
        IronChestRenderer.RAW_TEXTURES + BiblioCraftRenderer.RAW_TEXTURES + Ae2CableBusRenderer.RAW_TEXTURES + EnderIoConduitRenderer.RAW_TEXTURES
    }

    private val BLOCK_ENTITY_TYPES: List<BlockEntityType> by lazy {
        IronChestRenderer.BLOCK_ENTITY_TYPES + BiblioTileEntity.BLOCK_ENTITY_TYPES + Ae2CableBusTileEntity.BLOCK_ENTITY_TYPES +
            EioConduitBundleTileEntity.BLOCK_ENTITY_TYPE
    }

    @Volatile
    private var registered = false

    /** Registers the renderers, the `gtnh/mods` data extension and the mods' block entity types. Idempotent. */
    @JvmStatic
    @Synchronized
    fun register() {
        if (registered) return
        registered = true
        ALL.forEach(BlockRendererType.REGISTRY::register)
        ResourcePack.Extension.REGISTRY.register(ModAssets.TYPE)
        BLOCK_ENTITY_TYPES.forEach(BlockEntityType.REGISTRY::register)
        ModRenderStats.install()
    }

    private fun type(name: String, factory: BlockRendererFactory): BlockRendererType =
        BlockRendererType.Impl(Key("gtnh", name), factory)
}
