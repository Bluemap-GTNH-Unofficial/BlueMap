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
package de.bluecolored.bluemap.gtnh.render.mods.ae2

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant
import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.gtnh.render.mods.FaceTexture
import de.bluecolored.bluemap.gtnh.render.mods.Faces
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.MeshBuilder
import de.bluecolored.bluemap.gtnh.render.mods.MeshQuad
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModBlockRenderer
import de.bluecolored.bluemap.gtnh.render.mods.ModelFaces
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import java.util.concurrent.ConcurrentHashMap

/**
 * `gtnh:ae2_cable_bus`: AE2 cable buses from `TileCableBus` NBT: the centre cable (glass, covered, smart, dense,
 * by colour) with arms towards connected neighbours, and the common parts (terminals and monitors, buses,
 * interfaces, planes, P2P tunnels, ...) as AE2's own boxes with their AE2 textures. AE2 does not save connections,
 * so a side connects to a neighbouring bus with a compatible cable and no part in between, or to an AE2 network
 * block; a side with a part gets the cable stub to it. Facades are 2 px plates with the covered block's textures;
 * addon parts are drawn as a plain plate.
 */
class Ae2CableBusRenderer(
    resourcePack: ResourcePack,
    textureGallery: TextureGallery,
    renderSettings: RenderSettings,
) : ModBlockRenderer(resourcePack, textureGallery, renderSettings) {
    private val meshes = ConcurrentHashMap<List<Any?>, Mesh>()
    private val modelFaces = ModelFaces(resourcePack)

    override fun render(
        assets: ModAssets,
        block: BlockNeighborhood,
        variant: Variant,
        blockModel: TileModelView,
        color: Color,
    ): Boolean {
        val te = block.blockEntity as? Ae2CableBusTileEntity ?: return false
        val cable = te.cable
        val parts = Array(6) { te.part(it) }
        var connections = 0
        if (cable != null) {
            for (side in 0 until 6) {
                if (parts[side] != null) continue
                if (connects(block, cable, side)) connections = connections or (1 shl side)
            }
        }
        val facades = Array(6) { side -> te.facade(side)?.let { (name, meta) -> ModelFaces.legacyState(name, meta) } }
        if (cable == null && parts.all { it == null } && facades.all { it == null }) return false
        val key = listOf(cable, connections, *parts, *facades)
        val mesh = meshes[key] ?: (build(assets, cable, connections, parts) + facadeMesh(facades)).also { meshes[key] = it }
        emit(mesh, block, blockModel, color)
        return true
    }

    private fun connects(
        block: BlockNeighborhood,
        cable: Ae2Cable,
        side: Int,
    ): Boolean {
        val d = FORGE_DIRECTIONS[side].toVector()
        val neighbor = block.getNeighborBlock(d.x, d.y, d.z)
        val other = neighbor.blockEntity as? Ae2CableBusTileEntity
        if (other != null) {
            val otherCable = other.cable ?: return false
            return other.def(OPPOSITE[side]) == null && cable.connectsTo(otherCable)
        }
        val id = neighbor.blockState.id
        return id.namespace == AE2 && id.value.removePrefix("tile.") in GRID_BLOCKS
    }

    /** Facades: a 2 px plate over the whole side showing the covered block's textures (`FacadePart`). */
    private fun facadeMesh(facades: Array<de.bluecolored.bluemap.core.world.BlockState?>): Mesh {
        val quads = ArrayList<MeshQuad>()
        for (side in 0 until 6) {
            val state = facades[side] ?: continue
            val d = FORGE_DIRECTIONS[side].toVector()

            fun range(axis: Int): Pair<Float, Float> =
                when (axis) {
                    1 -> 16f - FACADE_THICKNESS to 16f
                    -1 -> 0f to FACADE_THICKNESS
                    else -> 0f to 16f
                }
            val (x1, x2) = range(d.x)
            val (y1, y2) = range(d.y)
            val (z1, z2) = range(d.z)
            val from = floatArrayOf(x1 / 16f, y1 / 16f, z1 / 16f)
            val to = floatArrayOf(x2 / 16f, y2 / 16f, z2 / 16f)
            for (face in Direction.entries) {
                val look = modelFaces.face(state, face) ?: continue
                val quad = Faces.face(face, from, to, MeshBuilder.autoUv(face, x1, y1, z1, x2, y2, z2, look.texture))
                // biome-tinted blocks (grass, leaves) keep their tint
                quads.add(if (look.tint) MeshQuad(quad.positions, quad.uvs, quad.texture, tint = true) else quad)
            }
        }
        return Mesh(quads)
    }

    private fun build(
        assets: ModAssets,
        cable: Ae2Cable?,
        connections: Int,
        parts: Array<Ae2Part?>,
    ): Mesh {
        val builder = MeshBuilder()

        fun tex(name: String): Key = assets.texture("appliedenergistics2:textures/blocks/$name.png") ?: MISSING
        if (cable != null) {
            val texture =
                Ae2Cable.textureNames(cable.type, cable.color).firstNotNullOfOrNull { assets.texture(raw(it)) } ?: tex(cable.type.fluix)
            val c = cable.type.core
            val a = cable.type.arm
            builder.box(c, c, c, 16 - c, 16 - c, 16 - c, texture)
            for (side in 0 until 6) {
                val part = parts[side]
                when {
                    connections and (1 shl side) != 0 -> {
                        builder.add(local(side, box(8 - a, 8 - a, 16 - c, 8 + a, 8 + a, 16f, texture)))
                    }

                    part != null -> {
                        val s = if (cable.type.arm >= 4f) 3f else 2f
                        val end = 16 - part.kind.stub
                        if (end > 16 - c) builder.add(local(side, box(8 - s, 8 - s, 16 - c, 8 + s, 8 + s, end, texture)))
                    }
                }
            }
        }
        for (side in 0 until 6) {
            val part = parts[side] ?: continue
            builder.add(local(side, partMesh(assets, part, cable?.color ?: Ae2Cable.FLUIX, ::tex)))
        }
        return builder.build()
    }

    /** A part on the south side in AE2's part frame (z = 16 is the bus side). */
    private fun partMesh(
        assets: ModAssets,
        part: Ae2Part,
        color: Int,
        tex: (String) -> Key,
    ): Mesh {
        val icon = Ae2Part.ICONS[part.damage]?.let { assets.texture(raw(it)) } ?: tex("PartMonitorBack")
        val b = MeshBuilder()

        fun box(
            x1: Float,
            y1: Float,
            z1: Float,
            x2: Float,
            y2: Float,
            z2: Float,
            side: Key,
            back: Key,
            front: Key = icon,
        ) {
            b.box(x1, y1, z1, x2, y2, z2) { face ->
                val t =
                    when (face) {
                        Direction.SOUTH -> front
                        Direction.NORTH -> back
                        else -> side
                    }
                MeshBuilder.autoUv(face, x1, y1, z1, x2, y2, z2, t)
            }
        }
        val sides = tex("PartMonitorSides")
        val back = tex("PartMonitorBack")
        val status = tex("PartMonitorSidesStatus")
        when (part.kind) {
            Ae2Part.Kind.DISPLAY -> {
                box(2f, 2f, 14f, 14f, 14f, 16f, sides, back)
                Ae2Part.DISPLAY_SCREENS[part.damage]?.let { stem ->
                    val (dark, medium, white) = AE_COLORS.getOrElse(color) { AE_COLORS[Ae2Cable.FLUIX] }
                    var layer = 1
                    for ((suffix, rgb) in listOf("_Bright" to white, "_Dark" to medium, "_Colored" to dark)) {
                        val overlay = assets.texture(raw(stem + suffix)) ?: continue
                        val z = (16f + layer++ * LAYER_LIFT_PIXELS) / 16f
                        b.add(
                            Faces.face(
                                Direction.SOUTH,
                                floatArrayOf(2 / 16f, 2 / 16f, 0f),
                                floatArrayOf(14 / 16f, 14 / 16f, z),
                                FaceTexture(
                                    overlay,
                                    2 / 16f,
                                    2 / 16f,
                                    14 / 16f,
                                    14 / 16f,
                                    rgb = rgb,
                                ),
                            ),
                        )
                    }
                }
                box(4f, 4f, 13f, 12f, 12f, 14f, status, back)
            }

            Ae2Part.Kind.IMPORT_BUS -> {
                val s = tex("PartImportSides")
                box(4f, 4f, 14f, 12f, 12f, 16f, s, back)
                box(5f, 5f, 13f, 11f, 11f, 14f, s, back)
                box(6f, 6f, 12f, 10f, 10f, 13f, s, back)
                box(6f, 6f, 11f, 10f, 10f, 12f, status, back)
            }

            Ae2Part.Kind.EXPORT_BUS -> {
                val s = tex("PartExportSides")
                box(4f, 4f, 12f, 12f, 12f, 14f, s, back)
                box(5f, 5f, 14f, 11f, 11f, 15f, s, back)
                box(6f, 6f, 15f, 10f, 10f, 16f, s, back)
                box(6f, 6f, 11f, 10f, 10f, 12f, status, back)
            }

            Ae2Part.Kind.STORAGE_BUS -> {
                val s = tex("PartStorageSides")
                val sb = tex("PartStorageBack")
                box(3f, 3f, 15f, 13f, 13f, 16f, s, sb)
                box(2f, 2f, 14f, 14f, 14f, 15f, s, sb)
                box(5f, 5f, 12f, 11f, 11f, 13f, s, sb)
                box(5f, 5f, 13f, 11f, 11f, 14f, status, back)
            }

            Ae2Part.Kind.INTERFACE -> {
                box(2f, 2f, 14f, 14f, 14f, 16f, sides, back)
                box(5f, 5f, 12f, 11f, 11f, 13f, sides, back)
                box(5f, 5f, 13f, 11f, 11f, 14f, status, back)
            }

            Ae2Part.Kind.PLANE -> {
                box(1f, 1f, 15f, 15f, 15f, 16f, tex("PartPlaneSides"), tex("PartTransitionPlaneBack"))
                box(5f, 5f, 14f, 11f, 11f, 15f, status, tex("PartTransitionPlaneBack"))
            }

            Ae2Part.Kind.LEVEL_EMITTER -> {
                box(7f, 7f, 11f, 9f, 9f, 16f, icon, icon)
            }

            Ae2Part.Kind.TOGGLE_BUS -> {
                box(6f, 6f, 14f, 10f, 10f, 16f, icon, icon)
                box(6f, 6f, 11f, 10f, 10f, 13f, icon, icon)
                box(6f, 6f, 13f, 10f, 10f, 14f, status, back)
            }

            Ae2Part.Kind.QUARTZ_FIBER -> {
                box(6f, 6f, 10f, 10f, 10f, 16f, icon, icon)
            }

            Ae2Part.Kind.CABLE_ANCHOR -> {
                box(7f, 7f, 10f, 9f, 9f, 16f, icon, icon)
            }

            Ae2Part.Kind.P2P -> {
                val s = tex("PartTunnelSides")
                val pb = tex("BlockP2PTunnel2")
                box(2f, 2f, 14f, 14f, 14f, 16f, s, pb)
                box(3f, 3f, 13f, 13f, 13f, 14f, s, pb)
                val inner = tex("BlockP2PTunnel3")
                box(6f, 5f, 12f, 10f, 11f, 13f, inner, inner, inner)
                box(5f, 6f, 12f, 11f, 10f, 13f, inner, inner, inner)
            }

            Ae2Part.Kind.UNKNOWN -> {
                box(2f, 2f, 14f, 14f, 14f, 16f, sides, back, back)
            }
        }
        return b.build()
    }

    private fun box(
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
        texture: Key,
    ): Mesh = MeshBuilder().box(x1, y1, z1, x2, y2, z2, texture).build()

    /** Turns a mesh built for the south side to ForgeDirection [side]. */
    private fun local(
        side: Int,
        mesh: Mesh,
    ): Mesh {
        if (side == SOUTH) return mesh
        val c = Transform.translate(0.5f, 0.5f, 0.5f)
        val back = Transform.translate(-0.5f, -0.5f, -0.5f)
        return mesh.transformed(Transform.gl(c, SIDE_ROTATIONS[side], back))
    }

    companion object {
        const val AE2 = "appliedenergistics2"
        private const val SOUTH = 3
        private const val LAYER_LIFT_PIXELS = 1f / 32f
        private const val FACADE_THICKNESS = 2f
        private val MISSING = Key.bluemap("block/missing")

        fun raw(name: String) = "appliedenergistics2:textures/blocks/$name.png"

        /** ForgeDirection ordinal -> direction. */
        val FORGE_DIRECTIONS = arrayOf(Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
        private val OPPOSITE = intArrayOf(1, 0, 3, 2, 5, 4)

        /** Rotation taking the part frame's +z (south) to the side. */
        private val SIDE_ROTATIONS =
            arrayOf(
                Transform.rotate(90f, 1f, 0f, 0f),
                Transform.rotate(-90f, 1f, 0f, 0f),
                Transform.rotate(180f, 0f, 1f, 0f),
                Transform.IDENTITY,
                Transform.rotate(-90f, 0f, 1f, 0f),
                Transform.rotate(90f, 0f, 1f, 0f),
            )

        /**
         * `AEColor` (dark, medium, white) variants by ordinal; 16 = Transparent (fluix). Values from AE2's `AEColor`
         * (MIT, Copyright (c) 2013 AlgorithmX2).
         */
        private val AE_COLORS: List<Triple<Int, Int, Int>> =
            listOf(
                Triple(0xBEBEBE, 0xDBDBDB, 0xFAFAFA),
                Triple(0xF99739, 0xFAAE44, 0xF4DEC3),
                Triple(0x821E82, 0xB82AB8, 0xC598C8),
                Triple(0x628DCB, 0x82ACE7, 0xD8F6FF),
                Triple(0xFFF7AA, 0xF8FF4A, 0xFFFFE8),
                Triple(0x7CFF4A, 0xBBFF51, 0xE7F7D7),
                Triple(0xDC8DB5, 0xF8B5D7, 0xF7DEEB),
                Triple(0x7C7C7C, 0xA0A0A0, 0xC9C9C9),
                Triple(0x9D9D9D, 0xCDCDCD, 0xEFEFEF),
                Triple(0x2F9BA5, 0x51AAC6, 0xAEDDF4),
                Triple(0x8230B2, 0xA453CE, 0xC7A3CC),
                Triple(0x2D29A0, 0x514AFF, 0xDDE6FF),
                Triple(0x724E35, 0xB7967F, 0xE0D2C8),
                Triple(0x45A021, 0x60E32E, 0xE3F2E3),
                Triple(0xA50029, 0xFF003C, 0xFFE6ED),
                Triple(0x2B2B2B, 0x565656, 0x848484),
                Triple(0x1B2344, 0x895CA8, 0xD7BBEC),
            )

        /** AE2 blocks that are grid nodes (cables connect to them), by `tile.<name>` without the prefix. */
        private val GRID_BLOCKS: Set<String> =
            listOf(
                "BlockController",
                "BlockCreativeEnergyController",
                "BlockDrive",
                "BlockChest",
                "BlockInterface",
                "BlockIOPort",
                "BlockEnergyAcceptor",
                "BlockEnergyCell",
                "BlockDenseEnergyCell",
                "BlockCreativeEnergyCell",
                "BlockCraftingUnit",
                "BlockAdvancedCraftingUnit",
                "BlockCraftingStorage",
                "BlockAdvancedCraftingStorage",
                "BlockSingularityCraftingStorage",
                "BlockCraftingMonitor",
                "BlockMolecularAssembler",
                "BlockQuantumRing",
                "BlockQuantumLinkChamber",
                "BlockSpatialPylon",
                "BlockSpatialIOPort",
                "BlockSecurity",
                "BlockWireless",
                "BlockQuartzGrowthAccelerator",
                "BlockStorageReshuffle",
                "BlockPatternOptimizationMatrix",
                "BlockSpatialNetworkRelay",
                "BlockAdvancedInscriber",
            ).mapTo(HashSet(), ::normalize)

        /** The exporter's key normalization for plain ASCII names: an upper-case letter becomes `~` + lower case. */
        private fun normalize(name: String): String =
            buildString {
                for (ch in name) if (ch.isUpperCase()) append('~').append(ch.lowercaseChar()) else append(ch)
            }

        val RAW_TEXTURES: Set<String> by lazy {
            val names = HashSet<String>()
            for (type in Ae2Cable.Type.entries) for (color in 0..Ae2Cable.FLUIX) names.addAll(Ae2Cable.textureNames(type, color))
            names.addAll(Ae2Part.ICONS.values)
            for (stem in Ae2Part.DISPLAY_SCREENS.values.filterNotNull()) {
                listOf("_Bright", "_Dark", "_Colored").forEach {
                    names.add(stem + it)
                }
            }
            names.addAll(
                listOf(
                    "PartMonitorSides",
                    "PartMonitorBack",
                    "PartMonitorSidesStatus",
                    "PartImportSides",
                    "PartExportSides",
                    "PartStorageSides",
                    "PartStorageBack",
                    "PartPlaneSides",
                    "PartTransitionPlaneBack",
                    "PartTunnelSides",
                    "BlockP2PTunnel2",
                    "BlockP2PTunnel3",
                ),
            )
            names.mapTo(HashSet(), ::raw)
        }
    }
}
