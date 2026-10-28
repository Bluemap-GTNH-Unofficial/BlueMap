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
import de.bluecolored.bluemap.core.world.block.BlockAccess
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluemap.gtnh.render.mods.EntityModel
import de.bluecolored.bluemap.gtnh.render.mods.FaceTexture
import de.bluecolored.bluemap.gtnh.render.mods.Faces
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.MeshQuad
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModelFaces
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import de.bluecolored.bluemap.gtnh.render.mods.ae2.Ae2Cable
import de.bluecolored.bluemap.gtnh.render.mods.ae2.Ae2CableBusTileEntity
import de.bluecolored.bluenbt.NBTName
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** GTNL `TileEntityBeamFormer`: AE2's saved orientation (`ForgeDirection` names) and the beam switch. */
class BeamFormerTileEntity : MCABlockEntity() {
    @field:NBTName("orientation_forward")
    var forwardName: String? = null
        private set

    @field:NBTName("orientation_up")
    var upName: String? = null
        private set

    @field:NBTName("hideBeam")
    var hideBeam: Boolean = false
        private set

    /** The front (beam) side, null when missing or `UNKNOWN`. */
    val forward: Direction? get() = direction(forwardName)

    val up: Direction? get() = direction(upName)

    private fun direction(name: String?): Direction? = Direction.entries.firstOrNull { it.name == name }
}

/** One entry of Thaumcraft's `AspectList.writeToNBT` list. */
class JarAspect {
    @field:NBTName("key")
    var key: String = ""
        private set

    @field:NBTName("amount")
    var amount: Int = 0
        private set
}

/** Thaumcraft's `AspectList` compound: the `Aspects` list. */
class JarAspectList {
    @field:NBTName("Aspects")
    var aspects: List<JarAspect> = emptyList()
        private set
}

/** GTNL `TileEntityMultiEssentiaJar` (`writeCustomNBT`): the stored aspects and the one drawn on top. */
class MultiEssentiaJarTileEntity : MCABlockEntity() {
    @field:NBTName("StoredAspects")
    var storedAspects: JarAspectList? = null
        private set

    @field:NBTName("ActiveAspect")
    var activeAspect: String = ""
        private set

    /** Aspect tag -> amount (> 0), duplicate tags summed like `AspectList.add`. */
    fun amounts(): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (a in storedAspects?.aspects.orEmpty()) if (a.key.isNotEmpty()) result.merge(a.key, a.amount, Int::plus)
        return result.filterValues { it > 0 }
    }
}

/**
 * GT: Not Leisure (`sciencenotleisure`). Its laser beacon shares [GtLaserBeacon]. Not ported: the April-Fools-only
 * `BallRenderer` (a time- and physics-driven overlay on GT controllers, no block of its own) and the multi essentia
 * tube, whose TESR (Thaumcraft's buffer-tube renderer) only adds valves on choked sides, which the static map has
 * no state for; the tube itself is the exporter's capture.
 */
object GtnlTesrs {
    private const val DOMAIN = "sciencenotleisure"

    /** `glRotatef(..., 0, 1, 0)` for a facing meta, as GTNL's figures use it (4: 90°, 5: -90°, 3: 180°). */
    fun turn(meta: Int): Float = OthTesrs.Figure.turn(meta)

    /**
     * Beam former: the block is AE2's world renderer drawing GTNL's Blockbench model `beam_former.json` turned by
     * the saved orientation (model north = front, model up = `orientation_up`); its TESR adds the beacon-style beam
     * to the paired former in front.
     *
     * The pairing is runtime-only, so it is reconstructed like `BeamFormerLink`: walking up to 256 blocks along the
     * front, the first beam former met pairs if it faces back (else no beam), an opaque block stops the walk. Both
     * formers' colours are the AE2 cable behind them (fluix = any); differing colours do not connect, the beam takes
     * the coloured one. In game whichever former ticks first owns the beam; here the one facing south, east or up
     * draws it, so it is drawn once. Formers on cable-bus parts are not recognised (the walk passes their bus).
     *
     * The beam at time 0: a square prism 0.12·√½ half-wide about the axis, from 3/16 past this former's centre to
     * 3/16 before the other's, full bright and two-sided, in `beacon_beam.png` tinted with the AE colour. The map
     * clamps texture coordinates, so the texture is stretched once over the length instead of repeating every
     * 0.24 blocks. The additive blending (alpha 0.8) and the 0.125-alpha glow shell cannot be drawn: the shell is
     * left out, the inner beam is drawn with the texture's own alpha.
     */
    object BeamFormer : AddonTesr<BeamFormerTileEntity>(listOf("beam_former_tile_entity"), BeamFormerTileEntity::class.java) {
        const val MODEL = "$DOMAIN:models/block/beam_former.json"
        const val TEXTURE = "$DOMAIN:textures/blocks/beam_former/beam_former_block_model.png"
        const val BEAM_TEXTURE = "minecraft:textures/entity/beacon_beam.png"

