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
package de.bluecolored.bluemap.gtnh.render.mods.bibliocraft

import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluenbt.NBTName

/** The placement fields of BiblioCraft's furniture tile entities (each TE writes its own subset). */
class BiblioTileEntity : MCABlockEntity() {
    @field:NBTName("bookcaseAngle")
    var bookcaseAngle: Int = 0
        private set

    @field:NBTName("genericShelfAngle")
    var genericShelfAngle: Int = 0
        private set

    @field:NBTName("potionshelfAngle")
    var potionShelfAngle: Int = 0
        private set

    @field:NBTName("rackAngle")
    var rackAngle: Int = 0
        private set

    @field:NBTName("labelAngle")
    var labelAngle: Int = 0
        private set

    @field:NBTName("caseAngle")
    var caseAngle: Int = 0
        private set

    /** 1 when the furniture sits half a block back (placed against a slab-like surface). */
    @field:NBTName("shifted")
    var shifted: Int = 0
        private set

    /** Generic shelf: draws its top board. */
    @field:NBTName("RendersTop")
    var rendersTop: Int = 0
        private set

    // tables: which corner legs / bevelled corners (1..4) and the centre leg, from the neighbouring tables
    @field:NBTName("leg1")
    var leg1: Int = 0
        private set

    @field:NBTName("leg2")
    var leg2: Int = 0
        private set

    @field:NBTName("leg3")
    var leg3: Int = 0
        private set

    @field:NBTName("leg4")
    var leg4: Int = 0
        private set

    @field:NBTName("top1")
    var top1: Int = 1
        private set

    @field:NBTName("top2")
    var top2: Int = 1
        private set

    @field:NBTName("top3")
    var top3: Int = 1
        private set

    @field:NBTName("top4")
    var top4: Int = 1
        private set

    @field:NBTName("monoleg")
    var monoleg: Int = 1
        private set

    fun angle(furniture: BiblioCraftRenderer.Furniture): Int = when (furniture) {
        BiblioCraftRenderer.Furniture.BOOKCASE -> bookcaseAngle
        BiblioCraftRenderer.Furniture.SHELF -> genericShelfAngle
        BiblioCraftRenderer.Furniture.POTION_SHELF -> potionShelfAngle
        BiblioCraftRenderer.Furniture.TOOL_RACK -> rackAngle
        BiblioCraftRenderer.Furniture.LABEL -> labelAngle
        BiblioCraftRenderer.Furniture.CASE -> caseAngle
        else -> 0
    }

    /** The fields that change a furniture's mesh. */
    fun stateKey(furniture: BiblioCraftRenderer.Furniture): List<Int> = when (furniture) {
        BiblioCraftRenderer.Furniture.TABLE -> listOf(leg1, leg2, leg3, leg4, top1, top2, top3, top4, monoleg)
        BiblioCraftRenderer.Furniture.SHELF -> listOf(angle(furniture), shifted, rendersTop)
        else -> listOf(angle(furniture), shifted)
    }

    companion object {
        /** Tile entity ids (`GameRegistry.registerTileEntity`) of the drawn furniture. */
        val IDS = listOf(
            "BookcaseTile", "GenericShelfTile", "PotionShelfTile", "ToolRackTile", "WoodLabelTile", "WeaponCaseTile", "TableTile",
        )

        val BLOCK_ENTITY_TYPES: List<BlockEntityType> = IDS.map { BlockEntityType.Impl(Key.minecraft(it), BiblioTileEntity::class.java) }
    }
}
