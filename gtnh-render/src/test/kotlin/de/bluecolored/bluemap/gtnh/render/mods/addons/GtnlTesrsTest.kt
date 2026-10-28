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

import com.google.gson.JsonParser
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
import de.bluecolored.bluenbt.TagType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt

/** GT: Not Leisure TESR ports: tile entity NBT and the geometry chosen from it. */
class GtnlTesrsTest {
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

        fun former(forward: String, up: String = "UP", hide: Boolean = false) = te("beam_former_tile_entity") {
            name("orientation_forward").value(forward)
            name("orientation_up").value(up)
            name("hideBeam").value((if (hide) 1 else 0).toByte())
        }

        fun cable(color: Int) = te("BlockCableBus") {
            name("def:6").beginCompound()
            name("id").value(1.toShort())
            name("Count").value(1.toByte())
            name("Damage").value(color.toShort())
            endCompound()
        }

        private fun apply(t: Transform, x: Float, y: Float, z: Float): FloatArray = FloatArray(3).also { t.apply(floatArrayOf(x, y, z), 0, it, 0) }

        private fun ModTestKit.Triangle.ys() = (0 until 3).map { positions[it * 3 + 1] }

        private fun ModTestKit.Triangle.zs() = (0 until 3).map { positions[it * 3 + 2] }

        private fun ModTestKit.Triangle.xs() = (0 until 3).map { positions[it * 3] }

