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

import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluenbt.NBTName

/** An item stack as 1.7 writes it (`id` is the world's numeric item id). */
class Ae2ItemStack {
    @field:NBTName("id")
    var id: Int = -1
        private set

    @field:NBTName("Damage")
    var damage: Int = 0
        private set
}

/**
 * AE2's `TileCableBus` (`CableBusContainer.writeToNBT`): `def:<side>` is the part's item stack per ForgeDirection
 * ordinal, `def:6` (UNKNOWN) the cable in the centre. All AE2 parts are damage values of `ItemMultiPart`.
 */
class Ae2CableBusTileEntity : MCABlockEntity() {
    @field:NBTName("def:0")
    private var def0: Ae2ItemStack? = null

    @field:NBTName("def:1")
    private var def1: Ae2ItemStack? = null

    @field:NBTName("def:2")
    private var def2: Ae2ItemStack? = null

    @field:NBTName("def:3")
    private var def3: Ae2ItemStack? = null

    @field:NBTName("def:4")
    private var def4: Ae2ItemStack? = null

    @field:NBTName("def:5")
    private var def5: Ae2ItemStack? = null

    @field:NBTName("def:6")
    private var def6: Ae2ItemStack? = null

    /** The stack on ForgeDirection [side] (0..5), or the centre cable for 6. */
    fun def(side: Int): Ae2ItemStack? = when (side) {
        0 -> def0
        1 -> def1
        2 -> def2
        3 -> def3
        4 -> def4
        5 -> def5
        6 -> def6
        else -> null
    }

    /** The centre cable, if it is one. */
    val cable: Ae2Cable? get() = def6?.let { Ae2Cable.of(it.damage) }

    /**
     * The AE2 part on ForgeDirection [side]. Every `ItemMultiPart` stack in a bus shares the centre cable's item
     * id, so a stack with another id is an addon's part ([Ae2Part.Kind.UNKNOWN]).
     */
    fun part(side: Int): Ae2Part? {
        val stack = def(side) ?: return null
        val cableId = def6?.id
        if (cableId != null && stack.id != cableId) return Ae2Part(Ae2Part.Kind.UNKNOWN, stack.damage)
        return Ae2Part.of(stack.damage)
    }

    companion object {
        val BLOCK_ENTITY_TYPES: List<BlockEntityType> = listOf("BlockCableBus", "BlockCableBusTESR").map {
            BlockEntityType.Impl(Key.minecraft(it), Ae2CableBusTileEntity::class.java)
        }
    }
}

/** A cable: type by `ItemMultiPart` damage base, colour = damage - base (`AEColor` ordinal, 16 = fluix). */
data class Ae2Cable(val type: Type, val color: Int) {
    enum class Type(
        val base: Int,
        /** Core box (pixels, min = 16 - max). */
        val core: Float,
        /** Connection arm half-width (pixels from the centre). */
        val arm: Float,
        /** Texture prefix of the coloured variants and the fluix texture. */
        val prefix: String,
        val fluix: String,
    ) {
        GLASS(0, 6f, 2f, "MECable", "ItemPart.CableGlass"),
        COVERED(20, 5f, 3f, "MECovered", "ItemPart.CableCovered"),
        SMART(40, 5f, 3f, "MESmart", "ItemPart.CableSmart"),
        DENSE(60, 3f, 4f, "MEDense", "ItemPart.CableDense"),
        DENSE_COVERED(520, 3f, 4f, "MEDenseCovered", "ItemPart.CableDenseCovered"),
    }

    /** Whether two cables join: equal colours, or either is fluix. */
    fun connectsTo(other: Ae2Cable): Boolean = color == other.color || color == FLUIX || other.color == FLUIX

    companion object {
        const val FLUIX = 16

        fun of(damage: Int): Ae2Cable? {
            for (type in Type.entries) {
                if (damage in type.base..type.base + FLUIX) return Ae2Cable(type, damage - type.base)
            }
            return null
        }

        /** `AEColor` names (ordinal order) as AE2's cable textures spell them: Gray/Grey vary by prefix. */
        val COLOR_NAMES = listOf(
            "White", "Orange", "Magenta", "LightBlue", "Yellow", "Lime", "Pink", "Gray",
            "LightGray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black",
        )

        /** Candidate texture names of [color] for [prefix] (first existing wins). */
        fun textureNames(type: Type, color: Int): List<String> {
            if (color !in 0 until FLUIX) return listOf(type.fluix)
            val name = COLOR_NAMES[color]
            val spellings = when (name) {
                "Gray" -> listOf("Gray", "Grey")
                "LightGray" -> listOf("LightGrey", "LightGray")
                else -> listOf(name)
            }
            return spellings.map { "${type.prefix}_$it" }
        }
    }
}

