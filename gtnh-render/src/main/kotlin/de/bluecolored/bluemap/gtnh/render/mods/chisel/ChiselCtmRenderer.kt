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
package de.bluecolored.bluemap.gtnh.render.mods.chisel

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
import de.bluecolored.bluemap.gtnh.render.mods.MeshQuad
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModBlockRenderer

/**
 * `gtnh:chisel_ctm`: Chisel blocks whose texture depends on the position (connected textures, pillars,
 * position variation, random tiles; see [ChiselType]) from the exporter's `chisel.json`. Everything else (and
 * blocks without data) keeps the variant's model, which shows the base tile.
 */
class ChiselCtmRenderer(resourcePack: ResourcePack, textureGallery: TextureGallery, renderSettings: RenderSettings) :
    ModBlockRenderer(resourcePack, textureGallery, renderSettings) {
    private val indices = IntArray(4)
    private val cells = IntArray(6)
    private val rotations = IntArray(6)

    override fun render(assets: ModAssets, block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color): Boolean {
        val chisel = assets.chisel ?: return false
        val state = block.blockState
        val data = chisel[state.id, meta(block)] ?: return false
        val neighbours = Neighbours { dx, dy, dz -> block.getNeighborBlock(dx, dy, dz).blockState == state }
        when (data.type) {
            ChiselType.CTMX -> for (face in Faces.ALL) {
                ChiselCtm.ctmIndices(face, neighbours, indices)
                for (q in 0 until 4) emit(ctmQuad(data, face, q, indices[q]), block, blockModel, color)
            }
            ChiselType.V9, ChiselType.V4 -> for (face in Faces.ALL) {
                val cell = ChiselCtm.variationCell(data.type.sheetSize, block.x, block.y, block.z, face)
                emit(cellQuad(data, face, cell, 0), block, blockModel, color)
            }
            ChiselType.R16, ChiselType.R9, ChiselType.R4 -> {
                val cell = ChiselCtm.randomCell(data.type.sheetSize, block.x, block.y, block.z)
                for (face in Faces.ALL) emit(cellQuad(data, face, cell, 0), block, blockModel, color)
            }
            ChiselType.CTMH -> for (face in Faces.ALL) {
                emit(cellQuad(data, face, ChiselCtm.horizontalCell(face, neighbours), 0), block, blockModel, color)
            }
            ChiselType.CTMV -> {
                ChiselCtm.column(neighbours, cells, rotations)
                for (face in Faces.ALL) emit(cellQuad(data, face, cells[face.ordinal], rotations[face.ordinal]), block, blockModel, color)
            }
        }
        return true
    }

    /** CTMX quadrant [q] of [face] showing submap [index] (0..15 sheet cell, 16..19 base quarter). */
    private fun ctmQuad(variant: ChiselVariant, face: Direction, q: Int, index: Int): MeshQuad {
        val slot = (face.ordinal * 4 + q) * 20 + index
        variant.quadCache[slot]?.let { return it as MeshQuad }
        val texture: Key
        val cellsPerSide: Int
        val cell: Int
        if (index >= 16) {
            texture = variant.base!!
            cellsPerSide = 2
            cell = index - 16
        } else {
            texture = variant.sheet
            cellsPerSide = 4
            cell = index
        }
        val region = ChiselCtm.QUADRANTS[q]
        val quad = Faces.region(face, ZERO, ONE, region[0], region[1], region[2], region[3], cellTexture(texture, cellsPerSide, cell, 0))
        variant.quadCache[slot] = quad
        return quad
    }

    /** A whole face showing [cell] of the variant's sheet (-1: the top texture), drawn like vanilla `RenderBlocks`. */
    private fun cellQuad(variant: ChiselVariant, face: Direction, cell: Int, rotation: Int): MeshQuad {
        val slot = (face.ordinal * 4 + rotation) * 20 + (cell + 1)
        variant.quadCache[slot]?.let { return it as MeshQuad }
        val texture = if (cell < 0) FaceTexture(variant.top!!) else cellTexture(variant.sheet, variant.type.sheetSize, cell, rotation)
        val quad = Faces.face(face, ZERO, ONE, texture, legacyBottom = true)
        variant.quadCache[slot] = quad
        return quad
    }

    private fun cellTexture(texture: Key, cellsPerSide: Int, cell: Int, rotation: Int): FaceTexture {
        val col = cell % cellsPerSide
        val row = cell / cellsPerSide
        val s = 1f / cellsPerSide
        return FaceTexture(texture, col * s, row * s, (col + 1) * s, (row + 1) * s, rotation)
    }

    private companion object {
        val ZERO = floatArrayOf(0f, 0f, 0f)
        val ONE = floatArrayOf(1f, 1f, 1f)
    }
}