        /** `MainConfig.machine.beamFormerLength`'s default. */
        const val MAX_LENGTH = 256

        /** `getRenderOffset()`: the beam starts and ends 3/16 from the formers' centres. */
        const val OFFSET = 3f / 16f

        /** `0.075 * 1.6`; at time 0 the rotating square is axis-aligned, half-width `radius · √½`. */
        const val BEAM_RADIUS = 0.12f

        /**
         * `AEColor.mediumVariant` by ordinal; 16 = Transparent (fluix). Values from AE2's `AEColor` (MIT, Copyright (c)
         * 2013 AlgorithmX2).
         */
        val COLORS =
            intArrayOf(
                0xDBDBDB,
                0xFAAE44,
                0xB82AB8,
                0x82ACE7,
                0xF8FF4A,
                0xBBFF51,
                0xF8B5D7,
                0xA0A0A0,
                0xCDCDCD,
                0x51AAC6,
                0xA453CE,
                0x514AFF,
                0xB7967F,
                0x60E32E,
                0xFF003C,
                0x565656,
                0x895CA8,
            )

        override val rawTextures = setOf(TEXTURE, BEAM_TEXTURE)

        /** The beam to a paired former [distance] blocks ahead, in AE colour ordinal [color]. */
        data class Link(
            val distance: Int,
            val color: Int,
        )

        override fun mesh(
            assets: ModAssets,
            entity: BeamFormerTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val forward = entity.forward ?: return null
            val up = entity.up ?: return null
            val json = assets.json(MODEL) ?: return null
            val model =
                JsonMeshes.get(json, forward to up) {
                    val orientation = orientation(forward, up) ?: return@get null
                    JsonModelMesh.mesh(json) { assets.texture(JsonModelMesh.rawTexture(it)) }?.transformed(orientation)
                } ?: return null
            if (!drawsBeam(forward) || entity.hideBeam) return model
            val link = link(entity, block) ?: return model
            val beamTexture = assets.texture(BEAM_TEXTURE) ?: return model
            return model + beam(beamTexture, forward, link.distance, COLORS[link.color])
        }

        /** The end that draws a pair's beam: the one facing the positive axis. */
        fun drawsBeam(forward: Direction): Boolean = forward == Direction.SOUTH || forward == Direction.EAST || forward == Direction.UP

        /**
         * About the block centre: model +X -> forward × up, +Y -> up, +Z -> back. Null for an impossible pair
         * (same axis).
         */
        fun orientation(
            forward: Direction,
            up: Direction,
        ): Transform? {
            if (forward.axis == up.axis) return null
            // first stand the model's +Y up, then spin it about up until its north side faces forward
            val tilt =
                when (up) {
                    Direction.UP -> Transform.IDENTITY
                    Direction.DOWN -> Transform.rotate(180f, 1f, 0f, 0f)
                    Direction.NORTH -> Transform.rotate(-90f, 1f, 0f, 0f)
                    Direction.SOUTH -> Transform.rotate(90f, 1f, 0f, 0f)
                    Direction.EAST -> Transform.rotate(-90f, 0f, 0f, 1f)
                    Direction.WEST -> Transform.rotate(90f, 0f, 0f, 1f)
                }
            val u = up.toVector()
            val f = forward.toVector()
            val north = floatArrayOf(0f, 0f, -1f)
            val out = FloatArray(3)
            for (quarter in 0 until 4) {
                val r = Transform.rotate(90f * quarter, u.x.toFloat(), u.y.toFloat(), u.z.toFloat()) * tilt
                r.apply(north, 0, out, 0)
                if (out[0].roundToInt() == f.x && out[1].roundToInt() == f.y && out[2].roundToInt() == f.z) {
                    return Transform.gl(CENTER, r, Transform.translate(-0.5f, -0.5f, -0.5f))
                }
            }
            return null
        }

