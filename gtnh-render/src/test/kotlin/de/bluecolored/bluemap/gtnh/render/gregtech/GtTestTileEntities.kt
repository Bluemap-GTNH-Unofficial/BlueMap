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

import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.gtnh.legacy.LegacyBlockEntities
import de.bluecolored.bluenbt.NBTWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Reads tile entities the way the legacy chunk loader does, from NBT written with GT's tag types. */
object GtTestTileEntities {
    init {
        GregTechRender.register()
    }

    fun read(
        id: String,
        fields: NBTWriter.() -> Unit,
    ): BlockEntity? {
        val out = ByteArrayOutputStream()
        NBTWriter(out).use { w ->
            w.beginCompound()
            w.name("id").value(id)
            w.name("x").value(1)
            w.name("y").value(2)
            w.name("z").value(3)
            w.fields()
            w.endCompound()
        }
        return LegacyBlockEntities.blueNbt().read(ByteArrayInputStream(out.toByteArray()), BlockEntity::class.java)
    }

    /** A `BaseMetaTileEntity` with GT's NBT types: `mFacing` short, `mActive`/`mColor` byte, the rest int/byte. */
    fun machine(
        id: Int,
        facing: Int = 2,
        active: Boolean = false,
        color: Int = 0,
        mainFacing: Int? = null,
        texturePage: Int? = null,
        textureIndex: Int? = null,
        rotation: Int? = null,
        flip: Int? = null,
    ): GtTileEntity =
        read("BaseMetaTileEntity") {
            name("mID").value(id)
            name("mFacing").value(facing.toShort())
            name("mActive").value((if (active) 1 else 0).toByte())
            name("mColor").value(color.toByte())
            mainFacing?.let { name("mMainFacing").value(it) }
            texturePage?.let { name("texturePage").value(it) }
            textureIndex?.let { name("textureIndex").value(it) }
            rotation?.let { name("eRotation").value(it.toByte()) }
            flip?.let { name("eFlip").value(it.toByte()) }
        } as GtTileEntity

    /** A `BaseMetaPipeEntity`: `mConnections` and `mColor` are bytes. */
    fun pipe(
        id: Int,
        connections: Int,
        color: Int = 0,
    ): GtTileEntity =
        read("BaseMetaPipeEntity") {
            name("mID").value(id)
            name("mConnections").value(connections.toByte())
            name("mColor").value(color.toByte())
        } as GtTileEntity
}
