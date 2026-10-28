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
package de.bluecolored.bluemap.gtnh.render.mods.bibliocraft

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.MeshBuilder
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.ModBlockRenderer
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import java.util.concurrent.ConcurrentHashMap

/**
 * `gtnh:bibliocraft`: BiblioCraft furniture drawn from the mod's own Wavefront models (copied into the pack by the
 * exporter) with the tile entity renderers' placement (`*Angle`, `shifted`, table legs/tops) and the default
 * textures (vanilla planks by wood meta, or the model's texture sheet). The armor stand, a Java model, gets a simple
 * stand of its own. Other BiblioCraft blocks keep the variant's model.
 */
class BiblioCraftRenderer(resourcePack: ResourcePack, textureGallery: TextureGallery, renderSettings: RenderSettings) :
    ModBlockRenderer(resourcePack, textureGallery, renderSettings) {
    override fun render(assets: ModAssets, block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color): Boolean {
        val furniture = Furniture.of(block.blockState.id.value) ?: return false
        val meta = meta(block)
        val te = block.blockEntity as? BiblioTileEntity
        val mesh = if (furniture == Furniture.ARMOR_STAND) {
            // the upper half (meta + 4) is drawn by the lower one
            if (meta >= 4) return true
            armorStand(meta)
        } else {
            mesh(assets, furniture, meta, te ?: return false) ?: return false
        }
        emit(mesh, block, blockModel, color)
        return true
    }

    private fun mesh(assets: ModAssets, furniture: Furniture, meta: Int, te: BiblioTileEntity): Mesh? {
        val key = MeshKey(furniture, meta, te.stateKey(furniture))
        meshes[key]?.let { return it }
        val built = build(assets, furniture, meta, te) ?: return null
        meshes[key] = built
        return built
    }

    private val meshes = ConcurrentHashMap<MeshKey, Mesh>()
    private val armorStands = ConcurrentHashMap<Int, Mesh>()

    private data class MeshKey(val furniture: Furniture, val meta: Int, val state: List<Int>)

    private fun build(assets: ModAssets, furniture: Furniture, meta: Int, te: BiblioTileEntity): Mesh? {
        val obj = assets.obj(furniture.obj) ?: return null
        val wood = wood(assets, meta)
        val angle = te.angle(furniture)
        val degrees = ANGLE_DEGREES.getOrElse(angle and 3) { 0f }
        val (ish, ksh) = if (te.shifted != 0) SHIFTS[angle and 3] else ZERO_SHIFT
        fun t(x: Float, y: Float, z: Float) = Transform.translate(x, y, z)
        fun ry(d: Float) = Transform.rotate(d, 0f, 1f, 0f)
        fun rx(d: Float) = Transform.rotate(d, 1f, 0f, 0f)
        fun rz(d: Float) = Transform.rotate(d, 0f, 0f, 1f)
        return when (furniture) {
            Furniture.BOOKCASE ->
                obj.mesh(wood).transformed(Transform.gl(t(0.5f + ish, 0.5f, 0.5f + ksh), ry(degrees), rx(-90f), rz(-90f)))
            Furniture.SHELF -> {
                val (xc, zc) = SHELF_OFFSETS[angle and 3]
                val groups = if (te.rendersTop != 0) listOf("shelf_bottom", "shelf_top") else listOf("shelf_bottom")
                obj.mesh(wood, groups).transformed(Transform.gl(t(0.5f + xc + ish, 1f, 0.5f + zc + ksh), ry(degrees + 180f), rx(180f), rx(90f)))
            }
            Furniture.POTION_SHELF ->
                obj.mesh(wood).transformed(
                    Transform.gl(t(0.5f + ish, 0.5f - 0.03125f, 0.5f + ksh), ry(degrees), rx(180f), rx(90f), rz(-90f), t(0f, 0f, 0.031f)),
                )
            Furniture.TOOL_RACK ->
                obj.mesh(wood).transformed(Transform.gl(t(0.5f + ish, 1.5f, 0.5f + ksh), ry(degrees + 180f), rx(180f), ry(90f), rx(90f)))
            Furniture.LABEL -> {
                val (lx, lz) = LABEL_POSITIONS[angle and 3]
                obj.mesh(wood).transformed(Transform.gl(t(lx, 0.3f, lz), ry(degrees), rx(180f), rx(-90f), rz(-90f)))
            }
            Furniture.CASE -> {
                val place = when (angle) {
                    4 -> Transform.gl(t(-0.5f, 0.5f, 0.5f), ry(degrees), rx(-90f))
                    5 -> Transform.gl(t(0.5f, 0.5f, -0.5f), ry(degrees), rx(-90f))
                    6 -> Transform.gl(t(1.5f, 0.5f, 0.5f), ry(degrees), rx(-90f))
                    7 -> Transform.gl(t(0.5f, 0.5f, 1.5f), ry(degrees), rx(-90f))
                    else -> Transform.gl(t(0.5f, 1.5f, 0.5f), ry(degrees + 90f), rx(180f))
                }
                val glass = Key.minecraft("block/glass")
                val iron = Key.minecraft("block/iron_block")
                (obj.mesh(wood, listOf("case_bottom", "case_lid_wood")) + obj.mesh(glass, listOf("case_lid_glass")) + obj.mesh(iron, listOf("case_lid_latch")))
                    .transformed(Transform.gl(place, rx(90f), rz(180f)))
            }
            Furniture.TABLE -> {
                val sheet = assets.texture(tableTexture(meta)) ?: wood
                val groups = ArrayList<String>()
                val legs = intArrayOf(te.leg1, te.leg2, te.leg3, te.leg4)
                val tops = intArrayOf(te.top1, te.top2, te.top3, te.top4)
                for (i in 0 until 4) {
                    if (legs[i] != 0) groups.add("leg0${i + 1}")
                    groups.add((if (tops[i] != 0) "tableBevel0" else "tableSquare0") + (i + 1))
                }
                if (te.monoleg != 0) groups.add("monoleg")
                obj.mesh(sheet, groups).transformed(t(0.5f, 0f, 0.5f))
            }
            Furniture.ARMOR_STAND -> null
        }
    }

    /** A simple stand (BiblioCraft's own is a Java model): stone base, oak pole and shoulder bar, two blocks high. */
    private fun armorStand(meta: Int): Mesh = armorStands.computeIfAbsent(meta and 3) { facing ->
        val planks = Key.minecraft("block/oak_planks")
        val stone = Key.minecraft("block/stone")
        MeshBuilder()
            .box(2f, 0f, 2f, 14f, 1f, 14f, stone)
            .box(7f, 1f, 7f, 9f, 27f, 9f, planks)
            .box(2f, 21f, 7f, 14f, 23f, 9f, planks)
            .box(4f, 13f, 7.5f, 12f, 14f, 8.5f, planks)
            .box(6.5f, 27f, 6.5f, 9.5f, 30f, 9.5f, planks)
            .build()
            .transformed(Transform.rotateYCentered(ANGLE_DEGREES[facing]))
    }

    /** The planks texture of a BiblioCraft wood meta (oak, spruce, birch, jungle, acacia, dark oak, framed). */
    private fun wood(assets: ModAssets, meta: Int): Key = when (meta) {
        1 -> Key.minecraft("block/spruce_planks")
        2 -> Key.minecraft("block/birch_planks")
        3 -> Key.minecraft("block/jungle_planks")
        4 -> Key.minecraft("block/acacia_planks")
        5 -> Key.minecraft("block/dark_oak_planks")
        6 -> assets.texture(FRAME) ?: Key.minecraft("block/oak_planks")
        else -> Key.minecraft("block/oak_planks")
    }

    enum class Furniture(val obj: String, val angleField: String?) {
        BOOKCASE(model("OBJBookcase"), "bookcaseAngle"),
        SHELF(model("OBJShelf"), "genericShelfAngle"),
        POTION_SHELF(model("OBJPotionShelf"), "potionshelfAngle"),
        TOOL_RACK(model("OBJToolRack"), "rackAngle"),
        LABEL(model("OBJLabel"), "labelAngle"),
        CASE(model("OBJCase"), "caseAngle"),
        TABLE(model("OBJModelTable"), null),
        ARMOR_STAND("", null),
        ;

        companion object {
            private val BY_BLOCK = mapOf(
                "~bibliotheca" to BOOKCASE,
                "~bookcase~filled" to BOOKCASE,
                "~biblio~shelf" to SHELF,
                "~biblio~potion~shelf" to POTION_SHELF,
                "~biblio~rack" to TOOL_RACK,
                "~biblio~label" to LABEL,
                "~biblio~case" to CASE,
                "~biblio~table" to TABLE,
                "~armor!20~stand" to ARMOR_STAND,
            )

            /** By the normalized block name (`BiblioCraft:Bibliotheca` -> `~bibliotheca`). */
            fun of(blockName: String): Furniture? = BY_BLOCK[blockName]
        }
    }

    companion object {
        private fun model(name: String) = "bibliocraft:models/$name.obj"

        const val FRAME = "bibliocraft:textures/blocks/frame.png"

        /** `*Angle` 0..3 -> the TESRs' `degreeAngle`. */
        private val ANGLE_DEGREES = floatArrayOf(270f, 180f, 90f, 0f)

        /** `shifted` furniture moves half a block back (ishift, kshift by angle). */
        private val SHIFTS = arrayOf(-0.5f to 0f, 0f to -0.5f, 0.5f to 0f, 0f to 0.5f)
        private val ZERO_SHIFT = 0f to 0f

        /** Generic shelf: `xchange`, `zchange` by angle. */
        private val SHELF_OFFSETS = arrayOf(0.5f to 0f, 0f to 0.5f, -0.5f to 0f, 0f to -0.5f)

        /** Label: its position on the block side by angle. */
        private val LABEL_POSITIONS = arrayOf(0.99f to 0.5f, 0.5f to 0.99f, 0.01f to 0.5f, 0.5f to 0.01f)

        private val TABLE_TEXTURES = listOf("table0", "table1", "table2", "table3", "table_v17_acacia", "table_v17_oldoak")

        fun tableTexture(meta: Int): String =
            if (meta == 6) FRAME else "bibliocraft:textures/models/${TABLE_TEXTURES.getOrElse(meta) { "table0" }}.png"

        val RAW_TEXTURES: Set<String> = TABLE_TEXTURES.mapTo(HashSet()) { "bibliocraft:textures/models/$it.png" } + FRAME
    }
}
