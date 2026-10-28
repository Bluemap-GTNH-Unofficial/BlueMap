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

import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import de.bluecolored.bluenbt.NBTName

/** Box++ `TEBoxRing`: whether the encapsulator's big rings show, their scale and count. */
class BoxRingTileEntity : MCABlockEntity() {
    /** Set by the formed Production Line Encapsulator (or toggled by hand). */
    @field:NBTName("renderStatus")
    var renderStatus: Boolean = false
        private set

    /** The encapsulator GUI's ring switch. */
    @field:NBTName("switch")
    var ringSwitch: Boolean = true
        private set

    @field:NBTName("scale")
    var scale: Float = 1f
        private set

    @field:NBTName("count")
    var count: Int = 1
        private set

    /** The rings drawn: the small core always, the 1..3 big ones while shown. */
    fun ringScales(): List<Float> {
        val big =
            if (ringSwitch && renderStatus) {
                BoxTesrs.Ring.BIG_SCALES
                    .take(count.coerceIn(1, 3))
                    .map { it * scale }
            } else {
                emptyList()
            }
        return big + BoxTesrs.Ring.CORE_SCALE
    }
}

/** Box++ (`boxplusplus`). */
object BoxTesrs {
    /**
     * `RenderBoxRing`: `Ring.obj` (radius ~31.5 model units) with `ring.png`, full bright and two-sided, about the
     * block centre: a 0.0118-scale core ring always and, on a formed encapsulator, 1..3 big rings at 1.1, 1.4 and
     * 1.8 × `scale`. The rings spin on different axes; at time 0 they all lie flat and concentric.
     */
    object Ring : AddonTesr<BoxRingTileEntity>(listOf("BoxRing"), BoxRingTileEntity::class.java) {
        const val MODEL = "boxplusplus:models/Ring.obj"
        const val TEXTURE = "boxplusplus:textures/models/ring.png"
        val BIG_SCALES = listOf(1.1f, 1.4f, 1.8f)
        const val CORE_SCALE = 0.0118f

        override val rawTextures = setOf(TEXTURE)

        override fun mesh(
            assets: ModAssets,
            entity: BoxRingTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val obj = assets.obj(MODEL) ?: return null
            val texture = assets.texture(TEXTURE) ?: return null
            val rings =
                entity.ringScales().map { s ->
                    ObjMeshes.get(obj, texture, s) {
                        obj
                            .mesh(texture)
                            .transformed(Transform.gl(CENTER, Transform.scale(s, s, s)))
                            .doubleSided()
                            .glowing()
                    }
                }
            return Mesh(rings.flatMap { it.quads })
        }
    }

    val ALL: List<AddonTesr<*>> = listOf(Ring)
}
