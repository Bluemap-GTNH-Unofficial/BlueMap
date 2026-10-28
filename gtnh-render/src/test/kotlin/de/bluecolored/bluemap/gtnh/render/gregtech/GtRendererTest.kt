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

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.ArrayTileModel
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.hires.TileModel
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.map.mask.Mask
import de.bluecolored.bluemap.core.resources.pack.PackVersion
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.DimensionType
import de.bluecolored.bluemap.core.world.LightData
import de.bluecolored.bluemap.core.world.biome.Biome
import de.bluecolored.bluemap.core.world.block.BlockAccess
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.gtnh.render.GtnhRender
import de.bluecolored.bluemap.gtnh.render.gregtech.GtFixtures.tex
import de.bluecolored.bluemap.gtnh.render.gregtech.GtTestTileEntities.machine
import de.bluecolored.bluemap.gtnh.render.gregtech.GtTestTileEntities.pipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Renders GT blocks through BlueMap's real resource loading, block access and tile model. */
class GtRendererTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun register() = GtnhRender.register()

        private val MACHINES = BlockState(Key("gregtech", "gt.blockmachines"), mapOf("meta" to "0"))
        private val STONE = BlockState(Key.minecraft("stone"))

        private val TEXTURES =
            listOf(
                "casing_side", "casing_top", "front", "front_overlay", "front_active", "hatch_overlay", "out_pipe", "wire",
                "insulation", "pipe_end", "pipe_side", "ctrl_front", "copied", "glass", "hidden", "shown",
                "casing_cleanstainless", "frame_dull", "frame_metallic", "stone", "netherrack", "mars", "ore_metallic",
                "ore_metallic_overlay", "ore_small_metallic", "ore_nether", "ore_small_nether", "ore_shiny",
                "ore_small_shiny", "ore_none", "unused",
            )
    }

    @TempDir
    lateinit var root: Path

    private fun file(path: String, content: String) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun png(path: String, rgb: Int) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, rgb or (0xFF shl 24))
        ImageIO.write(image, "png", file.toFile())
    }

    private val pack: ResourcePack by lazy {
        file("assets/minecraft/atlases/blocks.json", """{"sources": [{"type": "minecraft:directory", "source": "", "prefix": ""}]}""")
        GtFixtures.write(root)
        TEXTURES.forEachIndexed { i, name -> png("assets/t/textures/block/$name.png", 0x102030 * (i + 1) and 0xFFFFFF) }
        file("assets/gregtech/blockstates/gt.blockmachines.json", """{"variants": {"": {"renderer": "gtnh:gt_machine", "model": "t:block/cube"}}}""")
        file("assets/gregtech/blockstates/gt.blockores2.json", """{"variants": {"": {"renderer": "gtnh:gt_ore", "model": "t:block/cube"}}}""")
        file("assets/gregtech/blockstates/gt.blockframes.json", """{"variants": {"": {"renderer": "gtnh:gt_frame", "model": "t:block/cube"}}}""")
        file("assets/minecraft/blockstates/stone.json", """{"variants": {"": {"model": "t:block/cube"}}}""")
        val faces = listOf("down", "up", "north", "south", "west", "east").joinToString(",") { """"$it": {"texture": "#all", "cullface": "$it"}""" }
        file(
            "assets/t/models/block/cube.json",
            """{"textures": {"all": "t:block/stone"}, "elements": [{"from": [0,0,0], "to": [16,16,16], "faces": {$faces}}]}""",
        )
        ResourcePack(PackVersion(4, 0)).also { it.loadResources(listOf(root)) }
    }

    private val gallery by lazy { TextureGallery().also { it.put(pack.textures) } }

    private val settings =
        object : RenderSettings {
            override fun getRemoveCavesBelowY() = Int.MIN_VALUE

            override fun getCaveDetectionOceanFloor() = 0

            override fun isCaveDetectionUsesBlockLight() = false

            override fun getAmbientLight() = 0f

            override fun getRenderMask(): Mask = Mask.ALL

            override fun isSaveHiresLayer() = true

            override fun isRenderTopOnly() = false
        }

    private class Cell(val state: BlockState, val entity: BlockEntity?)

    private class World(val cells: Map<Triple<Int, Int, Int>, Cell>) : BlockAccess {
        private var x = 0
        private var y = 0
        private var z = 0
        private val light = LightData(15, 0)

        private fun cell() = cells[Triple(x, y, z)]

        override fun set(x: Int, y: Int, z: Int) {
            this.x = x
            this.y = y
            this.z = z
        }

        override fun copy(): BlockAccess = World(cells).also { it.set(x, y, z) }

        override fun getX() = x

        override fun getY() = y

        override fun getZ() = z

        override fun getBlockState(): BlockState = cell()?.state ?: BlockState.AIR

        override fun getLightData(): LightData = light

        override fun getBiome(): Biome = Biome.DEFAULT

        override fun getBlockEntity(): BlockEntity? = cell()?.entity

        override fun hasOceanFloorY() = false

        override fun getOceanFloorY() = 0
    }

    /** One drawn quad (two triangles). */
    private data class Quad(val texture: Int, val positions: FloatArray, val uvs: FloatArray, val rgb: Triple<Float, Float, Float>, val blockLight: Int) {
        val minX get() = (0 until 4).minOf { positions[it * 3] }
        val maxX get() = (0 until 4).maxOf { positions[it * 3] }
        val normal: FloatArray get() = de.bluecolored.bluemap.gtnh.render.QuadModel.normal(positions)
        val side: Int get() {
            val n = normal
            return (0 until 6).first { Side.dx(it) == Math.round(n[0]) && Side.dy(it) == Math.round(n[1]) && Side.dz(it) == Math.round(n[2]) }
        }
    }

    /** Records what renderers write; quads are rebuilt from the triangle pairs. */
    private class Recorder(private val model: ArrayTileModel = ArrayTileModel(64)) : TileModel by model {
        val positions = HashMap<Int, FloatArray>()
        val uvs = HashMap<Int, FloatArray>()
        val textures = HashMap<Int, Int>()
        val colors = HashMap<Int, Triple<Float, Float, Float>>()
        val blockLights = HashMap<Int, Int>()

        override fun setPositions(face: Int, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float): TileModel {
            positions[face] = floatArrayOf(x1, y1, z1, x2, y2, z2, x3, y3, z3)
            return model.setPositions(face, x1, y1, z1, x2, y2, z2, x3, y3, z3)
        }

        override fun setUvs(face: Int, u1: Float, v1: Float, u2: Float, v2: Float, u3: Float, v3: Float): TileModel {
            uvs[face] = floatArrayOf(u1, v1, u2, v2, u3, v3)
            return model.setUvs(face, u1, v1, u2, v2, u3, v3)
        }

        override fun setMaterialIndex(face: Int, m: Int): TileModel {
            textures[face] = m
            return model.setMaterialIndex(face, m)
        }

        override fun setColor(face: Int, r: Float, g: Float, b: Float): TileModel {
            colors[face] = Triple(r, g, b)
            return model.setColor(face, r, g, b)
        }

        override fun setBlocklight(face: Int, bl: Int): TileModel {
            blockLights[face] = bl
            return model.setBlocklight(face, bl)
        }

        fun quads(): List<Quad> =
            (0 until size() step 2).map { f ->
                val a = positions.getValue(f)
                val b = positions.getValue(f + 1)
                val ua = uvs.getValue(f)
                val ub = uvs.getValue(f + 1)
                Quad(
                    textures.getValue(f),
                    floatArrayOf(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], b[6], b[7], b[8]),
                    floatArrayOf(ua[0], ua[1], ua[2], ua[3], ua[4], ua[5], ub[4], ub[5]),
                    colors.getValue(f),
                    blockLights.getValue(f),
                )
            }
    }

    private class Result(val quads: List<Quad>, val color: Color)

    private fun render(center: Cell, vararg neighbours: Pair<Triple<Int, Int, Int>, Cell>): Result {
        // not (0, 0, 0) modulo the neighbourhood size: a fresh BlockNeighborhood believes it is already there
        val (x, y, z) = Triple(1, 65, 1)
        val cells = HashMap<Triple<Int, Int, Int>, Cell>()
        cells[Triple(x, y, z)] = center
        for ((offset, cell) in neighbours) cells[Triple(x + offset.first, y + offset.second, z + offset.third)] = cell
        val block = BlockNeighborhood(World(cells), pack, settings, DimensionType.OVERWORLD)
        block.set(x, y, z)
        assertEquals(center.state, block.blockState)
        val variants = ArrayList<Variant>()
        pack.getBlockState(center.state)!!.forEach(center.state, x, y, z) { variants.add(it) }
        val variant = variants.single()
        val recorder = Recorder()
        val color = Color().set(0f, 0f, 0f, 0f, true)
        variant.renderer.create(pack, gallery, settings).render(block, variant, TileModelView(recorder).initialize(), color)
        return Result(recorder.quads(), color)
    }

    private fun id(name: String) = gallery.get(tex(name))

    @Test
    fun `the dump loads with the pack and its textures enter the atlas`() {
        val data = requireNotNull(pack.getExtension(GregTechExtension.TYPE).data) { "dump loaded" }
        assertEquals(10, data.mtes.size)
        assertNotNull(pack.textures[tex("ore_small_nether")], "texture referenced only by the dump")
        assertNotNull(pack.textures[tex("casing_cleanstainless")])
        assertNull(pack.textures[tex("unused")])
        val variants = ArrayList<Variant>()
        pack.getBlockState(MACHINES)!!.forEach(MACHINES, 0, 0, 0) { variants.add(it) }
        assertSame(GregTechRender.MACHINE, variants.single().renderer)
    }

    @Test
    fun `machine front with active glow layer, culled against a full neighbour`() {
        val te = machine(10, facing = Side.SOUTH, active = true)
        val quads = render(Cell(MACHINES, te)).quads
        assertEquals(7, quads.size)
        val front = quads.filter { it.side == Side.SOUTH }
        assertEquals(listOf(id("casing_side"), id("front_active")), front.map { it.texture })
        assertEquals(listOf(0, 15), front.map { it.blockLight }, "glow layer is full-bright")
        assertEquals(1f, front[0].positions[2])
        assertTrue(front[1].positions[2] > 1f, "upper layer is lifted off the face")
        assertEquals(listOf(id("casing_top")), quads.filter { it.side == Side.UP }.map { it.texture })

        // bottom face: rendered layers are U-flipped (fixedBottomFaceUV = false)
        val bottom = quads.single { it.side == Side.DOWN }
        for (i in 0 until 4) {
            assertEquals(1f - bottom.positions[i * 3], bottom.uvs[i * 2], "u of corner $i")
            assertEquals(bottom.positions[i * 3 + 2], bottom.uvs[i * 2 + 1], "v of corner $i")
        }

        val culled = render(Cell(MACHINES, te), Triple(0, 0, 1) to Cell(STONE, null)).quads
        assertEquals(5, culled.size)
        assertTrue(culled.none { it.side == Side.SOUTH })
    }

    @Test
    fun `straight pipe spans the block and takes its paint`() {
        val connections = (1 shl Side.WEST) or (1 shl Side.EAST)
        val quads = render(Cell(MACHINES, pipe(5101, connections, color = 2))).quads
        assertEquals(14, quads.size)
        assertEquals(0f, quads.minOf { it.minX })
        assertEquals(1f, quads.maxOf { it.maxX })
        assertEquals(setOf(id("pipe_end")), quads.filter { it.side == Side.WEST || it.side == Side.EAST }.map { it.texture }.toSet())
        assertEquals(setOf(id("pipe_side")), quads.filter { it.side == Side.UP }.map { it.texture }.toSet())
        assertTrue(quads.all { it.rgb == Triple(1f, 0f, 0f) }, "painted red")
        // the end faces are culled by full neighbours, the sides are not
        val culled = render(Cell(MACHINES, pipe(5101, connections)), Triple(-1, 0, 0) to Cell(STONE, null)).quads
        assertEquals(13, culled.size)
    }

    @Test
    fun `ore shows stone and tinted ore on every side, with a map colour`() {
        val state = BlockState(Key("gregtech", "gt.blockores2"), mapOf("meta" to "1032"))
        val result = render(Cell(state, null))
        assertEquals(12, result.quads.size)
        val top = result.quads.filter { it.side == Side.UP }
        assertEquals(listOf(id("netherrack"), id("ore_nether")), top.map { it.texture })
        assertEquals(Triple(200 / 255f, 200 / 255f, 200 / 255f), top[1].rgb)
        assertTrue(result.color.a > 0f)
    }

    @Test
    fun `frames and unknown machines are never invisible`() {
        val frame = render(Cell(BlockState(Key("gregtech", "gt.blockframes"), mapOf("meta" to "32")), null)).quads
        assertEquals(6, frame.size)
        assertTrue(frame.all { it.texture == id("frame_metallic") && it.rgb == Triple(200 / 255f, 200 / 255f, 200 / 255f) })

        for (te in listOf(null, machine(12345))) {
            val quads = render(Cell(MACHINES, te)).quads
            assertEquals(6, quads.size)
            assertTrue(quads.all { it.texture == id("casing_side") })
        }
        // no dump data for the meta: the variant's own model
        val unknownFrame = render(Cell(BlockState(Key("gregtech", "gt.blockframes"), mapOf("meta" to "500")), null)).quads
        assertEquals(6, unknownFrame.size)
        assertTrue(unknownFrame.all { it.texture == id("stone") })
    }
}