        /** The paired former ahead and the beam's colour, or null when there is none or the pair is hidden. */
        fun link(
            entity: BeamFormerTileEntity,
            block: BlockNeighborhood,
        ): Link? {
            val forward = entity.forward ?: return null
            val f = forward.toVector()
            val own = cableColor(block.getNeighborBlock(-f.x, -f.y, -f.z))
            // a separate cursor: the neighbourhood's cache wraps every 8 blocks
            val probe = block.copy()
            for (d in 1..MAX_LENGTH) {
                probe.set(block.x + f.x * d, block.y + f.y * d, block.z + f.z * d)
                val other = probe.blockEntity
                if (other is BeamFormerTileEntity) {
                    if (other.forward != forward.opposite || other.hideBeam) return null
                    probe.set(block.x + f.x * (d + 1), block.y + f.y * (d + 1), block.z + f.z * (d + 1))
                    val color = beamColor(own, cableColor(probe)) ?: return null
                    return Link(d, color)
                }
                if (probe.properties.isCulling) return null
            }
            return null
        }

        /** The AE colour ordinal of the cable in [block] (fluix if it is none). */
        fun cableColor(block: BlockAccess): Int = (block.blockEntity as? Ae2CableBusTileEntity)?.cable?.color ?: Ae2Cable.FLUIX

        /** `connectionColor`: null if the colours do not match, else the source's unless it is fluix. */
        fun beamColor(
            source: Int,
            target: Int,
        ): Int? =
            when {
                source == Ae2Cable.FLUIX -> target
                target == Ae2Cable.FLUIX || target == source -> source
                else -> null
            }

