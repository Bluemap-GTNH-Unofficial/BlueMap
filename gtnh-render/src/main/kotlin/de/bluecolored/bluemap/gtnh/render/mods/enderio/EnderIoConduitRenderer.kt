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
package de.bluecolored.bluemap.gtnh.render.mods.enderio

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
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.MeshBuilder
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModBlockRenderer
import de.bluecolored.bluemap.gtnh.render.mods.ModelFaces
import de.bluecolored.bluenbt.NBTName
import java.util.concurrent.ConcurrentHashMap

class EioConduitBody {
    /** ForgeDirection ordinals of conduit-to-conduit connections. */
    @field:NBTName("connections")
    var connections: IntArray = IntArray(0)
        private set

    /** ForgeDirection ordinals of connections to blocks (machines, inventories). */
    @field:NBTName("externalConnections")
    var externalConnections: IntArray = IntArray(0)
        private set

    /** Power conduits: 0 basic, 1 enhanced, 2 ender. */
    @field:NBTName("subtype")
    var subtype: Int = 0
        private set
}

class EioConduitEntry {
    @field:NBTName("conduitType")
    var conduitType: String = ""
        private set

    @field:NBTName("conduit")
    var conduit: EioConduitBody? = null
        private set
}

/** EnderIO's `TileConduitBundle` (`writeCustomNBT`): the conduits of the bundle with their connections. */
class EioConduitBundleTileEntity : MCABlockEntity() {
    @field:NBTName("conduits")
    var conduits: List<EioConduitEntry> = emptyList()
        private set

    /** The facade block's registry name (`"null"` without a facade) and meta. */
    @field:NBTName("facadeId")
    var facadeId: String = "null"
        private set

    @field:NBTName("facadeMeta")
    var facadeMeta: Int = 0
        private set

    /** The facade's block (`modid:name`, meta), if the bundle has one. */
    val facade: Pair<String, Int>? get() = facadeId.takeIf { it.isNotEmpty() && it != "null" }?.let { it to facadeMeta }

    companion object {
        val BLOCK_ENTITY_TYPE: BlockEntityType =
            BlockEntityType.Impl(Key.minecraft("blockConduitBundleTileEntity"), EioConduitBundleTileEntity::class.java)
    }
}

/**
 * `gtnh:enderio_conduit`: EnderIO conduit bundles from the tile entity's conduit list: per conduit a core and an arm
 * towards every connection (conduit and external), in EnderIO's geometry (`ConduitGeometryUtil` at the default
 * conduit scale 0.6, `Offsets` per conduit type and axis when the bundle holds several types), with the conduit's
 * own core/body textures. A bundle with a facade is drawn as the facade's block. Connection plates are not drawn.
 */