/** A non-cable part on a bus side (`PartType` by `ItemMultiPart` damage). */
data class Ae2Part(val kind: Kind, val damage: Int) {
    enum class Kind(
        /** `cableConnectionRenderTo`: the centre cable's stub ends 16 - this pixels from the part's side. */
        val stub: Float,
    ) {
        DISPLAY(3f), IMPORT_BUS(5f), EXPORT_BUS(5f), STORAGE_BUS(4f), INTERFACE(4f), PLANE(1f), LEVEL_EMITTER(5f),
        TOGGLE_BUS(5f), QUARTZ_FIBER(6f), CABLE_ANCHOR(6f), P2P(1f), UNKNOWN(3f),
    }

    companion object {
        /** Display parts: damage -> texture stem of their screen overlays (`<stem>_Bright/_Dark/_Colored`). */
        val DISPLAY_SCREENS: Map<Int, String?> = mapOf(
            160 to "PartMonitor", 180 to "PartMonitor", 200 to "PartMonitor",
            340 to "PartPatternTerm", 360 to "PartCraftingTerm", 370 to "PartCraftingDiagnosticTerm", 380 to "PartTerminal",
            400 to "PartStorageMonitor", 410 to "PartThroughputMonitor", 420 to "PartConversionMonitor",
            480 to "PartInterfaceTerm", 500 to "PartPatternTerm", 473 to null, 690 to null,
        )

        fun of(damage: Int): Ae2Part? {
            if (Ae2Cable.of(damage) != null) return null
            val kind = when (damage) {
                in DISPLAY_SCREENS -> Kind.DISPLAY
                220 -> Kind.STORAGE_BUS
                240 -> Kind.IMPORT_BUS
                260 -> Kind.EXPORT_BUS
                280, 281 -> Kind.LEVEL_EMITTER
                300, 301, 320 -> Kind.PLANE
                80, 100 -> Kind.TOGGLE_BUS
                120 -> Kind.CABLE_ANCHOR
                140 -> Kind.QUARTZ_FIBER
                440 -> Kind.INTERFACE
                in 460..472 -> Kind.P2P
                else -> Kind.UNKNOWN
            }
            return Ae2Part(kind, damage)
        }

        /** `ItemMultiPart` icon (the part's face) of a part damage: `ItemPart.<PartType name>`. */
        val ICONS: Map<Int, String> = mapOf(
            80 to "ToggleBus", 100 to "InvertedToggleBus", 120 to "CableAnchor", 140 to "QuartzFiber",
            160 to "Monitor", 180 to "SemiDarkMonitor", 200 to "DarkMonitor", 220 to "StorageBus", 240 to "ImportBus",
            260 to "ExportBus", 280 to "LevelEmitter", 281 to "AdvancedLevelEmitter", 300 to "AnnihilationPlane",
            301 to "IdentityAnnihilationPlane", 320 to "FormationPlane", 340 to "PatternTerminal", 360 to "CraftingTerminal",
            370 to "CraftingDiagnosticTerminal", 380 to "Terminal", 400 to "StorageMonitor", 410 to "ThroughputMonitor",
            420 to "ConversionMonitor", 440 to "Interface", 460 to "P2PTunnel", 461 to "P2PTunnel", 462 to "P2PTunnel",
            463 to "P2PTunnel", 465 to "P2PTunnel", 466 to "P2PTunnel", 467 to "P2PTunnel", 468 to "P2PTunnel",
            469 to "P2PTunnel", 470 to "P2PTunnel", 471 to "P2PTunnel", 472 to "P2PTunnel", 473 to "PatternRepeater",
            480 to "InterfaceTerminal", 500 to "PatternTerminalEx", 690 to "PartCreativeEnergy",
        ).mapValues { "ItemPart.${it.value}" }
    }
}
