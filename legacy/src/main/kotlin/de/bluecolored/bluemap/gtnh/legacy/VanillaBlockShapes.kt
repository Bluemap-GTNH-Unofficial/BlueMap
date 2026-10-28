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
import de.bluecolored.bluemap.core.world.BlockState

/** The six block faces; horizontal ones carry their x/z offset. */
internal enum class Face(
    val dx: Int,
    val dy: Int,
    val dz: Int,
) {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0),
    ;

    val id: String = name.lowercase()
    val isHorizontal: Boolean get() = dy == 0
    val axisX: Boolean get() = dx != 0

    val opposite: Face
        get() =
            when (this) {
                DOWN -> UP
                UP -> DOWN
                NORTH -> SOUTH
                SOUTH -> NORTH
                WEST -> EAST
                EAST -> WEST
            }

    /** Clockwise seen from above (Mojang's `rotateY`). */
    val clockwise: Face
        get() =
            when (this) {
                NORTH -> EAST
                EAST -> SOUTH
                SOUTH -> WEST
                WEST -> NORTH
                else -> this
            }

    /** Counter-clockwise seen from above (Mojang's `rotateYCCW`). */
    val counterClockwise: Face
        get() =
            when (this) {
                NORTH -> WEST
                WEST -> SOUTH
                SOUTH -> EAST
                EAST -> NORTH
                else -> this
            }

    fun sameAxis(other: Face): Boolean = axisX == other.axisX && isHorizontal && other.isHorizontal

    companion object {
        /** NORTH, SOUTH, WEST, EAST: the order 1.13's upgrade visits horizontal neighbours in. */
        val HORIZONTAL: List<Face> = listOf(NORTH, SOUTH, WEST, EAST)

        fun byId(id: String?): Face? = entries.firstOrNull { it.id == id }
    }
}

/** 1.13's `BlockFaceShape`, reduced to the cases connection logic distinguishes. */
internal enum class FaceShape { SOLID, MIDDLE_POLE, MIDDLE_POLE_THICK, MIDDLE_POLE_THIN, OTHER }

/**
 * Shape and material facts of the vanilla 1.13 block states that 1.7.10 blocks flatten to, as far as the
 * neighbour-dependent properties need them (Minecraft 1.13.2 `BlockFaceShape`, `isNormalCube`, `isTopSolid`,
 * `canProvidePower`, fire flammability).
 *
 * Non-vanilla (modded) blocks have no shape information here: they count as solid opaque full cubes, which is what
 * most modded blocks are (machines, casings, ores).
 *
 * Re-implements Minecraft 1.13 behaviour in Kotlin (Minecraft (c) Mojang AB / Microsoft); see NOTICE.md.
 */
internal object VanillaBlockShapes {
    private fun names(vararg names: String): Set<String> = names.toSet()

    private val COLORS =
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
    private val WOODS = listOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")

