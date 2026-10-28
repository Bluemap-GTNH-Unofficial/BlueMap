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
package de.bluecolored.bluemap.gtnh.render.mods

import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.gtnh.legacy.LegacyBlockEntities
import de.bluecolored.bluemap.gtnh.render.GtnhRender
import de.bluecolored.bluemap.gtnh.render.mods.ModTestKit.Cell
import de.bluecolored.bluemap.gtnh.render.mods.ModTestKit.Companion.state
import de.bluecolored.bluemap.gtnh.render.mods.ae2.Ae2Cable
import de.bluecolored.bluemap.gtnh.render.mods.ae2.Ae2CableBusTileEntity
import de.bluecolored.bluemap.gtnh.render.mods.ae2.Ae2Part
import de.bluecolored.bluemap.gtnh.render.mods.bibliocraft.BiblioTileEntity
import de.bluecolored.bluemap.gtnh.render.mods.ctm.MyCtm
import de.bluecolored.bluemap.gtnh.render.mods.enderio.EioConduitBundleTileEntity
import de.bluecolored.bluemap.gtnh.render.mods.ironchest.IronChestRenderer
import de.bluecolored.bluemap.gtnh.render.mods.ironchest.IronChestTileEntity
import de.bluecolored.bluenbt.NBTWriter
import de.bluecolored.bluenbt.TagType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/** Tile entity NBT (as the mods write it, read through the legacy loader's BlueNBT) and the geometry chosen from it. */
class ModTileEntityRenderersTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun register() = GtnhRender.register()

        /** Serializes a tile entity compound and reads it back like a 1.7 chunk's `TileEntities` entry. */
        fun te(id: String, fields: NBTWriter.() -> Unit): BlockEntity? {
            val out = ByteArrayOutputStream()
            NBTWriter(out).use { w ->
                w.beginCompound()
                w.name("id").value(id)
                w.name("x").value(1)
                w.name("y").value(65)
                w.name("z").value(1)
                w.fields()
                w.endCompound()
            }
            return LegacyBlockEntities.blueNbt().read(ByteArrayInputStream(out.toByteArray()), BlockEntity::class.java)
        }

        fun NBTWriter.stack(name: String, id: Int, damage: Int) {
            name(name).beginCompound()
            name("id").value(id.toShort())
            name("Count").value(1.toByte())
            name("Damage").value(damage.toShort())
            endCompound()
        }
    }

    @TempDir
    lateinit var root: Path

    private fun ModTestKit.assets(vararg raw: Pair<String, String>) {
        file("gtnh/mods/assets.json", raw.joinToString(",", """{"format": 1, "assets": {""", "}}") { (k, v) -> """"$k": "$v"""" })
    }

    @Test
    fun `iron chest facing from NBT turns the chest, type texture from meta`() {
        val chest = te("IronChest.GOLD") { name("facing").value(5.toByte()) } as IronChestTileEntity
        assertEquals(5, chest.facing)

        val kit = ModTestKit(root)
        kit.png("assets/ironchest/textures/model/goldchest.png", 0xFFD700, 64, 64)
        kit.assets("ironchest:textures/model/goldchest.png" to "assets/ironchest/textures/model/goldchest.png")
        kit.cubeModel("assets/~iron~chest/models/block/bbox.json", "minecraft:block/stone")
        kit.file("assets/~iron~chest/blockstates/~block~iron~chest.json", """{"variants": {"meta=1": {"renderer": "gtnh:iron_chest", "model": "~iron~chest:block/bbox"}}}""")
        val tris = kit.render(Cell(state("~iron~chest:~block~iron~chest", 1), chest)).triangles
        // lid, latch and body: 18 faces
        assertEquals(36, tris.size)
        assertTrue(tris.all { it.texture == kit.id(Key("ironchest", "model/goldchest")) })
        // the latch sticks out of the front (east, facing 5) to x = 1
        val maxX = tris.maxOf { t -> (0 until 3).maxOf { t.positions[it * 3] } }
        assertEquals(1f, maxX, 1e-4f)
        // body 1..15 px, lid top at 14 px, every face's normal points out of its box
        assertEquals(14f / 16, tris.maxOf { t -> (0 until 3).maxOf { t.positions[it * 3 + 1] } }, 1e-4f)

        // facing 3 (south, also the default): latch towards +z
        val south = IronChestRenderer.mesh(Key("t", "x"), 3)
        assertEquals(1f, south.quads.maxOf { q -> (0 until 4).maxOf { q.positions[it * 3 + 2] } }, 1e-4f)
    }

    @Test
    fun `bibliocraft furniture fields parse from their tile entities`() {
        val table = te("TableTile") {
            name("leg1").value(1.toByte()); name("leg2").value(0.toByte()); name("monoleg").value(0.toByte())
            name("top1").value(1.toByte()); name("top2").value(0.toByte())
        } as BiblioTileEntity
        assertEquals(listOf(1, 0, 0, 0, 1, 0, 1, 1, 0), table.stateKey(de.bluecolored.bluemap.gtnh.render.mods.bibliocraft.BiblioCraftRenderer.Furniture.TABLE))
        val bookcase = te("BookcaseTile") { name("bookcaseAngle").value(2); name("shifted").value(1.toByte()) } as BiblioTileEntity
        assertEquals(2, bookcase.bookcaseAngle)
        assertEquals(1, bookcase.shifted)
    }

    @Test
    fun `bibliocraft draws the mod's Wavefront model with planks by wood meta and the TESR's turn`() {
        val kit = ModTestKit(root)
        // a unit quad in the bookcase's model space (x -0.5..0, like OBJBookcase)
        kit.file(
            "assets/bibliocraft/models/~o~b~j~bookcase.obj",
            "v -0.5 -0.5 -0.5\nv 0 -0.5 -0.5\nv 0 0.5 -0.5\nv -0.5 0.5 -0.5\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\ng bookcase\nf 1/1 2/2 3/3 4/4\n",
        )
        kit.assets("bibliocraft:models/OBJBookcase.obj" to "assets/bibliocraft/models/~o~b~j~bookcase.obj")
        kit.png("assets/minecraft/textures/block/spruce_planks.png", 0x806030)
        kit.cubeModel("assets/~biblio~craft/models/block/bbox.json", "minecraft:block/spruce_planks")
        kit.file("assets/~biblio~craft/blockstates/~bibliotheca.json", """{"variants": {"meta=1": {"renderer": "gtnh:bibliocraft", "model": "~biblio~craft:block/bbox"}}}""")
        val spruce = kit.id(Key.minecraft("block/spruce_planks"))
        for (angle in 0..3) {
            val bookcase = te("BookcaseTile") { name("bookcaseAngle").value(angle) }
            val tris = kit.render(Cell(state("~biblio~craft:~bibliotheca", 1), bookcase)).triangles
            assertEquals(2, tris.size, "angle $angle")
            assertTrue(tris.all { it.texture == spruce })
            for (t in tris) for (i in 0 until 9) assertTrue(t.positions[i] in -1e-4f..1.0001f, "angle $angle stays in the block")
        }
        // without a tile entity the variant's model (6 faces) is drawn
        assertEquals(12, kit.render(Cell(state("~biblio~craft:~bibliotheca", 1))).triangles.size)
    }

    @Test
    fun `AE2 cable bus parts by side, cable type and colour from the ItemMultiPart damage`() {
        val bus = te("BlockCableBus") {
            stack("def:6", 4632, 36)
            stack("def:2", 4632, 380)
            stack("def:4", 9999, 7)
            name("extra:6").beginCompound()
            endCompound()
        } as Ae2CableBusTileEntity
        assertEquals(Ae2Cable(Ae2Cable.Type.COVERED, Ae2Cable.FLUIX), bus.cable)
        assertEquals(Ae2Part.Kind.DISPLAY, bus.part(2)!!.kind)
        assertEquals(Ae2Part.Kind.UNKNOWN, bus.part(4)!!.kind, "another item id is an addon's part")
        assertEquals(null, bus.part(3))
        assertEquals(Ae2Cable(Ae2Cable.Type.DENSE, 4), Ae2Cable.of(64))
        assertTrue(Ae2Cable(Ae2Cable.Type.GLASS, 3).connectsTo(Ae2Cable(Ae2Cable.Type.SMART, Ae2Cable.FLUIX)))
        assertTrue(!Ae2Cable(Ae2Cable.Type.GLASS, 3).connectsTo(Ae2Cable(Ae2Cable.Type.GLASS, 4)))
        assertEquals(listOf("MECable_Gray", "MECable_Grey"), Ae2Cable.textureNames(Ae2Cable.Type.GLASS, 7))
        assertEquals(listOf("ItemPart.CableGlass"), Ae2Cable.textureNames(Ae2Cable.Type.GLASS, Ae2Cable.FLUIX))
    }

    @Test
    fun `AE2 cables connect to compatible neighbours without a part in between`() {
        val kit = ModTestKit(root)
        kit.png("assets/appliedenergistics2/textures/block/~m~e~cable_~red.png", 0xA00000)
        kit.png("assets/appliedenergistics2/textures/block/~item~part.~cable~glass.png", 0x8060A0)
        kit.assets(
            "appliedenergistics2:textures/blocks/MECable_Red.png" to "assets/appliedenergistics2/textures/block/~m~e~cable_~red.png",
            "appliedenergistics2:textures/blocks/ItemPart.CableGlass.png" to "assets/appliedenergistics2/textures/block/~item~part.~cable~glass.png",
        )
        kit.cubeModel("assets/appliedenergistics2/models/block/bbox.json", "minecraft:block/stone")
        kit.file("assets/appliedenergistics2/blockstates/tile.~block~cable~bus.json", """{"variants": {"": {"renderer": "gtnh:ae2_cable_bus", "model": "appliedenergistics2:block/bbox"}}}""")
        val busState = state("appliedenergistics2:tile.~block~cable~bus", 0)
        val red = te("BlockCableBus") { stack("def:6", 1, 14) }
        val fluix = te("BlockCableBus") { stack("def:6", 1, 16) }
        val blue = te("BlockCableBus") { stack("def:6", 1, 11) }

        fun extent(tris: List<ModTestKit.Triangle>) = tris.maxOf { t -> (0 until 3).maxOf { t.positions[it * 3] } }
        // alone: just the 4-px core
        assertEquals(10f / 16, extent(kit.render(Cell(busState, red)).triangles), 1e-4f)
        // fluix neighbour to the east: arm to the block edge
        assertEquals(1f, extent(kit.render(Cell(busState, red), Triple(1, 0, 0) to Cell(busState, fluix)).triangles), 1e-4f)
        // a differently coloured cable does not connect
        assertEquals(10f / 16, extent(kit.render(Cell(busState, red), Triple(1, 0, 0) to Cell(busState, blue)).triangles), 1e-4f)
        // ... nor does a compatible one with a part on the facing side (west = ForgeDirection 4)
        val blocked = te("BlockCableBus") { stack("def:6", 1, 16); stack("def:4", 1, 380) }
        assertEquals(10f / 16, extent(kit.render(Cell(busState, red), Triple(1, 0, 0) to Cell(busState, blocked)).triangles), 1e-4f)
        assertTrue(kit.render(Cell(busState, red)).triangles.all { it.texture == kit.id(Key("appliedenergistics2", "block/~m~e~cable_~red")) })
    }

    @Test
    fun `EnderIO conduit list with connections, one arm per connection`() {
        val bundle = te("blockConduitBundleTileEntity") {
            name("conduits").beginList(1, TagType.COMPOUND)
            beginCompound()
            name("conduitType").value("crazypants.enderio.conduit.item.ItemConduit")
            name("conduit").beginCompound()
            name("connections").value(intArrayOf(5))
            name("externalConnections").value(intArrayOf(1))
            endCompound()
            endCompound()
            endList()
        } as EioConduitBundleTileEntity
        assertEquals(1, bundle.conduits.size)
        assertArrayEquals(intArrayOf(5), bundle.conduits[0].conduit!!.connections)

        val kit = ModTestKit(root)
        kit.png("assets/enderio/textures/block/item~conduit.png", 0x404040)
        kit.png("assets/enderio/textures/block/item~conduit~core.png", 0x808080)
        kit.assets(
            "enderio:textures/blocks/itemConduit.png" to "assets/enderio/textures/block/item~conduit.png",
            "enderio:textures/blocks/itemConduitCore.png" to "assets/enderio/textures/block/item~conduit~core.png",
        )
        kit.cubeModel("assets/~ender~i~o/models/block/bbox.json", "minecraft:block/stone")
        kit.file("assets/~ender~i~o/blockstates/block~conduit~bundle.json", """{"variants": {"": {"renderer": "gtnh:enderio_conduit", "model": "~ender~i~o:block/bbox"}}}""")
        val tris = kit.render(Cell(state("~ender~i~o:block~conduit~bundle", 0), bundle)).triangles
        // core + east arm + up arm = 3 boxes
        assertEquals(36, tris.size)
        assertEquals(1f, tris.maxOf { t -> (0 until 3).maxOf { t.positions[it * 3] } }, 1e-4f)
        assertEquals(1f, tris.maxOf { t -> (0 until 3).maxOf { t.positions[it * 3 + 1] } }, 1e-4f)
        assertTrue(tris.minOf { t -> (0 until 3).minOf { t.positions[it * 3 + 2] } } > 0.3f, "no arm north/south")
    }

    @Test
    fun `MyCTMLib compact quadrants follow buildConnect`() {
        val none = MyCtm.FaceNeighbours { _, _, _ -> false }
        assertArrayEquals(intArrayOf(17, 18, 19, 20), MyCtm.indices(Direction.SOUTH, none, hasAlt = true))
        val row = MyCtm.FaceNeighbours { dx, dy, dz -> dy == 0 && dz == 0 && (dx == 1 || dx == -1) }
        // south face, left and right connected: TL 9, TR 10, BL 13, BR 14
        assertArrayEquals(intArrayOf(9, 10, 13, 14), MyCtm.indices(Direction.SOUTH, row, hasAlt = false))
        // with an alt sheet, unconnected quarters of a connected face use the alt quarters
        val east = MyCtm.FaceNeighbours { dx, dy, dz -> dx == 1 && dy == 0 && dz == 0 }
        assertArrayEquals(intArrayOf(21, 10, 23, 14), MyCtm.indices(Direction.SOUTH, east, hasAlt = true))
        // the down face uses 1.7's orientation: north is "up" on the texture
        val north = MyCtm.FaceNeighbours { dx, dy, dz -> dx == 0 && dy == 0 && dz == -1 }
        assertArrayEquals(intArrayOf(3, 4, 19, 20), MyCtm.indices(Direction.DOWN, north, hasAlt = false))
    }

    @Test
    fun `MyCTMLib renderer splits the whole-face quads of a CTM texture and keeps the rest`() {
        val kit = ModTestKit(root)
        kit.png("assets/gregtech/textures/block/casing.png", 0x707070)
        kit.png("assets/gregtech/textures/block/casing_ctm.png", 0x505050, 32, 32)
        kit.png("assets/gregtech/textures/block/other.png", 0x303030)
        kit.file(
            "gtnh/mods/myctmlib.json",
            """{"format": 1, "textures": {"gregtech:block/casing": {"method": "compact", "connection": "gregtech:block/casing_ctm"}}}""",
        )
        kit.file(
            "assets/gregtech/models/gtnh_quads/casing/0.json",
            """{"textures": {"t0": "gregtech:block/casing", "t1": "gregtech:block/other"}, "gtnh_quads": [
              {"texture": "#t0", "cullface": "south", "vertices": [[0,16,16,0,0],[0,0,16,0,16],[16,0,16,16,16],[16,16,16,16,0]]},
              {"texture": "#t1", "cullface": "up", "vertices": [[0,16,16,0,16],[16,16,16,16,16],[16,16,0,16,0],[0,16,0,0,0]]}]}""",
        )
        kit.file("assets/gregtech/blockstates/gt.blockcasings.json", """{"variants": {"meta=0": {"renderer": "gtnh:ctm", "model": "gregtech:gtnh_quads/casing/0"}}}""")
        val casing = state("gregtech:gt.blockcasings", 0)
        val alone = kit.render(Cell(casing)).triangles
        // south face as 4 quadrants (8 triangles) + the plain top quad (2)
        assertEquals(10, alone.size)
        assertEquals(2, alone.count { it.texture == kit.id(Key("gregtech", "block/other")) })
        val row = kit.render(Cell(casing), Triple(1, 0, 0) to Cell(casing)).triangles
        assertNotNull(row.firstOrNull { it.texture == kit.id(Key("gregtech", "block/casing_ctm")) }, "connected quarters use the sheet")
    }
}