        /**
         * The inner beam to a former [distance] blocks along [forward]: `glTranslated(.5, .5, .5)`, yaw about Y,
         * pitch about X (local +Y -> forward), `glTranslated(-.5, start, -.5)`, then four sides around (.5, .5).
         * The pitch/yaw lines are adapted from GTNL `BeamFormerRenderHelper` (LGPL-3.0) and remain available under
         * LGPL-3.0; see NOTICE.md.
         */
        fun beam(
            texture: Key,
            forward: Direction,
            distance: Int,
            rgb: Int,
        ): Mesh {
            val length = distance - 2 * OFFSET
            if (length <= 0) return Mesh.EMPTY
            val v = forward.toVector()
            val pitch = Math.toDegrees(atan2(sqrt((v.x * v.x + v.z * v.z).toDouble()), v.y.toDouble())).toFloat()
            val yaw = (90 - Math.toDegrees(atan2(v.z.toDouble(), v.x.toDouble()))).toFloat()
            val place =
                Transform.gl(
                    CENTER,
                    Transform.rotate(yaw, 0f, 1f, 0f),
                    Transform.rotate(pitch, 1f, 0f, 0f),
                    Transform.translate(-0.5f, OFFSET, -0.5f),
                )
            val h = BEAM_RADIUS * sqrt(0.5f)
            val lo = 0.5f - h
            val hi = 0.5f + h
            // the TESR's corner order at angle π/4
            val corners = arrayOf(floatArrayOf(lo, hi), floatArrayOf(hi, hi), floatArrayOf(hi, lo), floatArrayOf(lo, lo))
            val quads =
                (0 until 4).map { i ->
                    val a = corners[i]
                    val b = corners[(i + 1) % 4]
                    val p = floatArrayOf(a[0], 0f, a[1], a[0], length, a[1], b[0], length, b[1], b[0], 0f, b[1])
                    MeshQuad(p, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f), texture, rgb).transformed(place)
                }
            return Mesh(quads).glowing().doubleSided()
        }
    }

    /**
     * Multi essentia jar: the block is Thaumcraft's jar (the exporter's capture); the TESR fills it with one layer
     * per stored aspect, in the aspect's colour and Thaumcraft's `animatedglow` liquid texture: aspects sorted by
     * tag with the active one on top, each layer at least a sliver high, all together up to 0.625 high at the
     * 4096 capacity, x/z 0.25..0.75, from 0.0725 up. The TESR lights them at least at block light 12 of 15; the
     * map cannot floor the light, so the liquid is drawn full bright (closer in the dark than the world light).
     * The aspect filter label is not drawn.
     */
    object MultiEssentiaJar : AddonTesr<MultiEssentiaJarTileEntity>(
        listOf("multi_essentia_jar_tile_entity"),
        MultiEssentiaJarTileEntity::class.java,
    ) {
        const val TEXTURE = "thaumcraft:textures/blocks/animatedglow.png"
        const val CAPACITY = 4096
        private const val LIQUID_MIN = 0.0625
        private const val LIQUID_HEIGHT = 0.625
        private const val MIN_VISIBLE_LAYER = 0.015625
        private const val MIN_XZ = 0.25f
        private const val MAX_XZ = 0.75f

        /** `glTranslatef(x + .5, y + .01, z + .5)` with the faces drawn at (-.5, 0, -.5). */
        private const val LIFT = 0.01f

        override val rawTextures = setOf(TEXTURE)

        override val drawsBlockModel: Boolean get() = true

        /** One liquid layer, heights inside the TESR's frame (add [LIFT] for block space). */
        data class Layer(
            val aspect: String,
            val bottom: Double,
            val top: Double,
        )

        /**
         * The layers bottom to top. Adapted from GTNL `MultiEssentiaJarRenderer` (LGPL-3.0); this function remains
         * available under LGPL-3.0, see NOTICE.md.
         */
        fun layers(entity: MultiEssentiaJarTileEntity): List<Layer> {
            val amounts = entity.amounts()
            if (amounts.isEmpty()) return emptyList()
            val order = amounts.keys.sorted().toMutableList()
            // ensureActiveAspect: an absent or empty active aspect falls back to the first by tag
            val active = entity.activeAspect.takeIf { it in amounts } ?: order.first()
            order.remove(active)
            order.add(active)
            val n = order.size
            val total = amounts.values.sum()
            val actual = LIQUID_HEIGHT * total / CAPACITY
            val visible = min(LIQUID_HEIGHT, max(actual, n * MIN_VISIBLE_LAYER))
            val minLayer = min(MIN_VISIBLE_LAYER, visible / (n * 2.0))
            val distributable = visible - minLayer * n
            val top = LIQUID_MIN + visible
            var bottom = LIQUID_MIN
            return order.mapIndexed { i, aspect ->
                val height = minLayer + distributable * amounts.getValue(aspect) / total
                val layerTop = if (i == n - 1) top else min(top, bottom + height)
                Layer(aspect, bottom, layerTop).also { bottom = layerTop }
            }
        }

        override fun mesh(
            assets: ModAssets,
            entity: MultiEssentiaJarTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val layers = layers(entity)
            if (layers.isEmpty()) return Mesh.EMPTY
            val texture = assets.texture(TEXTURE) ?: return null
            val quads = ArrayList<MeshQuad>()
            for ((i, layer) in layers.withIndex()) {
                val rgb = assets.aspectColors[layer.aspect] ?: MeshQuad.WHITE
                val faces = if (i == 0) Faces.ALL else Faces.ALL - Direction.DOWN
                val from = floatArrayOf(MIN_XZ, layer.bottom.toFloat(), MIN_XZ)
                val to = floatArrayOf(MAX_XZ, layer.top.toFloat(), MAX_XZ)
                quads.addAll(renderBlocksBox(from, to, texture, rgb, faces, glow = true))
            }
            return Mesh(quads).transformed(Transform.translate(0f, LIFT, 0f))
        }
    }

    /**
     * Water candle: the block draws nothing but its TESR's `ModelBase` model — four 2×2 px candles 3..6 px high
     * and a crossed pair of 1×1 px flame planes on each — straight in block space (not flipped like entity
     * models), `water_candle.png` 16×16.
     */
    object WaterCandle : AddonTesr<PlainTileEntity>(listOf("water_candle_tile_entity"), PlainTileEntity::class.java) {
        const val TEXTURE = "$DOMAIN:textures/blocks/water_candle.png"

        /** Candle boxes `(x, y, z, w, h, d)` in the bone, texture offset (0, 8). */
        val CANDLES =
            listOf(
                floatArrayOf(-8f, 0f, 8f, 2f, 3f, 2f),
                floatArrayOf(-11f, 0f, 8f, 2f, 5f, 2f),
                floatArrayOf(-7f, 0f, 5f, 2f, 5f, 2f),
                floatArrayOf(-10f, 0f, 5f, 2f, 6f, 2f),
            )

        /** Flame rotation points in the bone; each holds a 1×1×0 px plane turned ±0.7854 rad about Y. */
        val FLAMES = listOf(floatArrayOf(-9f, 6f, 6f), floatArrayOf(-6f, 5f, 6f), floatArrayOf(-10f, 5f, 9f), floatArrayOf(-7f, 3f, 9f))

        private const val FLAME_ANGLE = 0.7854

        override val rawTextures = setOf(TEXTURE)

        private val meshes = ConcurrentHashMap<Key, Mesh>()

        override fun mesh(
            assets: ModAssets,
            entity: PlainTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val texture = assets.texture(TEXTURE) ?: return null
            return meshes.computeIfAbsent(texture, ::build)
        }

        fun build(texture: Key): Mesh {
            // glTranslated(x + .5, y, z + .5); bone.render(1/16): its rotation point (8, 0, -8)
            val bone = Transform.gl(Transform.translate(0.5f, 0f, 0.5f), EntityModel.PIXELS, Transform.translate(8f, 0f, -8f))
            val quads = ArrayList<MeshQuad>()
            for (c in CANDLES) {
                for (q in EntityModel.box(texture, 0f, 8f, c[0], c[1], c[2], c[3], c[4], c[5], 16f, 16f)) quads.add(q.transformed(bone))
            }
            val degrees = Math.toDegrees(FLAME_ANGLE).toFloat()
            for (point in FLAMES) {
                for (angle in floatArrayOf(degrees, -degrees)) {
                    val child = bone * Transform.translate(point[0], point[1], point[2]) * Transform.rotate(angle, 0f, 1f, 0f)
                    for (q in EntityModel.box(texture, 0f, 0f, -0.5f, 0f, 0f, 1f, 1f, 0f, 16f, 16f)) {
                        // a zero-depth box keeps only its front and back
                        if (ModelFaces.area(q) > 1e-6f) quads.add(q.transformed(child))
                    }
                }
            }
            return Mesh(quads)
        }
    }

    /**
     * Player doll: `player_doll.obj` (a sitting player) with the default skin, which GTNL marks slim (Alex arms):
     * body, head, legs and arms with their outer layers, no cape. Placed at the block's bottom centre and turned
     * by meta (4: 90°, 5: -90°, 3: 180°) and always another 180°, so it faces the meta's side. Downloaded owner
     * skins are not available to the map.
     */
    object PlayerDoll : AddonTesr<PlainTileEntity>(listOf("player_doll_tile_entity"), PlainTileEntity::class.java) {
        const val MODEL = "$DOMAIN:models/player_doll.obj"
        const val TEXTURE = "$DOMAIN:models/skin.png"

        val PARTS =
            listOf(
                "Body",
                "Body_Layer",
                "Head",
                "Head_Layer",
                "Left_Leg",
                "Left_Leg_Layer",
                "Right_Leg",
                "Right_Leg_Layer",
                "Left_Arm_Alex",
                "Left_Arm_Alex_Layer",
                "Right_Arm_Alex",
                "Right_Arm_Alex_Layer",
            )

        override val rawTextures = setOf(TEXTURE)

        fun placement(meta: Int): Transform =
            Transform.gl(Transform.translate(0.5f, 0f, 0.5f), Transform.rotate(turn(meta), 0f, 1f, 0f), Transform.rotate(180f, 0f, 1f, 0f))

        override fun mesh(
            assets: ModAssets,
            entity: PlainTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val obj = assets.obj(MODEL) ?: return null
            val texture = assets.texture(TEXTURE) ?: return null
            val meta = meta(block)
            return ObjMeshes.get(obj, texture, turn(meta)) { obj.mesh(texture, PARTS).transformed(placement(meta)) }
        }
    }

    /**
     * Pigmee fumo: Blockbench model `pigmee_fumo.json` (body, nose, four legs) turned about the block centre so
     * the nose points to the meta's side (2 and others north, 3 south, 4 west, 5 east). A right-clicked fumo
     * spins; the map shows it at 0°.
     */
    object PigmeeFumo : AddonTesr<PlainTileEntity>(listOf("pigmee_fumo_tile_entity"), PlainTileEntity::class.java) {
        const val MODEL = "$DOMAIN:models/block/pigmee_fumo.json"
        const val TEXTURE = "$DOMAIN:textures/blocks/pigmee_fumo.png"

        override val rawTextures = setOf(TEXTURE)

        override fun mesh(
            assets: ModAssets,
            entity: PlainTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val json = assets.json(MODEL) ?: return null
            // rotateTowards(-facing, up): model +Z to the back, i.e. the nose (north) to the facing
            val degrees = turn(meta(block))
            return JsonMeshes.get(json, degrees) {
                JsonModelMesh.mesh(json) { assets.texture(JsonModelMesh.rawTexture(it)) }?.transformed(Transform.rotateYCentered(degrees))
            }
        }
    }

    /**
     * Ender elevator (block, slab and carpet share the tile entity): the TESR draws the block's box — full, half
     * or 1 px high by block — with `ender_elevator.png` on all sides in the meta's colour (`COLOR_TABLE`,
     * opaque), plus the white `ender_elevator_overlay.png` 0.001 above the top. A disguise block (`disguiseId`, a
     * world-specific numeric id) is not resolved: the undisguised elevator is drawn.
     */
    object EnderElevator : AddonTesr<PlainTileEntity>(listOf("ender_elevator_tile_entity"), PlainTileEntity::class.java) {
        const val TEXTURE = "$DOMAIN:textures/blocks/ender_elevator.png"
        const val OVERLAY = "$DOMAIN:textures/blocks/ender_elevator_overlay.png"

        /** `BlockEnderElevator.COLOR_TABLE`, by meta. */
        val COLORS =
            intArrayOf(
                0xFFFFFF,
                0xD87F33,
                0xB24CA5,
                0x6699D8,
                0xE5E533,
                0x7FCC19,
                0xF27FA5,
                0x4C4C4C,
                0x999999,
                0x4C7F99,
                0x7F3FB2,
                0x334CB2,
                0x664C33,
                0x667F33,
                0x993333,
                0x191919,
            )

        override val rawTextures = setOf(TEXTURE, OVERLAY)

        /** The block's height by registry name: `ender_elevator_slab` 0.5, `_carpet` 1/16, else full. */
        fun height(blockName: String): Float =
            when {
                blockName.endsWith("_slab") -> 0.5f
                blockName.endsWith("_carpet") -> 0.0625f
                else -> 1f
            }

        fun color(meta: Int): Int = COLORS[Math.floorMod(meta, COLORS.size)]

        private data class MeshKey(
            val texture: Key,
            val overlay: Key,
            val height: Float,
            val rgb: Int,
        )

        private val meshes = ConcurrentHashMap<MeshKey, Mesh>()

        override fun mesh(
            assets: ModAssets,
            entity: PlainTileEntity,
            block: BlockNeighborhood,
        ): Mesh? {
            val texture = assets.texture(TEXTURE) ?: return null
            val overlay = assets.texture(OVERLAY) ?: return null
            val key = MeshKey(texture, overlay, height(block.blockState.id.value), color(meta(block)))
            return meshes.computeIfAbsent(key) { k ->
                val to = floatArrayOf(1f, k.height, 1f)
                val box = renderBlocksBox(floatArrayOf(0f, 0f, 0f), to, k.texture, k.rgb, Faces.ALL)
                // renderFaceYPos(elevator, 0, 0.001, 0, overlayIcon)
                val top =
                    renderBlocksBox(
                        floatArrayOf(0f, k.height + 0.001f, 0f),
                        floatArrayOf(1f, k.height + 0.001f, 1f),
                        k.overlay,
                        MeshQuad.WHITE,
                        listOf(Direction.UP),
                    )
                Mesh(box + top)
            }
        }
    }

    /**
     * The [faces] of a box drawn with 1.7 `RenderBlocks.renderFace*`: each face shows the part of the sprite its
     * bounds cover (x/z on top and bottom, x or z by 1 - y on the sides; the bottom in 1.7's orientation).
     */
    internal fun renderBlocksBox(
        from: FloatArray,
        to: FloatArray,
        texture: Key,
        rgb: Int,
        faces: Collection<Direction>,
        glow: Boolean = false,
    ): List<MeshQuad> =
        faces.map { face ->
            val (x1, y1, z1) = from
            val (x2, y2, z2) = to
            val tex =
                when (face) {
                    Direction.DOWN, Direction.UP -> FaceTexture(texture, x1, z1, x2, z2, rgb = rgb, glow = glow)
                    Direction.NORTH, Direction.SOUTH -> FaceTexture(texture, x1, 1 - y2, x2, 1 - y1, rgb = rgb, glow = glow)
                    Direction.WEST, Direction.EAST -> FaceTexture(texture, z1, 1 - y2, z2, 1 - y1, rgb = rgb, glow = glow)
                }
            Faces.face(face, from, to, tex, legacyBottom = true)
        }

    val ALL: List<AddonTesr<*>> = listOf(BeamFormer, MultiEssentiaJar, WaterCandle, PlayerDoll, PigmeeFumo, EnderElevator)
}
