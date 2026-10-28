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

import de.bluecolored.bluemap.core.util.Key

/** How a layer's UVs are oriented (exporter README "UV orientation"). */
enum class UvMode {
    /** `GTRenderedTexture` without ExtendedFacing: vanilla faces with the north/east mirroring undone, down U-flipped. */
    RENDERED,

    /** `GTRenderedTexture` with `extFacing`: rotated and flipped by the tile entity's ExtendedFacing. */
    EXT_FACING,

    /** `GTCopiedBlockTextureRender`: vanilla faces with the north/east mirroring undone, nothing else. */
    COPIED_BLOCK,
}

/**
 * One layer of a GT face, bottom first. [texture] is tinted with [rgb] and skipped when null (GT's invisible icon
 * or an icon the exporter could not write); [overlay] is drawn on top of it, untinted.
 */
data class GtLayer(
    val texture: Key?,
    val overlay: Key? = null,
    val rgb: Int = WHITE,
    val glow: Boolean = false,
    val pass: Int = 0,
    val uv: UvMode = UvMode.RENDERED,
    /** Placeholder for a hatch's casing background (`mte.json` `hatch.front`). */
    val background: Boolean = false,
) {
    fun withRgb(rgb: Int): GtLayer = if (rgb == this.rgb) this else copy(rgb = rgb)

    companion object {
        const val WHITE = 0xFFFFFF
        val BACKGROUND = GtLayer(texture = null, background = true)
    }
}

/** The layers drawn on one block side, bottom layer first. */
typealias GtFace = List<GtLayer>

/** Per-side faces (index = ForgeDirection ordinal: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east). */
typealias GtSides = List<GtFace>

class GtOutputFace(val down: GtFace, val up: GtFace, val side: GtFace)

class GtHatch(
    /** [inactive, active] front faces; may contain [GtLayer.BACKGROUND]. */
    val front: List<GtFace>,
    val defaultBackground: GtSides,
)

class GtPipe(
    val thickness: Float,
    /** The pipe material's colour (GT `mRGBa`), null for pipes without a GT material. */
    val materialRgb: Int?,
    val insulated: Boolean,
    /** Face of a connected side (also every side of an unconnected pipe). */
    val connected: GtFace,
    /** Face of an unconnected side. */
    val unconnected: GtFace,
)

class GtMte(
    val id: Int,
    val name: String,
    val kind: String,
    val tier: Int,
    /** Multiblock controller with an ExtendedFacing (`eRotation`/`eFlip`). */
    val alignment: Boolean,
    /** Also has a tile entity special renderer that is not represented. */
    val tesr: Boolean,
    /** Overrides `renderInWorld` with custom geometry that is not represented. */
    val customRender: String?,
    /** `[facing][active][side]`, null for pipes. */
    val textures: List<List<GtSides>>?,
    /** `[active]`, basic machines only. */
    val outputFace: List<GtOutputFace>?,
    val hatch: GtHatch?,
    val pipe: GtPipe?,
)

/** An IIconContainer: base icon, optional overlay and render pass. */
class GtIconRef(val texture: Key?, val overlay: Key?, val pass: Int)

class GtMaterial(val id: Int, val name: String, val rgb: Int, val iconSet: String, val glowingOre: Boolean)

class GtOreTexture(val normal: GtIconRef?, val small: GtIconRef?)

class GtOreStone(val name: String, val background: GtFace)

class GtOreBlock(
    val block: Key,
    /** Stone slot 0..7. */
    val stones: List<GtOreStone?>,
)

class GtOreTextures(
    val default: GtOreTexture,
    /** By StoneType name. */
    val perStone: Map<String, GtOreTexture>,
)

/** The decoded meta of a `gt.blockores2..7` block (`GTOreAdapter`). */
data class GtOreMeta(val material: Int, val stone: Int, val small: Boolean, val natural: Boolean) {
    companion object {
        fun decode(meta: Int): GtOreMeta =
            GtOreMeta(
                material = meta % 1000,
                stone = (meta % 16000 % 8000) / 1000,
                small = meta >= 16000,
                natural = meta % 16000 >= 8000,
            )
    }
}