class EnderIoConduitRenderer(
    resourcePack: ResourcePack,
    textureGallery: TextureGallery,
    renderSettings: RenderSettings,
) : ModBlockRenderer(resourcePack, textureGallery, renderSettings) {
    private val meshes = ConcurrentHashMap<List<Any>, Mesh>()

    override fun render(
        assets: ModAssets,
        block: BlockNeighborhood,
        variant: Variant,
        blockModel: TileModelView,
        color: Color,
    ): Boolean {
        val te = block.blockEntity as? EioConduitBundleTileEntity ?: return false
        // a facade hides the conduits: the bundle looks like the facade's block
        te.facade?.let { (name, meta) ->
            val state = ModelFaces.legacyState(name, meta)
            if (state != null && renderState(state, block, blockModel, color)) return true
        }
        val conduits =
            te.conduits.mapNotNull { entry ->
                val type = Kind.of(entry.conduitType) ?: return@mapNotNull null
                val body = entry.conduit ?: EioConduitBody()
                var mask = 0
                for (d in body.connections) if (d in 0..5) mask = mask or (1 shl d)
                for (d in body.externalConnections) if (d in 0..5) mask = mask or (1 shl d)
                Triple(type, body.subtype, mask)
            }
        if (conduits.isEmpty()) return false
        val key: List<Any> = conduits
        val mesh = meshes[key] ?: build(assets, conduits).also { meshes[key] = it }
        emit(mesh, block, blockModel, color)
        return true
    }

    private fun build(
        assets: ModAssets,
        conduits: List<Triple<Kind, Int, Int>>,
    ): Mesh {
        val builder = MeshBuilder()
        val single = conduits.size == 1
        for ((kind, subtype, mask) in conduits) {
            val core = kind.texture(assets, subtype, core = true) ?: continue
            val body = kind.texture(assets, subtype, core = false) ?: core
            val (cx, cy, cz) = if (single) ZERO else kind.offsets[0]
            builder.box(LO + cx, LO + cy, LO + cz, HI + cx, HI + cy, HI + cz, core)
            for (side in 0 until 6) {
                if (mask and (1 shl side) == 0) continue
                val (ox, oy, oz) = if (single) ZERO else kind.offsets[AXIS[side]]
                val from = floatArrayOf(LO + ox, LO + oy, LO + oz)
                val to = floatArrayOf(HI + ox, HI + oy, HI + oz)
                val axis = AXIS_INDEX[side]
                if (POSITIVE[side]) {
                    from[axis] = HI + listOf(ox, oy, oz)[axis]
                    to[axis] = 16f
                } else {
                    from[axis] = 0f
                    to[axis] = LO + listOf(ox, oy, oz)[axis]
                }
                builder.box(from[0], from[1], from[2], to[0], to[1], to[2], body)
            }
        }
        return builder.build()
    }

    /** Conduit types: texture stems and `Offsets` (offset unit vectors scaled by the conduit width) per axis. */
    enum class Kind(
        private val stems: List<String>,
        offsets: List<Triple<Int, Int, Int>>,
    ) {
        ITEM(listOf("itemConduit"), listOf(EAST, SOUTH, EAST, EAST)),
        LIQUID(listOf("liquidConduit"), listOf(WEST, NORTH, WEST, WEST)),
        LIQUID_ADVANCED(listOf("liquidConduitAdvanced", "liquidConduit"), listOf(WEST, NORTH, WEST, WEST)),
        LIQUID_ENDER(listOf("liquidConduitEnder", "liquidConduit"), listOf(WEST, NORTH, WEST, WEST)),
        POWER(listOf("powerConduit"), listOf(DOWN, DOWN, SOUTH, DOWN)),
        REDSTONE(listOf("redstoneInsulatedConduit", "redstoneConduit"), listOf(UP, UP, NORTH, UP)),
        ME(listOf("meConduit"), listOf(SOUTH_UP, SOUTH_UP, NORTH_EAST, EAST_UP)),
        GAS(listOf("gasConduit"), listOf(NORTH_UP, NORTH_UP, NORTH_WEST, WEST_UP)),
        OC(listOf("ocConduit"), listOf(NORTH_DOWN, NORTH_DOWN, SOUTH_WEST, WEST_DOWN)),
        ;

        /** Offsets in pixels for axis NONE, X, Y, Z. */
        val offsets: List<Triple<Float, Float, Float>> = offsets.map { (x, y, z) -> Triple(x * WIDTH, y * WIDTH, z * WIDTH) }

        fun texture(
            assets: ModAssets,
            subtype: Int,
            core: Boolean,
        ): Key? {
            val suffix = if (this == POWER) POWER_SUFFIXES.getOrElse(subtype) { "" } else ""
            for (stem in stems) {
                val name = if (core) "${stem}Core$suffix" else "$stem$suffix"
                assets.texture(raw(name))?.let { return it }
                if (this == LIQUID_ADVANCED && core) assets.texture(raw("liquidConduitCoreAdvanced"))?.let { return it }
                if (this == LIQUID_ENDER && core) assets.texture(raw("liquidConduitCoreEnder"))?.let { return it }
            }
            return null
        }

        fun rawTextures(): List<String> {
            val suffixes = if (this == POWER) POWER_SUFFIXES else listOf("")
            return stems.flatMap { stem -> suffixes.flatMap { listOf(raw("$stem$it"), raw("${stem}Core$it")) } } +
                listOf(raw("liquidConduitCoreAdvanced"), raw("liquidConduitCoreEnder"))
        }

        companion object {
            /** By the conduit's class (`conduitType`, e.g. `crazypants.enderio.conduit.item.ItemConduit`). */
            fun of(conduitType: String): Kind? =
                when (conduitType.substringAfterLast('.')) {
                    "ItemConduit" -> ITEM
                    "LiquidConduit" -> LIQUID
                    "AdvancedLiquidConduit" -> LIQUID_ADVANCED
                    "EnderLiquidConduit" -> LIQUID_ENDER
                    "PowerConduit" -> POWER
                    "InsulatedRedstoneConduit", "RedstoneConduit", "RedstoneSwitch" -> REDSTONE
                    "MEConduit" -> ME
                    "GasConduit" -> GAS
                    "OCConduit" -> OC
                    else -> null
                }
        }
    }

    companion object {
        /** `ConduitGeometryUtil.setupBounds(0.6)`: width 0.075 + 0.175 * 0.6 blocks, in pixels. */
        const val WIDTH = (0.075f + 0.175f * 0.6f) * 16f
        private const val LO = 8f - WIDTH / 2
        private const val HI = 8f + WIDTH / 2
        private val ZERO = Triple(0f, 0f, 0f)

        private val UP = Triple(0, 1, 0)
        private val DOWN = Triple(0, -1, 0)
        private val NORTH = Triple(0, 0, -1)
        private val SOUTH = Triple(0, 0, 1)
        private val WEST = Triple(-1, 0, 0)
        private val EAST = Triple(1, 0, 0)
        private val SOUTH_UP = Triple(0, 1, 1)
        private val NORTH_UP = Triple(0, 1, -1)
        private val EAST_UP = Triple(1, 1, 0)
        private val WEST_UP = Triple(-1, 1, 0)
        private val NORTH_DOWN = Triple(0, -1, -1)
        private val WEST_DOWN = Triple(-1, -1, 0)
        private val NORTH_EAST = Triple(1, 0, -1)
        private val NORTH_WEST = Triple(-1, 0, -1)
        private val SOUTH_WEST = Triple(-1, 0, 1)

        private val POWER_SUFFIXES = listOf("", "Enhanced", "Ender")

        /** ForgeDirection ordinal -> `Offsets.Axis` index (1 X, 2 Y, 3 Z), coordinate index, positive side. */
        private val AXIS = intArrayOf(2, 2, 3, 3, 1, 1)
        private val AXIS_INDEX = intArrayOf(1, 1, 2, 2, 0, 0)
        private val POSITIVE = booleanArrayOf(false, true, false, true, false, true)

        fun raw(name: String) = "enderio:textures/blocks/$name.png"

        val RAW_TEXTURES: Set<String> by lazy { Kind.entries.flatMapTo(HashSet()) { it.rawTextures() } }
    }
}
