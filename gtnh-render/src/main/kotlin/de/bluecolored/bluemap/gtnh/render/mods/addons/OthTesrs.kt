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

/** 123Technology `OTERocketRender`: its size, saved under `renderStatus` (it reads `size`, which it never writes). */
class OthRocketTileEntity : MCABlockEntity() {
    @field:NBTName("renderStatus")
    var savedSize: Double = 0.0
        private set
}

/** 123Technology (`123technology` assets). */
object OthTesrs {
    /**
     * `RocketRenderOTH`: GalaxySpace's `tier8rocket.obj` with its texture, full bright and two-sided, at the
     * block centre scaled by the size (1 as the Fire Rocket Assembler places it), turned by meta 2..5.
     */
    object Rocket : AddonTesr<OthRocketTileEntity>(listOf("RocketRender"), OthRocketTileEntity::class.java) {
        const val MODEL = "galaxyspace:models/tier8rocket.obj"
        const val TEXTURE = "galaxyspace:textures/model/tier8rocket.png"

        override val rawTextures = setOf(TEXTURE)

        fun size(entity: OthRocketTileEntity): Float = (if (entity.savedSize > 0) entity.savedSize else 1.0).toFloat()

        fun turn(meta: Int): Float =
            when (meta) {
                2 -> 180f
                4 -> 90f
                5 -> -90f
                else -> 0f
            }

        override fun mesh(
            assets: ModAssets,
            entity: OthRocketTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val obj = assets.obj(MODEL) ?: return null
            val texture = assets.texture(TEXTURE) ?: return null
            val size = size(entity)
            val turn = turn(meta(block))
            return ObjMeshes.get(obj, texture, size to turn) {
                val t = Transform.gl(CENTER, Transform.rotate(turn, 0f, 1f, 0f), Transform.scale(size, size, size))
                obj
                    .mesh(texture)
                    .transformed(t)
                    .doubleSided()
                    .glowing()
            }
        }
    }

    /**
     * `RenderAsuka` / `RenderAyanami` / `RenderTallHat`: a Blockbench Wavefront figure standing at the block centre
     * 0.1 above the floor, turned by meta (4: 90°, 5: -90°, 3: 180°), normally lit. The scale is Waila's HUD
     * scale, 1 by default.
     */
    class Figure(
        id: String,
        val model: String,
        val texture: String,
    ) : AddonTesr<PlainTileEntity>(listOf(id), PlainTileEntity::class.java) {
        override val rawTextures = setOf(texture)

        override fun mesh(
            assets: ModAssets,
            entity: PlainTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val obj = assets.obj(model) ?: return null
            val texture = assets.texture(texture) ?: return null
            val turn = turn(meta(block))
            return ObjMeshes.get(obj, texture, turn) {
                obj.mesh(texture).transformed(Transform.gl(Transform.translate(0.5f, 0.1f, 0.5f), Transform.rotate(turn, 0f, 1f, 0f)))
            }
        }

        companion object {
            fun turn(meta: Int): Float =
                when (meta) {
                    4 -> 90f
                    5 -> -90f
                    3 -> 180f
                    else -> 0f
                }
        }
    }

    private const val DOMAIN = "123technology"

    val ASUKA = Figure("modelAsuka", "$DOMAIN:models/Asuka.obj", "$DOMAIN:models/asukaTexture.png")
    val AYANAMI = Figure("modelAyanami", "$DOMAIN:models/Ayanami.obj", "$DOMAIN:models/ayanamiTexture.png")
    val TALL_HAT = Figure("modelTallHat", "$DOMAIN:models/TallHatLaodeng.obj", "$DOMAIN:models/tallHatLaodengTexture.png")

    val ALL: List<AddonTesr<*>> = listOf(Rocket, ASUKA, AYANAMI, TALL_HAT)
}