/** Everything the GT renderers read from the exporter's `gtnh/gregtech/` dump. */
class GtData(
    val fixedBottomFaceUV: Boolean,
    val renderGlowTextures: Boolean,
    val mtes: Map<Int, GtMte>,
    /** Casing texture id (`page << 7 | index`) -> per-side faces. */
    val casings: Map<Int, GtSides>,
    /** `[tier][colour 0..16][side]`; colour 0 is unpainted, `c + 1` painted with dye `c`. */
    val machineCasings: List<List<GtSides>>,
    val materials: Map<Int, GtMaterial>,
    /** Icon set name -> MaterialIconRegistry.IconType -> icon. */
    val iconSets: Map<String, Map<String, GtIconRef>>,
    /** Dye index 0..15 -> rgb. */
    val dyes: IntArray,
    val oreBlocks: Map<Key, GtOreBlock>,
    val oreTextures: Map<String, GtOreTextures>,
    val noMaterialOre: GtIconRef?,
) {
    /** Unpainted machine-casing layer -> its painted variants (index = dye). */
    private val paintedCasingLayers: Map<GtLayer, List<GtLayer>> =
        HashMap<GtLayer, List<GtLayer>>().also { map ->
            for (tier in machineCasings) {
                val unpainted = tier.firstOrNull() ?: continue
                for ((side, face) in unpainted.withIndex()) {
                    for ((index, layer) in face.withIndex()) {
                        if (map.containsKey(layer)) continue
                        map[layer] = (0 until DYES).map { dye -> tier.getOrNull(dye + 1)?.getOrNull(side)?.getOrNull(index) ?: layer }
                    }
                }
            }
        }

    /** The dye's rgb for a GT colour index (`mColor - 1`), null if unpainted. */
    fun dyeRgb(colour: Int): Int? = if (colour in 0 until DYES && colour < dyes.size) dyes[colour] else null

    /** A face as painted with `colour` (`mColor - 1`): machine-casing layers are swapped for their painted variant. */
    fun paintMachineFace(face: GtFace, colour: Int): GtFace {
        if (colour !in 0 until DYES) return face
        var result: MutableList<GtLayer>? = null
        for ((i, layer) in face.withIndex()) {
            val painted = paintedCasingLayers[layer]?.get(colour) ?: continue
            if (painted == layer) continue
            if (result == null) result = face.toMutableList()
            result[i] = painted
        }
        return result ?: face
    }

    fun oreTexture(material: GtMaterial, stone: String?, small: Boolean): GtIconRef? {
        val textures = oreTextures[material.iconSet] ?: return null
        val texture = stone?.let { textures.perStone[it] } ?: textures.default
        return if (small) texture.small else texture.normal
    }

    /** Every texture any face or icon of the dump can draw. */
    fun textureKeys(): Set<Key> {
        val keys = HashSet<Key>()
        fun add(layer: GtLayer) {
            layer.texture?.let(keys::add)
            layer.overlay?.let(keys::add)
        }
        fun add(face: GtFace) = face.forEach(::add)
        fun add(icon: GtIconRef?) {
            icon?.texture?.let(keys::add)
            icon?.overlay?.let(keys::add)
        }
        for (mte in mtes.values) {
            mte.textures?.forEach { facing -> facing.forEach { sides -> sides.forEach(::add) } }
            mte.outputFace?.forEach { add(it.down); add(it.up); add(it.side) }
            mte.hatch?.let { hatch -> hatch.front.forEach(::add); hatch.defaultBackground.forEach(::add) }
            mte.pipe?.let { add(it.connected); add(it.unconnected) }
        }
        casings.values.forEach { it.forEach(::add) }
        machineCasings.forEach { tier -> tier.forEach { colour -> colour.forEach(::add) } }
        iconSets.values.forEach { it.values.forEach(::add) }
        oreBlocks.values.forEach { block -> block.stones.forEach { stone -> stone?.background?.let(::add) } }
        oreTextures.values.forEach { textures ->
            add(textures.default.normal)
            add(textures.default.small)
            textures.perStone.values.forEach { add(it.normal); add(it.small) }
        }
        add(noMaterialOre)
        return keys
    }

    companion object {
        const val DYES = 16
    }
}
