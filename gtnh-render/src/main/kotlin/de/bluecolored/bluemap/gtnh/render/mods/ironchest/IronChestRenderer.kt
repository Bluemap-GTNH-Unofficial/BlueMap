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
package de.bluecolored.bluemap.gtnh.render.mods.ironchest

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluemap.gtnh.render.mods.EntityModel
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModBlockRenderer
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import de.bluecolored.bluenbt.NBTName
import java.util.concurrent.ConcurrentHashMap

/** `TileEntityIronChest`: the front side (ForgeDirection ordinal 2..5; 0 when placed without a player). */
class IronChestTileEntity : MCABlockEntity() {
    @field:NBTName("facing")
    var facing: Int = 0
        private set
}

/**
 * `gtnh:iron_chest`: Iron Chests' `BlockIronChest` as the vanilla chest model (`ModelChest`, closed) with the
 * type's 64×64 model texture (block meta = `IronChestType` ordinal) turned like `TileEntityIronChestRenderer` by
 * the tile entity's `facing`.
 */
class IronChestRenderer(
    resourcePack: ResourcePack,
    textureGallery: TextureGallery,
    renderSettings: RenderSettings,
) : ModBlockRenderer(resourcePack, textureGallery, renderSettings) {
    override fun render(
        assets: ModAssets,
        block: BlockNeighborhood,
        variant: Variant,
        blockModel: TileModelView,
        color: Color,
    ): Boolean {
        val type = TYPE_TEXTURES.getOrNull(meta(block)) ?: return false
        val texture = assets.texture(rawTexture(type)) ?: return false
        val facing = (block.blockEntity as? IronChestTileEntity)?.facing ?: 3
        emit(mesh(texture, facing), block, blockModel, color)
        return true
    }

    companion object {
        /** Model texture per `IronChestType` ordinal (STEEL and SILVER share one). */
        val TYPE_TEXTURES =
            listOf(
                "ironchest",
                "goldchest",
                "diamondchest",
                "copperchest",
                "silverchest",
                "crystalchest",
                "obsidianchest",
                "dirtchest",
                "netheritechest",
                "darksteelchest",
                "silverchest",
            )

        private val TE_TYPES =
            listOf(
                "IRON",
                "GOLD",
                "DIAMOND",
                "COPPER",
                "STEEL",
                "CRYSTAL",
                "OBSIDIAN",
                "DIRTCHEST9000",
                "NETHERITE",
                "DARKSTEEL",
                "SILVER",
            )

        /** Tile entity ids `IronChest.<TYPE>` (`GameRegistry.registerTileEntity(type.clazz, "IronChest." + type.name())`). */
        val BLOCK_ENTITY_TYPES: List<BlockEntityType> =
            TE_TYPES.map {
                BlockEntityType.Impl(Key.minecraft("IronChest.$it"), IronChestTileEntity::class.java)
            }

        fun rawTexture(name: String) = "ironchest:textures/model/$name.png"

        val RAW_TEXTURES: Set<String> = TYPE_TEXTURES.mapTo(HashSet(), ::rawTexture)

        private val meshes = ConcurrentHashMap<Pair<Key, Int>, Mesh>()

        /** The closed chest in block space, front towards ForgeDirection [facing] (others: south, like the TESR). */
        fun mesh(
            texture: Key,
            facing: Int,
        ): Mesh =
            meshes.computeIfAbsent(texture to facing) { (tex, f) ->
                val degrees =
                    when (f) {
                        2 -> 180f
                        4 -> 90f
                        5 -> -90f
                        else -> 0f
                    }
                // glTranslate(x, y + 1, z + 1); glScale(1, -1, -1); glTranslate(.5, .5, .5); glRotate(k, 0, 1, 0); glTranslate(-.5, -.5, -.5)
                val tesr =
                    Transform.gl(
                        Transform.translate(0f, 1f, 1f),
                        Transform.scale(1f, -1f, -1f),
                        Transform.translate(0.5f, 0.5f, 0.5f),
                        Transform.rotate(degrees, 0f, 1f, 0f),
                        Transform.translate(-0.5f, -0.5f, -0.5f),
                        EntityModel.PIXELS,
                    )
                // ModelChest: lid (0, 0) box (0,-5,-14) 14x5x14 at (1,7,15); knob (0, 0) box (-1,-2,-15) 2x4x1 at (8,7,15);
                // bottom (0, 19) box (0,0,0) 14x10x14 at (1,6,1)
                val quads =
                    EntityModel.box(tex, 0f, 0f, 1f, 2f, 1f, 14f, 5f, 14f) +
                        EntityModel.box(tex, 0f, 0f, 7f, 5f, 0f, 2f, 4f, 1f) +
                        EntityModel.box(tex, 0f, 19f, 1f, 6f, 1f, 14f, 10f, 14f)
                Mesh(quads).transformed(tesr)
            }
    }
}
