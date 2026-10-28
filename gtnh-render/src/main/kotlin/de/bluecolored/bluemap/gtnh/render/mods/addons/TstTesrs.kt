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

import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.MeshQuad
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ObjModel
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import de.bluecolored.bluenbt.NBTName
import java.util.concurrent.ConcurrentHashMap

/** `TileStar` (Artificial Star): its size, saved under `renderStatus` (it reads `size`, which it never writes). */
class TstStarTileEntity : MCABlockEntity() {
    @field:NBTName("renderStatus")
    var savedSize: Double = 0.0
        private set
}

/** A tile entity whose TESR draws from block meta / neighbours only (no own NBT). */
class PlainTileEntity : MCABlockEntity()

/** Twist Space Technology (`gtnhcommunitymod` assets): artificial star, eye of wood, power chair, arcane hole. */
object TstTesrs {
    private const val DOMAIN = "gtnhcommunitymod"

    /**
     * `ArtificialStarRender`: `Sol.obj` (radius 3) with `Star.png`, full bright, scaled by the star's size about the
     * block centre. The client's `TileStar` is never synced and keeps its default size 4; the spin is left at 0°.
     */
    object Star : AddonTesr<TstStarTileEntity>(listOf("StarRender"), TstStarTileEntity::class.java) {
        const val MODEL = "$DOMAIN:model/Sol.obj"
        const val TEXTURE = "$DOMAIN:model/Star.png"
        const val DEFAULT_SIZE = 4.0

        override val rawTextures = setOf(TEXTURE)

        fun size(entity: TstStarTileEntity): Float = (if (entity.savedSize > 0) entity.savedSize else DEFAULT_SIZE).toFloat()

        override fun mesh(assets: ModAssets, entity: TstStarTileEntity, block: BlockNeighborhood): Mesh? {
            val obj = assets.obj(MODEL) ?: return null
            val texture = assets.texture(TEXTURE) ?: return null
            val size = size(entity)
            return ObjMeshes.get(obj, texture, size) {
                // glTranslated(.5, .5, .5); glRotated(rotation, 1, 1, 1); glScaled(size)
                obj.mesh(texture).transformed(Transform.gl(CENTER, Transform.scale(size, size, size))).glowing()
            }
        }
    }

    /**
     * `EyeOfWoodRender`: the configured model (default `Thinking.obj`) at the size the client's tile entity eases to
     * (4.5), full bright, turned to face the viewer: drawn facing south, level.
     */
    object EyeOfWood : AddonTesr<PlainTileEntity>(listOf("EyeOfWoodRender"), PlainTileEntity::class.java) {
        const val MODEL = "$DOMAIN:model/Thinking.obj"
        const val TEXTURE = "$DOMAIN:model/Thinking.png"
        const val SIZE = 4.5f

        override val rawTextures = setOf(TEXTURE)

        override fun mesh(assets: ModAssets, entity: PlainTileEntity, block: BlockNeighborhood): Mesh? {
            val obj = assets.obj(MODEL) ?: return null
            val texture = assets.texture(TEXTURE) ?: return null
            // viewer due south and level: glRotated(yaw + 90 = 0, 0, 1, 0); glRotated(-pitch = 0, 1, 0, 0)
            return ObjMeshes.get(obj, texture, SIZE) {
                obj.mesh(texture).transformed(Transform.gl(CENTER, Transform.scale(SIZE, SIZE, SIZE))).doubleSided().glowing()
            }
        }
    }

    /** `TileEntityRenderer` for `TilePowerChair`: `PowerChair.obj` at 0.07 scale, placed and turned by block meta 0..3. */
    object PowerChair : AddonTesr<PlainTileEntity>(listOf("TilePowerChair"), PlainTileEntity::class.java) {
        const val MODEL = "$DOMAIN:model/PowerChair.obj"
        const val TEXTURE = "$DOMAIN:model/PowerChair.png"
        private const val SCALE = 0.07f

        override val rawTextures = setOf(TEXTURE)

