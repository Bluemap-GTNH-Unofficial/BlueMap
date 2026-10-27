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
package de.bluecolored.bluemap.gtnh.legacy

import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.gtnh.legacy.VanillaBlockShapes.faceShape
import java.util.concurrent.ConcurrentHashMap

/**
 * The parts of the 1.13 world upgrade that depend on neighbours or block entities, as [LegacyBlockFixer]s on
 * flattened vanilla states. Semantics follow Mojang's 1.13.2 upgrade:
 * - in-chunk fixes of `ChunkPalettedStorageFix` (PaperMC DataConverter `ConverterFlattenChunk`): snowy grass /
 *   podzol / mycelium, door halves, upper double-plant halves, carved pumpkins on dirt, flower pots, skulls,
 *   note blocks;
 * - the `UpgradeData` shape updates of the blocks it marks (`updatePostPlacement` of fences, panes, walls, stairs,
 *   fence gates, redstone wire, repeaters, tripwire, vines, fire, beds and the chest pairing fixer).
 *
 * Intentional differences (1.13 is inconsistent there, 1.7 shows otherwise):
 * - grass / podzol / mycelium are also snowy below snow blocks (1.7 renders it; 1.13's own shape update does it too,
 *   only the chunk converter checks snow layers alone);
 * - grown pumpkin / melon stems attach to an adjacent fruit (1.7 bends them; 1.13's upgrade only does it on
 *   chunk edges);
 * - flower pots keep their content (1.7 stores numeric item ids, which 1.13.2's converter ignores; DataConverter maps
 *   them like here).
 * See `FlatteningComparison.Intentional` for the full list checked against the 1.13.2 oracle.
 */
