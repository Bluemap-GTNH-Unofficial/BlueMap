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

import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.block.ExtendedBlock
import de.bluecolored.bluemap.gtnh.render.gregtech.GregTechExtension
import de.bluecolored.bluemap.gtnh.render.gregtech.GtData
import de.bluecolored.bluemap.gtnh.render.gregtech.GtFaces
import de.bluecolored.bluemap.gtnh.render.gregtech.GtTileEntity
import de.bluecolored.bluemap.gtnh.render.gregtech.Side
import de.bluecolored.bluemap.gtnh.render.mods.ModelFaces

/**
 * The texture a block shows on a side, as MyCTMLib compares neighbours. A GregTech machine (hatch, bus, controller)
 * shows the bottom layer of its GT face, the casing background it was built into, so casings connect to the hatches
 * of their multiblock and hatches to each other; any other block shows its model's face ([ModelFaces.face]).
 */
class CtmLooks(
    pack: ResourcePack,
) {
    private val modelFaces = ModelFaces(pack)
    private val gregTech: GtData? = pack.getExtension(GregTechExtension.TYPE)?.data

    fun texture(
        block: ExtendedBlock,
        face: Direction,
    ): Key? {
        val te = block.blockEntity as? GtTileEntity
        if (te != null) {
            val data = gregTech
            val mte = data?.mtes?.get(te.mteId)
            if (data != null && mte != null && mte.pipe == null) {
                return GtFaces
                    .machine(data, mte, te)
                    ?.getOrNull(Side.of(face))
                    ?.firstOrNull { it.texture != null }
                    ?.texture
            }
        }
        return modelFaces.face(block.blockState, face)?.texture
    }
}