        private fun rgb(t: ModTestKit.Triangle): Int =
            (Math.round(t.rgb.first * 255) shl 16) or (Math.round(t.rgb.second * 255) shl 8) or Math.round(t.rgb.third * 255)
    }

    @TempDir
    lateinit var root: Path

    private fun ModTestKit.assets(vararg raw: Pair<String, String>) {
        file("gtnh/mods/assets.json", raw.joinToString(",", """{"format": 1, "assets": {""", "}}") { (k, v) -> """"$k": "$v"""" })
    }

    private fun ModTestKit.addonBlock(name: String, meta: Int = 0) {
        cubeModel("assets/sciencenotleisure/models/block/bbox.json", "minecraft:block/stone")
        file("assets/sciencenotleisure/blockstates/$name.json", """{"variants": {"meta=$meta": {"renderer": "gtnh:addon_tesr", "model": "sciencenotleisure:block/bbox"}}}""")
    }

    // ---- beam former ----

    @Test
    fun `beam former orientation and beam switch parse from AE2's NBT`() {
        val f = former("EAST", "UP", hide = true) as BeamFormerTileEntity
        assertEquals(Direction.EAST, f.forward)
        assertEquals(Direction.UP, f.up)
        assertTrue(f.hideBeam)
        val unknown = former("UNKNOWN") as BeamFormerTileEntity
        assertNull(unknown.forward)
        assertTrue(!unknown.hideBeam)
        assertNull((te("beam_former_tile_entity") {} as BeamFormerTileEntity).forward)
    }

    @Test
    fun `beam former orientation maps the model's north to forward, up to up and east to forward x up`() {
        var pairs = 0
        for (forward in Direction.entries) for (up in Direction.entries) {
            val t = GtnlTesrs.BeamFormer.orientation(forward, up)
            if (forward.axis == up.axis) {
                assertNull(t)
                continue
            }
            pairs++
            assertNotNull(t, "$forward/$up")
            val f = forward.toVector()
            val u = up.toVector()
            val r = intArrayOf(f.y * u.z - f.z * u.y, f.z * u.x - f.x * u.z, f.x * u.y - f.y * u.x)
            val north = apply(t!!, 0.5f, 0.5f, 0f)
            val top = apply(t, 0.5f, 1f, 0.5f)
            val east = apply(t, 1f, 0.5f, 0.5f)
            for (axis in 0 until 3) {
                assertEquals(0.5f + 0.5f * intArrayOf(f.x, f.y, f.z)[axis], north[axis], 1e-5f, "$forward/$up north")
                assertEquals(0.5f + 0.5f * intArrayOf(u.x, u.y, u.z)[axis], top[axis], 1e-5f, "$forward/$up up")
                assertEquals(0.5f + 0.5f * r[axis], east[axis], 1e-5f, "$forward/$up east")
            }
        }
        assertEquals(24, pairs)
        // NORTH / UP is the model as drawn
        val p = apply(GtnlTesrs.BeamFormer.orientation(Direction.NORTH, Direction.UP)!!, 0.2f, 0.3f, 0.4f)
        assertEquals(listOf(0.2f, 0.3f, 0.4f), p.toList().map { Math.round(it * 1e5f) / 1e5f })
    }

    @Test
    fun `JSON model elements follow the vanilla face, uv and rotation semantics`() {
        val key = Key("t", "x")
        val model = JsonParser.parseString(
            """{"textures": {"1": "sciencenotleisure:blocks/beam_former/beam_former_block_model", "a": "#1"}, "elements": [
              {"from": [4, 0, 0], "to": [12, 16, 16], "faces": {"north": {"texture": "#a"}, "up": {"uv": [0, 0, 8, 8], "rotation": 90, "texture": "#1"}}},
              {"from": [0, 0, 7], "to": [16, 16, 9], "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": true},
               "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "#1"}}}
            ]}""",
        ).asJsonObject
        val requested = ArrayList<String>()
        val mesh = JsonModelMesh.mesh(model) { requested.add(it); key }!!
        assertEquals(setOf("sciencenotleisure:blocks/beam_former/beam_former_block_model"), requested.toSet())
        assertEquals("sciencenotleisure:textures/blocks/beam_former/beam_former_block_model.png", JsonModelMesh.rawTexture(requested[0]))
        assertEquals(3, mesh.quads.size)
        val (north, up, rotated) = mesh.quads
        // automatic uv of the north face: u 16 - 12 .. 16 - 4, top-left first
        assertEquals(0.25f, north.uvs[0], 1e-6f)
        assertEquals(0.75f, north.uvs[6], 1e-6f)
        assertEquals(Direction.NORTH, north.facing)
        // uv rotation 90: the top-left corner takes the bottom-left uv
        assertEquals(0f, up.uvs[0], 1e-6f)
        assertEquals(0.5f, up.uvs[1], 1e-6f)
        // 45° about y, rescaled: the face spans the block diagonally, its normal turned to (-√½, 0, -√½)
        val n = ModTestKit.normal(rotated)
        assertEquals(-sqrt(0.5f), n[0], 1e-4f)
        assertEquals(-sqrt(0.5f), n[2], 1e-4f)
        val xs = (0 until 4).map { rotated.positions[it * 3] }
        assertEquals(1f, xs.max() - xs.min(), 1e-4f)
        // a missing texture leaves the model undrawable
        assertNull(JsonModelMesh.mesh(model) { null })
    }

    private fun ModTestKit.beamFormerPack() {
        png("assets/sciencenotleisure/textures/block/beam_former_block_model.png", 0x606060)
        png("assets/minecraft/textures/entity/beacon_beam.png", 0xFFFFFF)
        file(
            "gtnh/mods/models/beam_former.json",
            """{"textures": {"1": "sciencenotleisure:blocks/beam_former/beam_former_block_model"}, "elements": [
              {"from": [4, 4, 1], "to": [12, 12, 16], "faces": {"north": {"uv": [0, 0, 8, 8], "texture": "#1"}}}]}""",
        )
        assets(
            GtnlTesrs.BeamFormer.MODEL to "gtnh/mods/models/beam_former.json",
            GtnlTesrs.BeamFormer.TEXTURE to "assets/sciencenotleisure/textures/block/beam_former_block_model.png",
            GtnlTesrs.BeamFormer.BEAM_TEXTURE to "assets/minecraft/textures/entity/beacon_beam.png",
        )
        addonBlock("beam_former")
        png("assets/minecraft/textures/block/stone.png", 0x7F7F7F)
        cubeModel("assets/minecraft/models/block/stone.json", "minecraft:block/stone")
        file("assets/minecraft/blockstates/stone.json", """{"variants": {"": {"model": "minecraft:block/stone"}}}""")
    }

    @Test
    fun `beam former draws its model turned to forward and the beam to a former facing back`() {
        val kit = ModTestKit(root)
        kit.beamFormerPack()
        val state = state("sciencenotleisure:beam_former", 0)
        val beam = kit.id(Key.minecraft("entity/beacon_beam"))
        val body = kit.id(Key("sciencenotleisure", "block/beam_former_block_model"))

        // alone, facing east: the model's north face looks east, no beam
        val alone = kit.render(Cell(state, former("EAST"))).triangles
        assertEquals(2, alone.size)
        assertTrue(alone.all { it.texture == body && it.facing == Triple(1, 0, 0) })

        for (distance in listOf(1, 5, 8, 17)) {
            val tris = kit.render(Cell(state, former("SOUTH")), Triple(0, 0, distance) to Cell(state, former("NORTH"))).triangles
            val beamTris = tris.filter { it.texture == beam }
            // 4 sides, two-sided
            assertEquals(16, beamTris.size, "distance $distance")
            val zs = beamTris.flatMap { it.zs() }
            assertEquals(0.5f + 3f / 16, zs.min(), 1e-4f)
            assertEquals(distance - 0.375f, zs.max() - zs.min(), 1e-4f, "distance $distance")
            val xs = beamTris.flatMap { it.xs() }
            assertEquals(0.12f * sqrt(0.5f) * 2, xs.max() - xs.min(), 1e-4f)
            assertTrue(beamTris.all { it.blockLight == 15 && rgb(it) == 0x895CA8 }, "full bright fluix")
            assertEquals(1f, beamTris.maxOf { t -> (0 until 3).maxOf { t.uvs[it * 2 + 1] } }, 1e-6f, "one texture stretch")
        }

        fun beamCount(vararg cells: Pair<Triple<Int, Int, Int>, Cell>, center: Cell = Cell(state, former("SOUTH"))) =
            kit.render(center, *cells).triangles.count { it.texture == beam }
        // a former facing the same way does not pair
        assertEquals(0, beamCount(Triple(0, 0, 3) to Cell(state, former("SOUTH"))))
        // an opaque block in between stops the beam; the 256 limit too
        assertEquals(0, beamCount(Triple(0, 0, 2) to Cell(state("minecraft:stone", 0)), Triple(0, 0, 3) to Cell(state, former("NORTH"))))
        assertEquals(0, beamCount(Triple(0, 0, 257) to Cell(state, former("NORTH"))))
        assertEquals(16, beamCount(Triple(0, 0, 256) to Cell(state, former("NORTH"))))
        // the end facing north leaves the beam to the other end
        assertEquals(0, beamCount(Triple(0, 0, -3) to Cell(state, former("SOUTH")), center = Cell(state, former("NORTH"))))
        assertEquals(0, beamCount(Triple(0, 3, 0) to Cell(state, former("UP", "NORTH")), center = Cell(state, former("DOWN", "NORTH"))))
        assertEquals(16, beamCount(Triple(0, 3, 0) to Cell(state, former("DOWN", "NORTH")), center = Cell(state, former("UP", "NORTH"))))
        // hidden on either end
        assertEquals(0, beamCount(Triple(0, 0, 3) to Cell(state, former("NORTH")), center = Cell(state, former("SOUTH", hide = true))))
        assertEquals(0, beamCount(Triple(0, 0, 3) to Cell(state, former("NORTH", hide = true))))
    }

    @Test
    fun `beam colour comes from the cables behind both formers`() {
        val kit = ModTestKit(root)
        kit.beamFormerPack()
        val state = state("sciencenotleisure:beam_former", 0)
        val bus = state("appliedenergistics2:tile.~block~cable~bus", 0)
        val beam = kit.id(Key.minecraft("entity/beacon_beam"))
        fun colors(source: Int?, target: Int?): Set<Int> {
            val cells = ArrayList<Pair<Triple<Int, Int, Int>, Cell>>()
            cells.add(Triple(0, 0, 4) to Cell(state, former("NORTH")))
            if (source != null) cells.add(Triple(0, 0, -1) to Cell(bus, cable(source)))
            if (target != null) cells.add(Triple(0, 0, 5) to Cell(bus, cable(target)))
            return kit.render(Cell(state, former("SOUTH")), *cells.toTypedArray()).triangles.filter { it.texture == beam }.map(::rgb).toSet()
        }
        // red (14) behind the source, nothing (fluix) behind the target
        assertEquals(setOf(0xFF003C), colors(14, null))
        // fluix source takes the target's blue (11)
        assertEquals(setOf(0x514AFF), colors(16, 11))
        // red and blue do not connect
        assertEquals(emptySet<Int>(), colors(14, 11))
        assertEquals(setOf(0x895CA8), colors(null, null))
        assertEquals(14, GtnlTesrs.BeamFormer.beamColor(14, 14))
        assertNull(GtnlTesrs.BeamFormer.beamColor(3, 4))
    }

    // ---- multi essentia jar ----

    private fun jar(active: String, vararg aspects: Pair<String, Int>) = te("multi_essentia_jar_tile_entity") {
        name("StoredAspects").beginCompound()
        name("Aspects").beginList(aspects.size, TagType.COMPOUND)
        for ((key, amount) in aspects) {
            beginCompound()
            name("key").value(key)
            name("amount").value(amount)
            endCompound()
        }
        endList()
        endCompound()
        name("ActiveAspect").value(active)
        name("AspectFilter").value("")
        name("facing").value(2.toByte())
    } as MultiEssentiaJarTileEntity

    @Test
    fun `essentia jar layers stack the aspects by tag with the active one on top`() {
        val jar = jar("aer", "ignis" to 512, "aer" to 1024, "perditio" to 0)
        assertEquals(mapOf("ignis" to 512, "aer" to 1024), jar.amounts())
        val layers = GtnlTesrs.MultiEssentiaJar.layers(jar)
        assertEquals(listOf("ignis", "aer"), layers.map { it.aspect })
        // visible 0.625 * 1536 / 4096 = 0.234375, min layer 1/64, the rest shared by amount
        assertEquals(0.0625, layers[0].bottom, 1e-9)
        assertEquals(0.0625 + 0.015625 + (0.234375 - 2 * 0.015625) * 512 / 1536, layers[0].top, 1e-9)
        assertEquals(layers[0].top, layers[1].bottom, 1e-12)
        assertEquals(0.0625 + 0.234375, layers[1].top, 1e-9)

        // without a valid active aspect the first by tag goes on top; tiny amounts still show a sliver each
        val fallback = GtnlTesrs.MultiEssentiaJar.layers(jar("", "ordo" to 1, "aqua" to 1, "terra" to 1))
        assertEquals(listOf("ordo", "terra", "aqua"), fallback.map { it.aspect })
        assertEquals(0.0625 + 3 * 0.015625, fallback.last().top, 1e-9)
        // a full jar stops at 0.625 of liquid
        assertEquals(0.0625 + 0.625, GtnlTesrs.MultiEssentiaJar.layers(jar("aer", "aer" to 4096)).last().top, 1e-9)
        assertTrue(GtnlTesrs.MultiEssentiaJar.layers(jar("")).isEmpty())
    }

    @Test
    fun `essentia jar draws aspect-coloured liquid boxes over the jar model`() {
        val kit = ModTestKit(root)
        kit.png("assets/thaumcraft/textures/block/animatedglow.png", 0xFFFFFF)
        kit.png("assets/minecraft/textures/block/stone.png", 0x7F7F7F)
        kit.assets(GtnlTesrs.MultiEssentiaJar.TEXTURE to "assets/thaumcraft/textures/block/animatedglow.png")
        kit.file("gtnh/mods/aspects.json", """{"aspects": {"aer": "#FFFF7E", "ignis": "#FF5A01"}}""")
        kit.addonBlock("multi_essentia_jar")
        val tris = kit.render(Cell(state("sciencenotleisure:multi_essentia_jar", 0), jar("aer", "ignis" to 512, "aer" to 1024))).triangles
        val glow = kit.id(Key("thaumcraft", "block/animatedglow"))
        val liquid = tris.filter { it.texture == glow }
        // bottom layer 6 faces, upper layer 5
        assertEquals(22, liquid.size)
        assertEquals(12, tris.count { it.texture == kit.id(Key.minecraft("block/stone")) }, "the jar's own model")
        assertEquals(0.0725f, liquid.minOf { it.ys().min() }, 1e-5f)
        assertEquals(0.0725f + 0.234375f, liquid.maxOf { it.ys().max() }, 1e-5f)
        assertEquals(0.25f, liquid.minOf { it.xs().min() }, 1e-5f)
        assertEquals(0.75f, liquid.maxOf { it.zs().max() }, 1e-5f)
        val top = liquid.filter { it.facing == Triple(0, 1, 0) }
        assertEquals(setOf(0xFF5A01, 0xFFFF7E), top.map(::rgb).toSet())
        assertEquals(0xFFFF7E, rgb(top.maxBy { it.ys().max() }))
        // RenderBlocks uvs: the side shows the sprite part under the layer's bounds
        val side = liquid.first { it.facing == Triple(0, 0, -1) }
        assertTrue((0 until 3).all { side.uvs[it * 2] in 0.2499f..0.7501f })
    }

    // ---- water candle ----

    @Test
    fun `water candle model has four candles and crossed flame planes, unflipped`() {
        val mesh = GtnlTesrs.WaterCandle.build(Key("t", "x"))
        assertEquals(4 * 6 + 8 * 2, mesh.quads.size)
        fun bounds(q: de.bluecolored.bluemap.gtnh.render.mods.MeshQuad, axis: Int) = (0 until 4).map { q.positions[it * 3 + axis] }
        val all = mesh.quads.flatMap { q -> (0 until 4).map { i -> Triple(q.positions[i * 3], q.positions[i * 3 + 1], q.positions[i * 3 + 2]) } }
        assertEquals(0f, all.minOf { it.second }, 1e-5f)
        // tallest candle 6 px, its flame plane 1 px above
        assertEquals(7f / 16, all.maxOf { it.second }, 1e-5f)
        // candle 1: x .5..625, y 0..3/16, z .5..625
        val c1 = mesh.quads.take(6)
        assertEquals(0.5f, c1.minOf { bounds(it, 0).min() }, 1e-5f)
        assertEquals(0.625f, c1.maxOf { bounds(it, 0).max() }, 1e-5f)
        assertEquals(3f / 16, c1.maxOf { bounds(it, 1).max() }, 1e-5f)
        assertEquals(0.5f, c1.minOf { bounds(it, 2).min() }, 1e-5f)
        // flame planes stand diagonally, the first pair above (0.4375, 0.375, 0.375)
        val flames = mesh.quads.drop(24)
        assertTrue(flames.all { q -> ModTestKit.normal(q).let { abs(abs(it[0]) - sqrt(0.5f)) < 1e-3f && abs(it[1]) < 1e-5f } })
        val first = flames.take(4)
        assertEquals(0.375f, first.minOf { bounds(it, 1).min() }, 1e-5f)
        assertEquals(0.4375f, first.map { (bounds(it, 0).min() + bounds(it, 0).max()) / 2 }.average().toFloat(), 1e-4f)
    }

    // ---- player doll ----

    @Test
    fun `player doll draws the slim default parts and faces its meta side`() {
        val kit = ModTestKit(root)
        kit.png("assets/sciencenotleisure/textures/models/skin.png", 0xC08060, 64, 64)
        // a +z face for the head (the doll's face) and parts that must not be drawn
        val quad = "v -0.25 0.5 0.1\nv 0.25 0.5 0.1\nv 0.25 1 0.1\nv -0.25 1 0.1\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n"
        kit.file(
            "gtnh/mods/models/player_doll.obj",
            "o Head\n$quad" + "f 1/1 2/2 3/3 4/4\n" + "o Cape\nf 1/1 2/2 3/3 4/4\n" + "o Left_Arm\nf 1/1 2/2 3/3 4/4\n" +
                "o Left_Arm_Alex\nf 4/4 3/3 2/2 1/1\n",
        )
        kit.assets(
            GtnlTesrs.PlayerDoll.MODEL to "gtnh/mods/models/player_doll.obj",
            GtnlTesrs.PlayerDoll.TEXTURE to "assets/sciencenotleisure/textures/models/skin.png",
        )
        val expected = mapOf(2 to Triple(0, 0, -1), 3 to Triple(0, 0, 1), 4 to Triple(-1, 0, 0), 5 to Triple(1, 0, 0))
        for ((meta, facing) in expected) {
            kit.addonBlock("player_doll", meta)
            val tris = ModTestKit(root).render(Cell(state("sciencenotleisure:player_doll", meta), te("player_doll_tile_entity") {})).triangles
            // head + slim arm only
            assertEquals(4, tris.size, "meta $meta")
            val head = tris.take(2)
            assertTrue(head.all { it.facing == facing }, "meta $meta faces $facing")
        }
    }

    // ---- pigmee fumo ----

    @Test
    fun `pigmee fumo turns its nose to the meta side`() {
        val model = JsonParser.parseString(
            """{"textures": {"0": "sciencenotleisure:blocks/pigmee_fumo"}, "elements": [
              {"from": [6, 3, 1], "to": [10, 6, 2], "faces": {"north": {"uv": [8, 4, 10, 5.5], "texture": "#0"}}}]}""",
        ).toString()
        val expected = mapOf(2 to Triple(0, 0, -1), 0 to Triple(0, 0, -1), 3 to Triple(0, 0, 1), 4 to Triple(-1, 0, 0), 5 to Triple(1, 0, 0))
        for ((meta, facing) in expected) {
            val kit = ModTestKit(root)
            kit.png("assets/sciencenotleisure/textures/block/pigmee_fumo.png", 0xE0C0A0, 32, 32)
            kit.file("gtnh/mods/models/pigmee_fumo.json", model)
            kit.assets(
                GtnlTesrs.PigmeeFumo.MODEL to "gtnh/mods/models/pigmee_fumo.json",
                GtnlTesrs.PigmeeFumo.TEXTURE to "assets/sciencenotleisure/textures/block/pigmee_fumo.png",
            )
            kit.addonBlock("pigmee_fumo", meta)
            val tris = kit.render(Cell(state("sciencenotleisure:pigmee_fumo", meta), te("pigmee_fumo_tile_entity") {})).triangles
            assertEquals(2, tris.size)
            assertTrue(tris.all { it.facing == facing }, "meta $meta nose $facing")
            // the nose sits 1 px inside the block edge on that side
            val c = (0 until 3).map { a -> tris.flatMap { t -> (0 until 3).map { t.positions[it * 3 + a] } }.average().toFloat() }
            val f = facing.toList()
            for (a in 0 until 3) if (f[a] != 0) assertEquals(0.5f + f[a] * (7f / 16), c[a], 1e-4f)
        }
    }

    // ---- ender elevator ----

    @Test
    fun `ender elevator draws its height in the meta colour with the overlay on top`() {
        assertEquals(1f, GtnlTesrs.EnderElevator.height("ender_elevator_block"))
        assertEquals(0.5f, GtnlTesrs.EnderElevator.height("ender_elevator_slab"))
        assertEquals(0.0625f, GtnlTesrs.EnderElevator.height("ender_elevator_carpet"))
        assertEquals(0x993333, GtnlTesrs.EnderElevator.color(14))
        assertEquals(0xD87F33, GtnlTesrs.EnderElevator.color(17))

        val kit = ModTestKit(root)
        kit.png("assets/sciencenotleisure/textures/block/ender_elevator.png", 0xFFFFFF)
        kit.png("assets/sciencenotleisure/textures/block/ender_elevator_overlay.png", 0x40FF40)
        kit.assets(
            GtnlTesrs.EnderElevator.TEXTURE to "assets/sciencenotleisure/textures/block/ender_elevator.png",
            GtnlTesrs.EnderElevator.OVERLAY to "assets/sciencenotleisure/textures/block/ender_elevator_overlay.png",
        )
        kit.addonBlock("ender_elevator_slab", 14)
        kit.addonBlock("ender_elevator_carpet", 0)
        val body = kit.id(Key("sciencenotleisure", "block/ender_elevator"))
        val overlay = kit.id(Key("sciencenotleisure", "block/ender_elevator_overlay"))

        val slab = kit.render(Cell(state("sciencenotleisure:ender_elevator_slab", 14), te("ender_elevator_tile_entity") {})).triangles
        assertEquals(14, slab.size)
        val box = slab.filter { it.texture == body }
        assertEquals(12, box.size)
        assertEquals(0.5f, box.maxOf { it.ys().max() }, 1e-6f)
        assertTrue(box.all { rgb(it) == 0x993333 })
        // the side shows the sprite's lower half, like RenderBlocks
        val side = box.first { it.facing == Triple(0, 0, 1) }
        assertTrue((0 until 3).all { side.uvs[it * 2 + 1] >= 0.5f - 1e-6f })
        val top = slab.filter { it.texture == overlay }
        assertEquals(2, top.size)
        assertTrue(top.all { t -> t.facing == Triple(0, 1, 0) && t.ys().all { abs(it - 0.501f) < 1e-5f } && rgb(t) == 0xFFFFFF })

        val carpet = kit.render(Cell(state("sciencenotleisure:ender_elevator_carpet", 0), te("ender_elevator_tile_entity") {})).triangles
        assertEquals(0.0625f, carpet.filter { it.texture == body }.maxOf { it.ys().max() }, 1e-6f)
        assertTrue(carpet.filter { it.texture == body }.all { rgb(it) == 0xFFFFFF })
    }

    @Test
    fun `all six ports are registered by tile entity id`() {
        val ids = GtnlTesrs.ALL.flatMap { it.ids }.toSet()
        assertEquals(
            setOf(
                "beam_former_tile_entity", "multi_essentia_jar_tile_entity", "water_candle_tile_entity",
                "player_doll_tile_entity", "pigmee_fumo_tile_entity", "ender_elevator_tile_entity",
            ),
            ids,
        )
        assertTrue(GtnlTesrs.ALL.all { it in AddonTesrs.ALL })
        assertTrue(te("water_candle_tile_entity") {} is PlainTileEntity)
    }
}
