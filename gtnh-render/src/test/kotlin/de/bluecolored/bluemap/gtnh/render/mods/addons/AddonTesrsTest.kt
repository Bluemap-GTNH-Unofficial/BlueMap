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
package de.bluecolored.bluemap.gtnh.render.mods.addons

import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.gtnh.legacy.LegacyBlockEntities
import de.bluecolored.bluemap.gtnh.render.GtnhRender
import de.bluecolored.bluemap.gtnh.render.mods.ModTestKit
import de.bluecolored.bluemap.gtnh.render.mods.ModTestKit.Cell
import de.bluecolored.bluemap.gtnh.render.mods.ModTestKit.Companion.state
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import de.bluecolored.bluenbt.NBTWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/** Addon TESR ports (TST, Box++, 123Technology, the shared laser beacon): tile entity NBT and the geometry it selects. */
class AddonTesrsTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun register() = GtnhRender.register()

        /** Serializes a tile entity compound and reads it back like a 1.7 chunk's `TileEntities` entry. */
        fun te(id: String, fields: NBTWriter.() -> Unit = {}): BlockEntity? {
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

        /** A closed tetrahedron-free test model: one quad at y = 1 spanning x, z in -1..1 (model units). */
        const val QUAD_OBJ = "v -1 1 -1\nv -1 1 1\nv 1 1 1\nv 1 1 -1\nvt 0 0\nvt 0 1\nvt 1 1\nvt 1 0\no quad\nf 1/1 2/2 3/3 4/4\n"
    }

    @TempDir
    lateinit var root: Path

    /** A kit whose `test:block` (meta 0..5) uses `gtnh:addon_tesr` over a stone cube, with the given raw assets. */
    private fun kit(vararg raw: Pair<String, String>): ModTestKit {
        val kit = ModTestKit(root)
        kit.file("gtnh/mods/assets.json", raw.joinToString(",", """{"format": 1, "assets": {""", "}}") { (k, v) -> """"$k": "$v"""" })
        kit.png("assets/minecraft/textures/block/stone.png", 0x808080)
        kit.cubeModel("assets/test/models/block/cube.json", "minecraft:block/stone")
        val variants = (0..5).joinToString(",") { """"meta=$it": {"renderer": "gtnh:addon_tesr", "model": "test:block/cube"}""" }
        kit.file("assets/test/blockstates/block.json", """{"variants": {$variants}}""")
        return kit
    }

    private fun ModTestKit.texture(raw: String, path: String, rgb: Int = 0xFFFFFF): Pair<String, String> {
        png(path, rgb)
        return raw to path
    }

    private fun ModTestKit.obj(raw: String, path: String, content: String = QUAD_OBJ): Pair<String, String> {
        file(path, content)
        return raw to path
    }

    private fun maxOf(tris: List<ModTestKit.Triangle>, axis: Int) = tris.maxOf { t -> (0 until 3).maxOf { t.positions[it * 3 + axis] } }

    private fun minOf(tris: List<ModTestKit.Triangle>, axis: Int) = tris.minOf { t -> (0 until 3).minOf { t.positions[it * 3 + axis] } }

    @Test
    fun `every port's tile entity id reads into its own tile entity class`() {
        val expected = mapOf(
            "StarRender" to TstStarTileEntity::class.java,
            "EyeOfWoodRender" to PlainTileEntity::class.java,
            "TilePowerChair" to PlainTileEntity::class.java,
            "TileArcaneHole" to PlainTileEntity::class.java,
            "MeteorMinerRenderer" to GtLaserTileEntity::class.java,
            "laser_beacon_tile_entity" to GtLaserTileEntity::class.java,
            "BoxRing" to BoxRingTileEntity::class.java,
            "RocketRender" to OthRocketTileEntity::class.java,
            "modelAsuka" to PlainTileEntity::class.java,
            "modelAyanami" to PlainTileEntity::class.java,
            "modelTallHat" to PlainTileEntity::class.java,
        )
        for ((id, type) in expected) {
            assertEquals(type, te(id)?.javaClass, id)
            assertTrue(AddonTesrs.byId.containsKey(Key.minecraft(id)), id)
        }
        // ids are unique across the ports
        assertEquals(AddonTesrs.ALL.sumOf { it.ids.size }, AddonTesrs.byId.size)
    }

    @Test
    fun `artificial star size comes from the misnamed renderStatus, default 4, scaling Sol about the block centre`() {
        assertEquals(2.5f, TstTesrs.Star.size(te("StarRender") { name("renderStatus").value(2.5) } as TstStarTileEntity))
        assertEquals(4f, TstTesrs.Star.size(te("StarRender") as TstStarTileEntity))
        // a reloaded star saves 0: the client still draws its default size
        assertEquals(4f, TstTesrs.Star.size(te("StarRender") { name("renderStatus").value(0.0) } as TstStarTileEntity))

        val kit = kit()
        kit.file(
            "gtnh/mods/assets.json",
            """{"format": 1, "assets": {"${TstTesrs.Star.MODEL}": "assets/gtnhcommunitymod/models/sol.obj", "${TstTesrs.Star.TEXTURE}": "assets/gtnhcommunitymod/textures/model/star.png"}}""",
        )
        kit.file("assets/gtnhcommunitymod/models/sol.obj", QUAD_OBJ)
        kit.png("assets/gtnhcommunitymod/textures/model/star.png", 0xFFAA00)
        val tris = kit.render(Cell(state("test:block", 0), te("StarRender") { name("renderStatus").value(2.0) })).triangles
        assertEquals(2, tris.size)
        assertTrue(tris.all { it.texture == kit.id(Key("gtnhcommunitymod", "model/star")) && it.blockLight == 15 })
        // the quad at model y = 1, x -1..1 scaled by 2 about (0.5, 0.5, 0.5)
        assertEquals(2.5f, maxOf(tris, 1), 1e-4f)
        assertEquals(-1.5f, minOf(tris, 0), 1e-4f)
        assertEquals(2.5f, maxOf(tris, 0), 1e-4f)
    }

    @Test
    fun `laser beacon reads GT's laser fields, draws only its block while idle and a coloured column while mining`() {
        val laser = te("laser_beacon_tile_entity") {
            name("rgb_red").value(1f); name("rgb_green").value(0f); name("rgb_blue").value(0.5f)
            name("shouldRender").value(1.toByte()); name("range").value(10.0)
            name("rotAxisX").value(1.0); name("rotAxisY").value(0.0); name("rotAxisZ").value(0.0); name("rotationAngle").value(90.0)
        } as GtLaserTileEntity
        assertTrue(laser.shouldRender)
        assertEquals(10.0, laser.range)
        assertEquals(90.0, laser.rotationAngle)
        assertEquals(0.5f, laser.blue)

        val kit = kit(ModTestKit(root).texture(AddonTesrs.WHITE, "assets/gtnh/textures/misc/white.png"))
        val stone = kit.id(Key.minecraft("block/stone"))
        val white = kit.id(Key("gtnh", "misc/white"))

        val idle = kit.render(Cell(state("test:block", 0), te("MeteorMinerRenderer") { name("range").value(10.0) })).triangles
        assertEquals(12, idle.size)
        assertTrue(idle.all { it.texture == stone })

        val upright = kit.render(Cell(state("test:block", 0), te("MeteorMinerRenderer") {
            name("rgb_red").value(1f); name("shouldRender").value(1.toByte()); name("range").value(10.0)
        })).triangles
        val beam = upright.filter { it.texture == white }
        assertEquals(12, upright.size - beam.size)
        // 5 faces (no bottom), both sides
        assertEquals(20, beam.size)
        assertTrue(beam.all { it.rgb == Triple(1f, 0f, 0f) && it.blockLight == 15 })
        assertEquals(10f, maxOf(beam, 1), 1e-4f)
        assertEquals(0.5f, minOf(beam, 1), 1e-4f)
        assertEquals(0.25f, minOf(beam, 0), 1e-4f)
        assertEquals(0.75f, maxOf(beam, 0), 1e-4f)

        // a controller facing another way turns the column about the block centre: 90° about +x sends +y to +z
        val turned = kit.render(Cell(state("test:block", 0), laser)).triangles.filter { it.texture == white }
        assertEquals(10f, maxOf(turned, 2), 1e-4f)
        assertEquals(0.75f, maxOf(turned, 1), 1e-4f)
        assertTrue(turned.all { it.rgb.first == 1f && it.rgb.third == 128 / 255f })
    }

    @Test
    fun `power chair placement follows block meta`() {
        fun origin(meta: Int): FloatArray = FloatArray(3).also { TstTesrs.PowerChair.placement(meta).apply(floatArrayOf(0f, 0f, 0f), 0, it, 0) }
        fun forward(meta: Int): FloatArray = FloatArray(3).also {
            val t = TstTesrs.PowerChair.placement(meta)
            val o = origin(meta)
            t.apply(floatArrayOf(0f, 0f, 1f), 0, it, 0)
            for (i in 0 until 3) it[i] -= o[i]
        }
        assertEquals(listOf(0.2f, 0f, 0.5f), origin(0).toList())
        assertEquals(listOf(0.8f, 0f, 0.5f), origin(1).toList())
        assertEquals(listOf(0.5f, 0f, 0.2f), origin(2).toList())
        assertEquals(listOf(0.5f, 0f, 0.8f), origin(3).toList())
        // model +z: east for meta 0 (glRotated 90 about y), west for 1, south for 2, north for 3
        assertEquals(listOf(1f, 0f, 0f), forward(0).toList())
        assertEquals(listOf(-1f, 0f, 0f), forward(1).toList())
        assertEquals(listOf(0f, 0f, 1f), forward(2).toList())
        assertEquals(listOf(0f, 0f, -1f), forward(3).toList())
    }

    @Test
    fun `arcane hole draws a grey plane inside each opaque neighbour's face, facing into the hole`() {
        val kit = kit(ModTestKit(root).texture(TstTesrs.ArcaneHole.TEXTURE, "assets/thaumcraft/textures/misc/particlefield32.png"))
        kit.file("assets/test/blockstates/stone.json", """{"variants": {"meta=0": {"model": "test:block/cube"}}}""")
        val stone = Cell(state("test:stone", 0))
        val hole = kit.render(
            Cell(state("test:block", 0), te("TileArcaneHole")),
            Triple(0, -1, 0) to stone,
            Triple(1, 0, 0) to stone,
        )
        // the hole's own block model is not drawn (its icon is blank); only the TESR planes
        assertTrue(hole.triangles.all { it.texture == kit.id(Key("thaumcraft", "misc/particlefield32")) })
        val facings = hole.triangles.map { it.facing }.toSet()
        assertTrue(facings.isNotEmpty())
        assertTrue(facings.all { it == Triple(0, 1, 0) || it == Triple(-1, 0, 0) }, facings.toString())

        // the plane geometry itself: up plane at y 0.999 facing down
        val up = TstTesrs.ArcaneHole.plane(Direction.UP, Key("t", "x"))
        assertEquals(Direction.DOWN, up.facing)
        assertTrue((0 until 4).all { up.positions[it * 3 + 1] == 0.999f })
        for (face in Direction.entries) assertEquals(face.opposite, TstTesrs.ArcaneHole.plane(face, Key("t", "x")).facing)
    }

    @Test
    fun `box ring draws the core ring always and 1 to 3 big rings on a formed encapsulator`() {
        assertEquals(listOf(BoxTesrs.Ring.CORE_SCALE), (te("BoxRing") { name("renderStatus").value(0.toByte()) } as BoxRingTileEntity).ringScales())
        val formed = te("BoxRing") {
            name("renderStatus").value(1.toByte()); name("switch").value(1.toByte()); name("scale").value(2f); name("count").value(3)
        } as BoxRingTileEntity
        val scales = formed.ringScales()
        assertEquals(4, scales.size)
        listOf(2.2f, 2.8f, 3.6f, BoxTesrs.Ring.CORE_SCALE).zip(scales).forEach { (e, a) -> assertEquals(e, a, 1e-5f) }
        val switchedOff = te("BoxRing") { name("renderStatus").value(1.toByte()); name("switch").value(0.toByte()); name("count").value(2) } as BoxRingTileEntity
        assertEquals(1, switchedOff.ringScales().size)
        val two = te("BoxRing") { name("renderStatus").value(1.toByte()); name("switch").value(1.toByte()); name("scale").value(1f); name("count").value(2) } as BoxRingTileEntity
        assertEquals(3, two.ringScales().size)

        val kit = kit(
            ModTestKit(root).obj(BoxTesrs.Ring.MODEL, "assets/boxplusplus/models/~ring.obj"),
            ModTestKit(root).texture(BoxTesrs.Ring.TEXTURE, "assets/boxplusplus/textures/models/ring.png"),
        )
        val tris = kit.render(Cell(state("test:block", 0), two)).triangles
        // 3 rings × 1 quad × 2 sides × 2 triangles, full bright; the biggest (1.4) reaches 0.5 + 1.4
        assertEquals(12, tris.size)
        assertTrue(tris.all { it.blockLight == 15 })
        assertEquals(1.9f, maxOf(tris, 0), 1e-4f)
    }

    @Test
    fun `123Technology rocket size and figures turn by meta`() {
        assertEquals(1f, OthTesrs.Rocket.size(te("RocketRender") as OthRocketTileEntity))
        assertEquals(1.5f, OthTesrs.Rocket.size(te("RocketRender") { name("renderStatus").value(1.5) } as OthRocketTileEntity))
        assertEquals(listOf(0f, 0f, 180f, 0f, 90f, -90f), (0..5).map(OthTesrs.Rocket::turn))
        assertEquals(listOf(0f, 0f, 0f, 180f, 90f, -90f), (0..5).map(OthTesrs.Figure::turn))

        val kit = kit(
            ModTestKit(root).obj(OthTesrs.ASUKA.model, "assets/123technology/models/~asuka.obj"),
            ModTestKit(root).texture(OthTesrs.ASUKA.texture, "assets/123technology/textures/models/asuka~texture.png"),
        )
        // meta 3: turned 180°, standing 0.1 above the floor (model y = 1 -> 1.1), normally lit
        val tris = kit.render(Cell(state("test:block", 3), te("modelAsuka"))).triangles
        assertEquals(2, tris.size)
        assertEquals(1.1f, maxOf(tris, 1), 1e-4f)
        assertTrue(tris.all { it.blockLight == 0 })
        // the quad's uv (0,0) corner sits at model (-1, -1) -> turned to (1.5, 1.5)
        val transform = Transform.gl(Transform.translate(0.5f, 0.1f, 0.5f), Transform.rotate(180f, 0f, 1f, 0f))
        val corner = FloatArray(3).also { transform.apply(floatArrayOf(-1f, 1f, -1f), 0, it, 0) }
        assertEquals(listOf(1.5f, 1.1f, 1.5f), corner.toList())
        assertTrue(tris.any { t -> (0 until 3).any { t.positions[it * 3] == 1.5f && t.positions[it * 3 + 2] == 1.5f } })
    }

    @Test
    fun `missing assets or tile entity fall back to the block model`() {
        val kit = kit()
        val stone = kit.id(Key.minecraft("block/stone"))
        assertTrue(kit.render(Cell(state("test:block", 0), te("StarRender"))).triangles.all { it.texture == stone })
        assertTrue(kit.render(Cell(state("test:block", 0))).triangles.all { it.texture == stone })
        assertEquals(12, kit.render(Cell(state("test:block", 0), te("BoxRing"))).triangles.size)
    }
}