        /** Placement by meta: glTranslated(x + dx, y, z + dz); glRotated(degrees, 0, 1, 0). */
        fun placement(meta: Int): Transform = when (meta) {
            0 -> Transform.translate(0.2f, 0f, 0.5f) * Transform.rotate(90f, 0f, 1f, 0f)
            1 -> Transform.translate(0.8f, 0f, 0.5f) * Transform.rotate(-90f, 0f, 1f, 0f)
            2 -> Transform.translate(0.5f, 0f, 0.2f)
            3 -> Transform.translate(0.5f, 0f, 0.8f) * Transform.rotate(180f, 0f, 1f, 0f)
            else -> Transform.IDENTITY
        }

        override fun mesh(assets: ModAssets, entity: PlainTileEntity, block: BlockNeighborhood): Mesh? {
            val obj = assets.obj(MODEL) ?: return null
            val texture = assets.texture(TEXTURE) ?: return null
            val meta = meta(block)
            return ObjMeshes.get(obj, texture, meta) {
                obj.mesh(texture).transformed(placement(meta) * Transform.scale(SCALE, SCALE, SCALE)).doubleSided()
            }
        }
    }

    /**
     * `TileArcaneHoleRender`, seen from afar (the portal-like parallax only runs within ~22 blocks): a grey
     * `particlefield32` plane just inside every face whose neighbour is an opaque block other than a hole.
     */
    object ArcaneHole : AddonTesr<PlainTileEntity>(listOf("TileArcaneHole"), PlainTileEntity::class.java) {
        const val TEXTURE = "thaumcraft:textures/misc/particlefield32.png"
        private const val GREY = 0x7F7F7F

        override val rawTextures = setOf(TEXTURE)

        override fun mesh(assets: ModAssets, entity: PlainTileEntity, block: BlockNeighborhood): Mesh? {
            val texture = assets.texture(TEXTURE) ?: return null
            val own = block.blockState.id
            val faces = Direction.entries.filter { face ->
                val v = face.toVector()
                val neighbor = block.getNeighborBlock(v.x, v.y, v.z)
                neighbor.properties.isCulling && neighbor.blockState.id != own
            }
            return Mesh(faces.map { plane(it, texture) })
        }

        /** The plane 0.001 inside [face], facing into the hole, with the TESR's corner order and uvs. */
        fun plane(face: Direction, texture: Key): MeshQuad {
            val near = 0.001f
            val far = 0.999f
            val p = when (face) {
                Direction.UP -> floatArrayOf(0f, far, 1f, 0f, far, 0f, 1f, far, 0f, 1f, far, 1f)
                Direction.DOWN -> floatArrayOf(0f, near, 0f, 0f, near, 1f, 1f, near, 1f, 1f, near, 0f)
                Direction.NORTH -> floatArrayOf(0f, 1f, near, 0f, 0f, near, 1f, 0f, near, 1f, 1f, near)
                Direction.SOUTH -> floatArrayOf(0f, 0f, far, 0f, 1f, far, 1f, 1f, far, 1f, 0f, far)
                Direction.WEST -> floatArrayOf(near, 1f, 0f, near, 1f, 1f, near, 0f, 1f, near, 0f, 0f)
                Direction.EAST -> floatArrayOf(far, 0f, 0f, far, 0f, 1f, far, 1f, 1f, far, 1f, 0f)
            }
            return MeshQuad(p, floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f), texture, GREY, glow = true)
        }
    }

    val ALL: List<AddonTesr<*>> = listOf(Star, EyeOfWood, PowerChair, ArcaneHole, GtLaserBeacon)
}

/** `glTranslated(x + .5, y + .5, z + .5)`. */
internal val CENTER: Transform = Transform.translate(0.5f, 0.5f, 0.5f)

/** The block's 1.7 metadata (`meta` property). */
internal fun meta(block: BlockNeighborhood): Int = block.blockState.properties["meta"]?.toIntOrNull() ?: 0

/** Transformed Wavefront meshes, per model instance, texture and the parameters that place them. */
internal object ObjMeshes {
    private data class CacheKey(val obj: ObjModel, val texture: Key, val params: Any)

    private val cache = ConcurrentHashMap<CacheKey, Mesh>()

    fun get(obj: ObjModel, texture: Key, params: Any, build: () -> Mesh): Mesh =
        cache.computeIfAbsent(CacheKey(obj, texture, params)) { build() }
}