    /** Blocks without a full-cube shape (everything else that is not air is a full cube). */
    private val NON_FULL: Set<String> =
        buildSet {
            addAll(
                names(
                    "air",
                    "cave_air",
                    "void_air",
                    "water",
                    "lava",
                    "cobweb",
                    "grass",
                    "fern",
                    "dead_bush",
                    "dandelion",
                    "poppy",
                    "blue_orchid",
                    "allium",
                    "azure_bluet",
                    "red_tulip",
                    "orange_tulip",
                    "white_tulip",
                    "pink_tulip",
                    "oxeye_daisy",
                    "brown_mushroom",
                    "red_mushroom",
                    "torch",
                    "wall_torch",
                    "fire",
                    "redstone_wire",
                    "wheat",
                    "farmland",
                    "sign",
                    "wall_sign",
                    "ladder",
                    "rail",
                    "powered_rail",
                    "detector_rail",
                    "activator_rail",
                    "lever",
                    "stone_pressure_plate",
                    "oak_pressure_plate",
                    "light_weighted_pressure_plate",
                    "heavy_weighted_pressure_plate",
                    "redstone_torch",
                    "redstone_wall_torch",
                    "stone_button",
                    "oak_button",
                    "snow",
                    "cactus",
                    "sugar_cane",
                    "cake",
                    "repeater",
                    "comparator",
                    "iron_bars",
                    "glass_pane",
                    "pumpkin_stem",
                    "melon_stem",
                    "attached_pumpkin_stem",
                    "attached_melon_stem",
                    "vine",
                    "lily_pad",
                    "nether_wart",
                    "enchanting_table",
                    "brewing_stand",
                    "cauldron",
                    "end_portal",
                    "end_portal_frame",
                    "dragon_egg",
                    "cocoa",
                    "tripwire",
                    "tripwire_hook",
                    "flower_pot",
                    "carrots",
                    "potatoes",
                    "anvil",
                    "chipped_anvil",
                    "damaged_anvil",
                    "daylight_detector",
                    "hopper",
                    "chest",
                    "trapped_chest",
                    "ender_chest",
                    "nether_portal",
                    "moving_piston",
                    "piston_head",
                    "red_bed",
                    "oak_door",
                    "iron_door",
                    "oak_trapdoor",
                    "oak_fence",
                    "nether_brick_fence",
                    "oak_fence_gate",
                    "cobblestone_wall",
                    "mossy_cobblestone_wall",
                    "tall_grass",
                    "large_fern",
                    "sunflower",
                    "lilac",
                    "rose_bush",
                    "peony",
                ),
            )
            for (sapling in WOODS) add("${sapling}_sapling")
            for (color in COLORS) {
                add("${color}_carpet")
                add("${color}_stained_glass_pane")
            }
            for (type in listOf("skeleton", "wither_skeleton")) {
                add("${type}_skull")
                add("${type}_wall_skull")
            }
            for (type in listOf("zombie", "player", "creeper", "dragon")) {
                add("${type}_head")
                add("${type}_wall_head")
            }
        }

    /** Full cubes whose material is not opaque (Material.GLASS / ICE / LEAVES / TNT ...). */
    private val NON_OPAQUE_FULL: Set<String> =
        buildSet {
            addAll(names("glass", "ice", "glowstone", "beacon", "tnt"))
            for (color in COLORS) add("${color}_stained_glass")
            for (wood in WOODS) add("${wood}_leaves")
        }

    /** Blocks no connecting block attaches to (as observed in 1.13.2: gourds and pistons). */
    private val COMMON_ATTACH_EXCEPTIONS: Set<String> =
        names(
            "pumpkin",
            "carved_pumpkin",
            "jack_o_lantern",
            "melon",
            "piston",
            "sticky_piston",
            "piston_head",
            "beacon",
            "cauldron",
            "glowstone",
            "ice",
            "oak_trapdoor",
            "iron_trapdoor",
        ) + WOODS.map { "${it}_leaves" }

    /** Fences and walls additionally skip glass and TNT (1.13.2 oracle). */
    private val FENCE_ATTACH_EXCEPTIONS: Set<String> =
        COMMON_ATTACH_EXCEPTIONS + names("glass", "tnt") + COLORS.map { "${it}_stained_glass" }

    /** Panes and iron bars attach to glass and TNT, unlike fences (1.13.2 oracle). */
    private val PANE_ATTACH_EXCEPTIONS: Set<String> = COMMON_ATTACH_EXCEPTIONS

    /** 1.13 `BlockVine.isExceptBlockForAttaching`: vines do not attach to these. */
    private val VINE_EXCEPTIONS: Set<String> =
        buildSet {
            addAll(names("beacon", "cauldron", "glass", "piston", "sticky_piston", "piston_head", "oak_trapdoor", "iron_trapdoor"))
            for (color in COLORS) add("${color}_stained_glass")
        }