internal class VanillaFixers(
    private val registry: FmlBlockRegistry,
) {
    private val byBlock: Map<String, LegacyBlockFixer> = buildFixers()

    fun fixer(state: BlockState): LegacyBlockFixer? {
        val id = state.id
        if (id.namespace != Key.MINECRAFT_NAMESPACE) return null
        return byBlock[id.value]
    }

    private fun buildFixers(): Map<String, LegacyBlockFixer> {
        val map = HashMap<String, LegacyBlockFixer>()

        fun put(
            fixer: LegacyBlockFixer,
            vararg names: String,
        ) {
            for (name in names) map[name] = fixer
        }

        val colors =
            listOf(
                "white",
                "orange",
                "magenta",
                "light_blue",
                "yellow",
                "lime",
                "pink",
                "gray",
                "light_gray",
                "cyan",
                "purple",
                "blue",
                "brown",
                "green",
                "red",
                "black",
            )
        val stairs =
            listOf(
                "oak",
                "cobblestone",
                "brick",
                "stone_brick",
                "nether_brick",
                "sandstone",
                "spruce",
                "birch",
                "jungle",
                "quartz",
                "acacia",
                "dark_oak",
            ).map { "${it}_stairs" }

        put(::snowy, "grass_block", "podzol", "mycelium")
        put(::door, "oak_door", "iron_door")
        put(::doublePlant, "sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony")
        put(::pumpkin, "carved_pumpkin")
        put(::flowerPot, "flower_pot")
        put(::skull, "skeleton_skull", "skeleton_wall_skull")
        put(::noteBlock, "note_block")
        put(::bed, "red_bed")
        put(::fence, "oak_fence", "nether_brick_fence")
        put(::pane, "iron_bars", "glass_pane", *colors.map { "${it}_stained_glass_pane" }.toTypedArray())
        put(::wall, "cobblestone_wall", "mossy_cobblestone_wall")
        put(::stairs, *stairs.toTypedArray())
        put(::fenceGate, "oak_fence_gate")
        put(::redstoneWire, "redstone_wire")
        put(::repeater, "repeater")
        put(::tripwire, "tripwire")
        put(::vine, "vine")
        put(::fire, "fire")
        put(::chest, "chest", "trapped_chest")
        put(::stem, "pumpkin_stem", "melon_stem")
        return map
    }

    // ---- in-chunk fixes of the chunk converter (ChunkPalettedStorageFix) ----

    private fun snowy(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val above = blocks.baseState(x, y + 1, z).id
        val snowy = above == SNOW || above == SNOW_BLOCK
        return with(state, "snowy", snowy.toString())
    }

    /** Lower halves provide facing/open, upper halves hinge/powered; a stack of lower halves pairs bottom-up. */
    private fun door(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val below = blocks.baseState(x, y - 1, z)
        if (state.properties["half"] == "upper") {
            if (below.id == state.id && below.properties["half"] == "lower" && !claimedAsUpper(state.id, x, y - 1, z, blocks)) {
                return doorState(state.id, below, "upper", state)
            }
            return state
        }
        // a lower half directly above an unclaimed lower half of the same door becomes its upper half
        if (claimedAsUpper(state.id, x, y, z, blocks)) return doorState(state.id, below, "upper", state)
        val above = blocks.baseState(x, y + 1, z)
        if (above.id == state.id) return doorState(state.id, state, "lower", above)
        return state
    }

    /** True if the lower door half at (x,y,z) is turned into the upper half of the lower half below it. */
    private fun claimedAsUpper(
        id: Key,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
    ): Boolean {
        var lowerHalvesBelow = 0
        var cy = y - 1
        while (cy >= 0) {
            val s = blocks.baseState(x, cy, z)
            if (s.id != id || s.properties["half"] != "lower") break
            lowerHalvesBelow++
            cy--
        }
        return lowerHalvesBelow % 2 == 1
    }

    private fun doorState(
        id: Key,
        lower: BlockState,
        half: String,
        upper: BlockState,
    ): BlockState =
        intern(
            BlockState(
                id,
                mapOf(
                    "facing" to lower.properties.getValue("facing"),
                    "half" to half,
                    "hinge" to upper.properties.getValue("hinge"),
                    "open" to lower.properties.getValue("open"),
                    "powered" to upper.properties.getValue("powered"),
                ),
            ),
        )

    private fun doublePlant(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        if (state.properties["half"] != "upper") return state
        val below = blocks.state(x, y - 1, z).id
        if (below.namespace != Key.MINECRAFT_NAMESPACE || below.value !in DOUBLE_PLANTS) return state
        return intern(BlockState(below, mapOf("half" to "upper")))
    }

    private fun pumpkin(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val below = blocks.baseState(x, y - 1, z).id
        return if (below == GRASS_BLOCK || below == DIRT) PUMPKIN else state
    }

    private fun flowerPot(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val pot = blockEntity as? LegacyFlowerPotBlockEntity ?: return state
        val item =
            when (val raw = pot.item) {
                is Int -> registry.itemName(raw)
                is String -> raw
                else -> null
            } ?: return state
        return POTTED["$item:${pot.data}"] ?: state
    }

    private fun skull(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        // without its block entity a 1.7 skull renders nothing, and the 1.13 upgrade drops it
        val skull = blockEntity as? LegacySkullBlockEntity ?: return BlockState.AIR
        val wallFacing = if (state.id.value.contains("_wall_")) state.properties["facing"] else null
        val type = SKULL_TYPES.getOrNull(skull.skullType)
        if (type == null || (wallFacing == null && skull.rotation !in 0..15)) return DEFAULT_SKULL
        return intern(
            if (wallFacing != null) {
                BlockState(Key.minecraft(type.wall), mapOf("facing" to wallFacing))
            } else {
                BlockState(Key.minecraft(type.floor), mapOf("rotation" to skull.rotation.toString()))
            },
        )
    }

    private fun noteBlock(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val note = blockEntity as? LegacyNoteBlockEntity ?: return state
        return with(state, "note", note.note.coerceIn(0, 24).toString(), "powered", "false")
    }

    // ---- UpgradeData shape updates ----

    /**
     * A bed half without its other half is removed. Both halves copy `occupied` from each other in 1.13's position
     * order (ascending z, then x), so both end up with the original value of the half that comes later.
     */
    private fun bed(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val facing = Face.byId(state.properties["facing"]) ?: return state
        val foot = state.properties["part"] == "foot"
        val toOther = if (foot) facing else facing.opposite
        val other = blocks.baseState(x + toOther.dx, y, z + toOther.dz)
        if (other.id != state.id || other.properties["part"] == state.properties["part"]) return BlockState.AIR
        val otherIsLater = toOther.dz > 0 || (toOther.dz == 0 && toOther.dx > 0)
        return if (otherIsLater) with(state, "occupied", other.properties["occupied"] ?: "false") else state
    }

    private fun fence(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState =
        connect(state, x, y, z, blocks) { neighbour, shape ->
            (shape == FaceShape.SOLID && !VanillaBlockShapes.isFenceAttachException(neighbour)) ||
                (
                    shape == FaceShape.MIDDLE_POLE &&
                        (
                            VanillaBlockShapes.isFenceGate(neighbour) ||
                                VanillaBlockShapes.isWooden(neighbour) == VanillaBlockShapes.isWooden(state)
                        )
                )
        }

    private fun pane(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState =
        connect(state, x, y, z, blocks) { neighbour, shape ->
            (shape == FaceShape.SOLID && !VanillaBlockShapes.isPaneAttachException(neighbour)) || shape == FaceShape.MIDDLE_POLE_THIN
        }

    private fun wall(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val connected =
            connect(state, x, y, z, blocks) { neighbour, shape ->
                (shape == FaceShape.SOLID && !VanillaBlockShapes.isFenceAttachException(neighbour)) ||
                    shape == FaceShape.MIDDLE_POLE_THICK ||
                    (shape == FaceShape.MIDDLE_POLE && VanillaBlockShapes.isFenceGate(neighbour))
            }
        val p = connected.properties
        val n = p["north"] == "true"
        val s = p["south"] == "true"
        val w = p["west"] == "true"
        val e = p["east"] == "true"
        val straight = (n && s && !w && !e) || (!n && !s && w && e)
        val up = !straight || !blocks.baseState(x, y + 1, z).isAir
        return with(connected, "up", up.toString())
    }

    /** Sets `north`/`south`/`west`/`east` by [attaches] (neighbour state, shape of its face towards us). */
    private inline fun connect(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        attaches: (BlockState, FaceShape) -> Boolean,
    ): BlockState {
        val props = HashMap(state.properties)
        for (face in Face.HORIZONTAL) {
            val nx = x + face.dx
            val nz = z + face.dz
            var neighbour = blocks.baseState(nx, y, nz)
            if (VanillaBlockShapes.isStairs(neighbour)) neighbour = blocks.state(nx, y, nz)
            props[face.id] = attaches(neighbour, faceShape(neighbour, face.opposite)).toString()
        }
        return intern(BlockState(state.id, props))
    }

    private fun stairs(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState = with(state, "shape", stairsShape(state, x, y, z, blocks))

    /** 1.13.2 `BlockStairs.getStairsShape` (only base properties of the neighbours are involved). */
    private fun stairsShape(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
    ): String {
        val facing = Face.byId(state.properties["facing"]) ?: return "straight"
        val half = state.properties["half"]

        val front = blocks.baseState(x + facing.dx, y, z + facing.dz)
        if (VanillaBlockShapes.isStairs(front) && front.properties["half"] == half) {
            val frontFacing = Face.byId(front.properties["facing"])
            if (frontFacing != null && !frontFacing.sameAxis(facing) && isDifferentStairs(state, x, y, z, frontFacing.opposite, blocks)) {
                return if (frontFacing == facing.counterClockwise) "outer_left" else "outer_right"
            }
        }
        val back = blocks.baseState(x - facing.dx, y, z - facing.dz)
        if (VanillaBlockShapes.isStairs(back) && back.properties["half"] == half) {
            val backFacing = Face.byId(back.properties["facing"])
            if (backFacing != null && !backFacing.sameAxis(facing) && isDifferentStairs(state, x, y, z, backFacing, blocks)) {
                return if (backFacing == facing.counterClockwise) "inner_left" else "inner_right"
            }
        }
        return "straight"
    }

    private fun isDifferentStairs(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        face: Face,
        blocks: LegacyBlockAccess,
    ): Boolean {
        val other = blocks.baseState(x + face.dx, y, z + face.dz)
        return !VanillaBlockShapes.isStairs(other) ||
            other.properties["facing"] != state.properties["facing"] ||
            other.properties["half"] != state.properties["half"]
    }

    /** `in_wall` if a wall is on either side of the gate's posts. */
    private fun fenceGate(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val facing = Face.byId(state.properties["facing"]) ?: return state
        val side = facing.clockwise
        val inWall =
            VanillaBlockShapes.isWall(blocks.baseState(x + side.dx, y, z + side.dz)) ||
                VanillaBlockShapes.isWall(blocks.baseState(x - side.dx, y, z - side.dz))
        return with(state, "in_wall", inWall.toString())
    }

    /** 1.13.2 `BlockRedstoneWire.getSide` for the four sides. */
    private fun redstoneWire(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val props = HashMap(state.properties)
        val aboveIsNormal = VanillaBlockShapes.isNormalCube(blocks.baseState(x, y + 1, z))
        for (face in Face.HORIZONTAL) {
            val nx = x + face.dx
            val nz = z + face.dz
            val neighbour = blocks.baseState(nx, y, nz)
            var side = "none"
            if (!aboveIsNormal &&
                (VanillaBlockShapes.isTopSolid(neighbour) || neighbour.id == GLOWSTONE) &&
                isRedstoneWire(blocks.baseState(nx, y + 1, nz))
            ) {
                side = if (VanillaBlockShapes.isBlockNormalCube(neighbour)) "up" else "side"
            } else if (wireConnects(neighbour, face) ||
                (!VanillaBlockShapes.isNormalCube(neighbour) && isRedstoneWire(blocks.baseState(nx, y - 1, nz)))
            ) {
                side = "side"
            }
            props[face.id] = side
        }
        return intern(BlockState(state.id, props))
    }

    private fun isRedstoneWire(state: BlockState): Boolean = state.id == REDSTONE_WIRE

    /** 1.13.2 `BlockRedstoneWire.canConnectTo(state, side)` for a horizontal [side]. */
    private fun wireConnects(
        state: BlockState,
        side: Face,
    ): Boolean {
        if (isRedstoneWire(state)) return true
        if (state.id == REPEATER) {
            val facing = Face.byId(state.properties["facing"])
            return facing == side || facing == side.opposite
        }
        return VanillaBlockShapes.canProvidePower(state)
    }

    /** `locked` if a powered repeater or comparator outputs into a side of this repeater. */
    private fun repeater(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val facing = Face.byId(state.properties["facing"]) ?: return state
        var locked = false
        for (side in arrayOf(facing.clockwise, facing.counterClockwise)) {
            val other = blocks.baseState(x + side.dx, y, z + side.dz)
            if ((other.id == REPEATER || other.id == COMPARATOR) &&
                other.properties["powered"] == "true" &&
                Face.byId(other.properties["facing"]) == side
            ) {
                locked = true
            }
        }
        return with(state, "locked", locked.toString())
    }

    private fun tripwire(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val props = HashMap(state.properties)
        for (face in Face.HORIZONTAL) {
            val neighbour = blocks.baseState(x + face.dx, y, z + face.dz)
            val connects =
                when (neighbour.id) {
                    TRIPWIRE_HOOK -> Face.byId(neighbour.properties["facing"]) == face.opposite
                    TRIPWIRE -> true
                    else -> false
                }
            props[face.id] = connects.toString()
        }
        return intern(BlockState(state.id, props))
    }

    /**
     * 1.13.2 `BlockVine.getUpdatedState`: sides stay only where they can attach (or the vine above has that side),
     * `up` if the block above has a solid bottom face; a vine without any attachment is removed.
     */
    private fun vine(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val props = HashMap(state.properties)
        if (props["up"] == "true") props["up"] = vineAttaches(blocks.baseState(x, y + 1, z), Face.DOWN).toString()
        val above = blocks.baseState(x, y + 1, z)
        for (face in Face.HORIZONTAL) {
            if (props[face.id] != "true") continue
            var attached = vineAttaches(blocks.baseState(x + face.dx, y, z + face.dz), face.opposite)
            if (!attached) attached = above.id == VINE && above.properties[face.id] == "true"
            props[face.id] = attached.toString()
        }
        if (props.none { (k, v) -> k != "up" && v == "true" } && props["up"] != "true") return BlockState.AIR
        return intern(BlockState(state.id, props))
    }

    private fun vineAttaches(
        state: BlockState,
        face: Face,
    ): Boolean = faceShape(state, face) == FaceShape.SOLID && !VanillaBlockShapes.isVineException(state)

    /** 1.13.2 `BlockFire.updatePostPlacement`: side flags on unsupported fire, removed if nothing holds it. */
    private fun fire(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val below = blocks.baseState(x, y - 1, z)
        val belowHolds = VanillaBlockShapes.isTopSolid(below)
        var anyFlammable = false
        val flags = HashMap<String, String>()
        for (face in Face.entries) {
            val flammable = VanillaBlockShapes.isFlammable(blocks.baseState(x + face.dx, y + face.dy, z + face.dz))
            anyFlammable = anyFlammable || flammable
            if (face != Face.DOWN) flags[face.id] = flammable.toString()
        }
        if (!belowHolds && !anyFlammable) return BlockState.AIR
        val props = HashMap<String, String>()
        props["age"] = state.properties["age"] ?: "0"
        for (face in Face.entries) if (face != Face.DOWN) props[face.id] = "false"
        if (!belowHolds && !VanillaBlockShapes.isFlammable(below)) props.putAll(flags)
        return intern(BlockState(state.id, props))
    }

    /**
     * Chests pair with an adjacent chest of the same block and facing on the axis across their facing.
     * Like 1.13's upgrade (positions in ascending order, first match wins) a row of chests pairs from its
     * north / west end.
     */
    private fun chest(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        val facing = Face.byId(state.properties["facing"]) ?: return state
        // the pairing axis runs perpendicular to the facing; walk towards its start (west / north)
        val back = if (facing.axisX) Face.NORTH else Face.WEST
        val forward = back.opposite
        var before = 0
        while (before < MAX_CHEST_ROW &&
            isSameChest(state, blocks.baseState(x + back.dx * (before + 1), y, z + back.dz * (before + 1)))
        ) {
            before++
        }
        val partner =
            if (before % 2 == 1) {
                back
            } else if (isSameChest(state, blocks.baseState(x + forward.dx, y, z + forward.dz))) {
                forward
            } else {
                return state
            }
        val type = if (partner == facing.clockwise) "left" else "right"
        return with(state, "type", type)
    }

    private fun isSameChest(
        state: BlockState,
        other: BlockState,
    ): Boolean = other.id == state.id && other.properties["facing"] == state.properties["facing"]

    /** A fully grown stem attaches to an adjacent fruit (1.7 checks west, east, north, south). */
    private fun stem(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState {
        if (state.properties["age"] != "7") return state
        val pumpkin = state.id.value == "pumpkin_stem"
        for (face in STEM_ORDER) {
            val fruit = blocks.baseState(x + face.dx, y, z + face.dz).id
            val matches = if (pumpkin) fruit == PUMPKIN.id || fruit == CARVED_PUMPKIN else fruit == MELON
            if (matches) {
                val attached = if (pumpkin) "attached_pumpkin_stem" else "attached_melon_stem"
                return intern(BlockState(Key.minecraft(attached), mapOf("facing" to face.id)))
            }
        }
        return state
    }

    private class SkullType(
        val floor: String,
        val wall: String,
    )

    companion object {
        private val SNOW = Key.minecraft("snow")
        private val SNOW_BLOCK = Key.minecraft("snow_block")
        private val GRASS_BLOCK = Key.minecraft("grass_block")
        private val DIRT = Key.minecraft("dirt")
        private val CARVED_PUMPKIN = Key.minecraft("carved_pumpkin")
        private val MELON = Key.minecraft("melon")
        private val GLOWSTONE = Key.minecraft("glowstone")
        private val REDSTONE_WIRE = Key.minecraft("redstone_wire")
        private val REPEATER = Key.minecraft("repeater")
        private val COMPARATOR = Key.minecraft("comparator")
        private val TRIPWIRE = Key.minecraft("tripwire")
        private val TRIPWIRE_HOOK = Key.minecraft("tripwire_hook")
        private val VINE = Key.minecraft("vine")
        private val PUMPKIN = BlockState(Key.minecraft("pumpkin"))
        private const val MAX_CHEST_ROW = 64

        private val STEM_ORDER = listOf(Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH)

        private val DOUBLE_PLANTS = setOf("sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony")

        private val SKULL_TYPES =
            listOf(
                SkullType("skeleton_skull", "skeleton_wall_skull"),
                SkullType("wither_skeleton_skull", "wither_skeleton_wall_skull"),
                SkullType("zombie_head", "zombie_wall_head"),
                SkullType("player_head", "player_wall_head"),
                SkullType("creeper_head", "creeper_wall_head"),
                SkullType("dragon_head", "dragon_wall_head"),
            )
        private val DEFAULT_SKULL = BlockState(Key.minecraft("skeleton_wall_skull"), mapOf("facing" to "north"))

        /** `<item registry name>:<data>` of a 1.7 flower pot -> the 1.13 potted block (DataConverter's FLOWER_POT_MAP). */
        private val POTTED: Map<String, BlockState> =
            mapOf(
                "minecraft:red_flower:0" to "potted_poppy",
                "minecraft:red_flower:1" to "potted_blue_orchid",
                "minecraft:red_flower:2" to "potted_allium",
                "minecraft:red_flower:3" to "potted_azure_bluet",
                "minecraft:red_flower:4" to "potted_red_tulip",
                "minecraft:red_flower:5" to "potted_orange_tulip",
                "minecraft:red_flower:6" to "potted_white_tulip",
                "minecraft:red_flower:7" to "potted_pink_tulip",
                "minecraft:red_flower:8" to "potted_oxeye_daisy",
                "minecraft:yellow_flower:0" to "potted_dandelion",
                "minecraft:sapling:0" to "potted_oak_sapling",
                "minecraft:sapling:1" to "potted_spruce_sapling",
                "minecraft:sapling:2" to "potted_birch_sapling",
                "minecraft:sapling:3" to "potted_jungle_sapling",
                "minecraft:sapling:4" to "potted_acacia_sapling",
                "minecraft:sapling:5" to "potted_dark_oak_sapling",
                "minecraft:red_mushroom:0" to "potted_red_mushroom",
                "minecraft:brown_mushroom:0" to "potted_brown_mushroom",
                "minecraft:deadbush:0" to "potted_dead_bush",
                "minecraft:tallgrass:2" to "potted_fern",
                "minecraft:cactus:0" to "potted_cactus",
            ).mapValues { BlockState(Key.minecraft(it.value)) }

        private val INTERNED = ConcurrentHashMap<BlockState, BlockState>()

        /** Fixed states repeat a lot (every fence line): share one instance per distinct state. */
        private fun intern(state: BlockState): BlockState = INTERNED.putIfAbsent(state, state) ?: state

        private fun with(
            state: BlockState,
            vararg keyValues: String,
        ): BlockState {
            var changed = false
            for (i in keyValues.indices step 2) {
                if (state.properties[keyValues[i]] != keyValues[i + 1]) changed = true
            }
            if (!changed) return state
            val props = HashMap(state.properties)
            for (i in keyValues.indices step 2) props[keyValues[i]] = keyValues[i + 1]
            return intern(BlockState(state.id, props))
        }
    }
}
