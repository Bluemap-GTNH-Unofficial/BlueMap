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

/** Chooses the layered faces GT shows for a block, from the dump and the tile entity / block meta. */
object GtFaces {
    /** Faces of a (non-pipe) machine: front per facing, active state, basic-machine output side, hatch casing, paint. */
    fun machine(
        data: GtData,
        mte: GtMte,
        te: GtTileEntity,
    ): GtSides? {
        val facing = te.facing.takeIf { it in 0 until 6 } ?: Side.NORTH
        val active = if (te.active) 1 else 0
        val colour = te.colourIndex
        // hatches with their own casing: composed from the front overlay and the casing / painted tier casing;
        // otherwise the dumped faces are GT's exact result (a few hatches override the composition)
        val hatch = mte.hatch
        if (hatch != null && (te.casingTextureId > 0 || colour >= 0 || mte.textures == null)) {
            return hatch(data, mte, hatch, te.casingTextureId, facing, active, colour)
        }
        val textures = mte.textures ?: return null
        // basic machines: mMainFacing is the front, mFacing the output side
        val output = mte.outputFace?.getOrNull(active)
        val front = if (output != null && te.mainFacing in 0 until 6) te.mainFacing else facing
        val sides = textures.getOrNull(front)?.getOrNull(active)?.toMutableList() ?: return null
        if (output != null && front != facing) {
            sides[facing] =
                when (facing) {
                    Side.DOWN -> output.down
                    Side.UP -> output.up
                    else -> output.side
                }
        }
        return if (colour < 0) sides else sides.map { data.paintMachineFace(it, colour) }
    }

    /** A hatch's faces: casing background on every side, front overlays on the facing side. */
    internal fun hatch(
        data: GtData,
        mte: GtMte,
        hatch: GtHatch,
        casingTextureId: Int,
        facing: Int,
        active: Int,
        colour: Int,
    ): GtSides {
        val casing = casingTextureId.takeIf { it > 0 }?.let { data.casings[it] }
        return (0 until 6).map { side ->
            val background =
                casing?.getOrNull(side)
                    ?: data.machineCasings
                        .getOrNull(mte.tier)
                        ?.getOrNull(colour + 1)
                        ?.getOrNull(side)
                        ?.takeIf { colour >= 0 }
                    ?: hatch.defaultBackground.getOrNull(side)
                    ?: emptyList()
            if (side != facing) return@map background
            val front = hatch.front.getOrNull(active) ?: return@map background
            if (front.none { it.background }) front else front.flatMap { if (it.background) background else listOf(it) }
        }
    }

    /** Face of a pipe/cable/MTE frame on [side], tinted with its paint ([colour] = `mColor - 1`). */
    fun pipe(
        data: GtData,
        pipe: GtPipe,
        connections: Int,
        side: Int,
        colour: Int,
    ): GtFace {
        val face = if (GtPipeGeometry.showsConnectedTexture(connections, side)) pipe.connected else pipe.unconnected
        val dye = data.dyeRgb(colour) ?: return face
        return face.map { layer -> if (isPaintedPipeLayer(pipe, layer)) layer.withRgb(dye) else layer }
    }

    /**
     * Which pipe layers take the paint colour (GT `Dyes.getModulation(colour, default)`): an insulated cable paints
     * its insulation (the layers not tinted with the wire material), everything else its material-tinted layers.
     */
    private fun isPaintedPipeLayer(
        pipe: GtPipe,
        layer: GtLayer,
    ): Boolean {
        val material = pipe.materialRgb ?: return false
        if (layer.texture == null) return false
        return if (pipe.insulated) layer.rgb != material && layer.rgb != GtLayer.WHITE else layer.rgb == material
    }

    /** All six faces of a `gt.blockores2..7` block (`GTBlockOre.getTextures`), null if the block is not in the dump. */
    fun ore(
        data: GtData,
        block: Key,
        meta: Int,
    ): GtFace? {
        val ore = data.oreBlocks[block] ?: return null
        val decoded = GtOreMeta.decode(meta)
        val stone = ore.stones.getOrNull(decoded.stone) ?: data.oreBlocks[DEFAULT_ORE_BLOCK]?.stones?.getOrNull(0)
        val background = stone?.background ?: emptyList()
        val material = data.materials[decoded.material]
        val foreground =
            if (material != null) {
                data.oreTexture(material, stone?.name, decoded.small)?.let {
                    GtLayer(it.texture, it.overlay, material.rgb, glow = material.glowingOre, pass = 1)
                }
            } else {
                data.noMaterialOre?.let { GtLayer(it.texture, it.overlay, pass = 1) }
            }
        return if (foreground == null) background else background + foreground
    }

    /** All six faces of a `gt.blockframes` block: the material (`meta & 0xFFF`) frame icon, tinted. */
    fun frame(
        data: GtData,
        meta: Int,
    ): GtFace? {
        val material = data.materials[meta and FRAME_MATERIAL_MASK] ?: return null
        val icon = data.iconSets[material.iconSet]?.get(FRAME_ICON) ?: return null
        if (icon.texture == null && icon.overlay == null) return null
        return listOf(GtLayer(icon.texture, icon.overlay, material.rgb, pass = icon.pass))
    }

    /** Last resort for an unknown or untextured machine: the unpainted ULV machine casing. */
    fun casingFallback(data: GtData): GtSides? =
        data.machineCasings
            .firstOrNull()
            ?.firstOrNull()
            ?.takeIf { sides -> sides.any { it.isNotEmpty() } }

    val DEFAULT_ORE_BLOCK = Key("gregtech", "gt.blockores2")
    const val FRAME_MATERIAL_MASK = 0xFFF
    const val FRAME_ICON = "FRAME_GT"
}