    /** Blocks that `canProvidePower` (redstone wire connects to them from any side). */
    private val POWER_SOURCES: Set<String> =
        names(
            "redstone_torch",
            "redstone_wall_torch",
            "lever",
            "stone_button",
            "oak_button",
            "stone_pressure_plate",
            "oak_pressure_plate",
            "light_weighted_pressure_plate",
            "heavy_weighted_pressure_plate",
            "detector_rail",
            "tripwire_hook",
            "daylight_detector",
            "trapped_chest",
            "redstone_block",
            "comparator",
            "repeater",
        )

    /** Blocks with a fire encouragement > 0 (1.13 `BlockFire.init`), i.e. fire burns on their faces. */
    private val FLAMMABLE: Set<String> =
        buildSet {
            for (wood in WOODS) {
                for (suffix in listOf("planks", "slab", "fence_gate", "fence", "stairs", "log", "wood", "leaves")) add("${wood}_$suffix")
            }
            addAll(
                names(
                    "bookshelf",
                    "tnt",
                    "grass",
                    "fern",
                    "dead_bush",
                    "sunflower",
                    "lilac",
                    "rose_bush",
                    "peony",
                    "tall_grass",
                    "large_fern",
                    "dandelion",
                    "poppy",
                    "blue_orchid",
                    "allium",
                    "azure_bluet",
                    "red_tulip",
                    "orange_tulip",
                    "white_tulip",
                    "pink_tulip",
                    "oxeye_daisy",
                    "vine",
                    "coal_block",
                    "hay_block",
                ),
            )
            for (color in COLORS) {
                add("${color}_wool")
                add("${color}_carpet")
            }
        }

    private fun vanilla(state: BlockState): String? {
        val id: Key = state.id
        return if (id.namespace == Key.MINECRAFT_NAMESPACE) id.value else null
    }

    fun isFullCube(state: BlockState): Boolean {
        val name = vanilla(state) ?: return true
        if (name.endsWith("_slab")) return state.properties["type"] == "double"
        if (name.endsWith("_stairs")) return false
        if (name == "piston" || name == "sticky_piston") return state.properties["extended"] != "true"
        if (name == "snow") return false
        return name !in NON_FULL && !name.startsWith("potted_")
    }

    private fun isOpaque(state: BlockState): Boolean {
        val name = vanilla(state) ?: return true
        return name !in NON_OPAQUE_FULL
    }

    fun canProvidePower(state: BlockState): Boolean = vanilla(state) in POWER_SOURCES

    /** 1.13 `isNormalCube`: opaque material, full cube, no power source. */
    fun isNormalCube(state: BlockState): Boolean = isFullCube(state) && isOpaque(state) && !canProvidePower(state)

    /** 1.13 `isBlockNormalCube`: movement-blocking full cube (glass and leaves count, unlike [isNormalCube]). */
    fun isBlockNormalCube(state: BlockState): Boolean = isFullCube(state)

    /** 1.13 `isTopSolid` (snow layers never are). */
    fun isTopSolid(state: BlockState): Boolean {
        val name = vanilla(state) ?: return true
        if (name.endsWith("_slab")) return state.properties["type"] != "bottom"
        if (name.endsWith("_stairs")) return state.properties["half"] == "top"
        if (name == "hopper") return true
        return isFullCube(state) && isOpaque(state)
    }

    fun isFlammable(state: BlockState): Boolean = vanilla(state) in FLAMMABLE

    /** Fences and walls do not attach to the solid face of these. */
    fun isFenceAttachException(state: BlockState): Boolean = vanilla(state) in FENCE_ATTACH_EXCEPTIONS

    /** Glass panes and iron bars do not attach to the solid face of these. */
    fun isPaneAttachException(state: BlockState): Boolean = vanilla(state) in PANE_ATTACH_EXCEPTIONS

    fun isVineException(state: BlockState): Boolean = vanilla(state) in VINE_EXCEPTIONS

    fun isFence(state: BlockState): Boolean = vanilla(state).let { it == "oak_fence" || it == "nether_brick_fence" }

    fun isFenceGate(state: BlockState): Boolean = vanilla(state)?.endsWith("_fence_gate") == true

