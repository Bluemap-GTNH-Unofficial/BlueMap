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

import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluenbt.NBTName

/**
 * GregTech's `BaseMetaTileEntity` (machines) and `BaseMetaPipeEntity` (pipes, cables, frames) as stored in a 1.7
 * chunk: the base fields plus the MTE-specific ones the renderers read. The legacy reader keeps modded block entity
 * ids unchanged, so they resolve as `minecraft:BaseMetaTileEntity` / `minecraft:BaseMetaPipeEntity`.
 * Numeric fields accept any NBT number type (GT writes `mFacing` as short, `mColor` as byte, ...).
 */
class GtTileEntity : MCABlockEntity() {
    @field:NBTName("mID")
    var mteId: Int = 0
        private set

    /** ForgeDirection ordinal of the front (basic machines: the output side). */
    @field:NBTName("mFacing")
    var facing: Int = 0
        private set

    @field:NBTName("mActive")
    var active: Boolean = false
        private set

    /** 0 unpainted, `dye + 1` painted. */
    @field:NBTName("mColor")
    var color: Int = 0
        private set

    /** Basic machines: the front (ForgeDirection ordinal), while [facing] is the output side. */
    @field:NBTName("mMainFacing")
    var mainFacing: Int = -1
        private set

    @field:NBTName("texturePage")
    var texturePage: Int = 0
        private set

    @field:NBTName("textureIndex")
    var textureIndex: Int = 0
        private set

    /** Pipes: bit `1 << side` per connected side. */
    @field:NBTName("mConnections")
    var connections: Int = 0
        private set

    /** Multiblock controllers: ExtendedFacing rotation index (StructureLib `Rotation` ordinal). */
    @field:NBTName("eRotation")
    var rotation: Int = 0
        private set

    /** Multiblock controllers: ExtendedFacing flip index (StructureLib `Flip` ordinal). */
    @field:NBTName("eFlip")
    var flip: Int = 0
        private set

    /** GT colour index (`Dyes` ordinal 0..15), -1 when unpainted. */
    val colourIndex: Int get() = (color and 0xFF) - 1

    /** Hatch casing texture id `page << 7 | index`; 0 = the hatch's own tier casing. */
    val casingTextureId: Int get() = texturePage shl 7 or textureIndex

    companion object {
        @JvmField
        val MACHINE: BlockEntityType = BlockEntityType.Impl(Key.minecraft("BaseMetaTileEntity"), GtTileEntity::class.java)

        @JvmField
        val PIPE: BlockEntityType = BlockEntityType.Impl(Key.minecraft("BaseMetaPipeEntity"), GtTileEntity::class.java)
    }
}