    fun isWall(state: BlockState): Boolean = vanilla(state).let { it == "cobblestone_wall" || it == "mossy_cobblestone_wall" }

    fun isPane(state: BlockState): Boolean =
        vanilla(state).let {
            it == "iron_bars" || it == "glass_pane" ||
                it?.endsWith("_stained_glass_pane") == true
        }

    fun isStairs(state: BlockState): Boolean = vanilla(state)?.endsWith("_stairs") == true

    /** Wooden fences and fence gates (material WOOD) versus the nether brick fence (ROCK). */
    fun isWooden(state: BlockState): Boolean = vanilla(state) != "nether_brick_fence"

    /**
     * The shape of the [face] of [state] (the face pointing towards the asking block).
     * Stairs need their final `shape` property.
     */
    fun faceShape(
        state: BlockState,
        face: Face,
    ): FaceShape {
        val name = vanilla(state) ?: return FaceShape.SOLID
        val props = state.properties
        when {
            name.endsWith("_slab") -> {
                return when (props["type"]) {
                    "double" -> FaceShape.SOLID
                    "top" -> if (face == Face.UP) FaceShape.SOLID else FaceShape.OTHER
                    else -> if (face == Face.DOWN) FaceShape.SOLID else FaceShape.OTHER
                }
            }

            name.endsWith("_stairs") -> {
                return stairsFaceShape(state, face)
            }

            isFence(state) -> {
                return if (face.isHorizontal) FaceShape.MIDDLE_POLE else FaceShape.OTHER
            }

            isFenceGate(state) -> {
                val facing = Face.byId(props["facing"]) ?: return FaceShape.OTHER
                return if (face.isHorizontal && face.sameAxis(facing.clockwise)) FaceShape.MIDDLE_POLE else FaceShape.OTHER
            }

            isWall(state) -> {
                return if (face.isHorizontal) FaceShape.MIDDLE_POLE_THICK else FaceShape.OTHER
            }

            isPane(state) -> {
                return if (face.isHorizontal) FaceShape.MIDDLE_POLE_THIN else FaceShape.OTHER
            }

            name == "cauldron" -> {
                return if (face.isHorizontal) FaceShape.SOLID else FaceShape.OTHER
            }

            name == "piston" || name == "sticky_piston" -> {
                if (props["extended"] != "true") return FaceShape.SOLID
                return if (Face.byId(props["facing"]) != face) FaceShape.SOLID else FaceShape.OTHER
            }

            name == "piston_head" -> {
                return if (Face.byId(props["facing"]) == face) FaceShape.SOLID else FaceShape.OTHER
            }

            name in DOWN_SOLID -> {
                return if (face == Face.DOWN) FaceShape.SOLID else FaceShape.OTHER
            }

            else -> {
                return if (isFullCube(state)) FaceShape.SOLID else FaceShape.OTHER
            }
        }
    }

    /** Non-full blocks with a solid bottom face. */
    private val DOWN_SOLID: Set<String> =
        buildSet {
            addAll(names("snow", "farmland", "enchanting_table", "end_portal_frame", "daylight_detector", "repeater", "comparator"))
            for (color in COLORS) add("${color}_carpet")
        }

    /** 1.12/1.13 `BlockStairs.getBlockFaceShape`. */
    private fun stairsFaceShape(
        state: BlockState,
        face: Face,
    ): FaceShape {
        val props = state.properties
        if (!face.isHorizontal) {
            return if ((face == Face.UP) == (props["half"] == "top")) FaceShape.SOLID else FaceShape.OTHER
        }
        val facing = Face.byId(props["facing"]) ?: return FaceShape.OTHER
        val solid =
            when (props["shape"]) {
                "straight" -> facing == face
                "inner_right" -> facing == face || facing == face.counterClockwise
                "inner_left" -> facing == face || facing == face.clockwise
                else -> false
            }
        return if (solid) FaceShape.SOLID else FaceShape.OTHER
    }
}
