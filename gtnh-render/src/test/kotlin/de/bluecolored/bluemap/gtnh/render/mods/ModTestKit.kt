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
import de.bluecolored.bluemap.gtnh.render.QuadModel
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.math.roundToInt

/**
 * A resource pack in a temp dir plus a fake world, rendering blocks through BlueMap's real resource loading,
 * [BlockNeighborhood] and tile model.
 */
class ModTestKit(val root: Path) {
    init {
        GtnhRender.register()
        file("assets/minecraft/atlases/blocks.json", """{"sources": [{"type": "minecraft:directory", "source": "", "prefix": ""}]}""")
    }

    fun file(path: String, content: String) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        file.writeText(content)
    }

    fun png(path: String, rgb: Int, width: Int = 16, height: Int = 16) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, rgb or (0xFF shl 24))
        ImageIO.write(image, "png", file.toFile())
    }

    /** A full cube JSON model with [texture] on all faces (cullfaces set). */
    fun cubeModel(path: String, texture: String) {
        val faces = listOf("down", "up", "north", "south", "west", "east").joinToString(",") { """"$it": {"texture": "#all", "cullface": "$it"}""" }
        file(path, """{"textures": {"all": "$texture"}, "elements": [{"from": [0,0,0], "to": [16,16,16], "faces": {$faces}}]}""")
    }

    val pack: ResourcePack by lazy { ResourcePack(PackVersion(4, 0)).also { it.loadResources(listOf(root)) } }

    val gallery by lazy { TextureGallery().also { it.put(pack.textures) } }

    fun id(texture: Key): Int = gallery.get(texture)

    val settings =
        object : RenderSettings {
            override fun getRemoveCavesBelowY() = Int.MIN_VALUE

            override fun getCaveDetectionOceanFloor() = 0

            override fun isCaveDetectionUsesBlockLight() = false

            override fun getAmbientLight() = 0f

            override fun getRenderMask(): Mask = Mask.ALL

            override fun isSaveHiresLayer() = true

            override fun isRenderTopOnly() = false
        }

    class Cell(val state: BlockState, val entity: BlockEntity? = null)

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

    /** One drawn triangle. */
    data class Triangle(val texture: Int, val positions: FloatArray, val uvs: FloatArray, val rgb: Triple<Float, Float, Float>, val blockLight: Int) {
        val normal: FloatArray
            get() {
                val p = positions
                val ax = p[3] - p[0]
                val ay = p[4] - p[1]
                val az = p[5] - p[2]
                val bx = p[6] - p[0]
                val by = p[7] - p[1]
                val bz = p[8] - p[2]
                val n = floatArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
                val len = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
                return floatArrayOf(n[0] / len, n[1] / len, n[2] / len)
            }

        /** The axis direction of the normal as (dx, dy, dz). */
        val facing: Triple<Int, Int, Int> get() = normal.let { Triple(it[0].roundToInt(), it[1].roundToInt(), it[2].roundToInt()) }

        fun center(axis: Int): Float = (positions[axis] + positions[3 + axis] + positions[6 + axis]) / 3f

        fun uvCenter(axis: Int): Float = (uvs[axis] + uvs[2 + axis] + uvs[4 + axis]) / 3f
    }

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

        fun triangles(): List<Triangle> = (0 until size()).map { f ->
            Triangle(textures.getValue(f), positions.getValue(f), uvs.getValue(f), colors.getValue(f), blockLights.getValue(f))
        }
    }

    class Result(val triangles: List<Triangle>, val color: Color, val variant: Variant)

    /** Renders [center] at (1, 65, 1) with [neighbours] at offsets from it. */
    fun render(center: Cell, vararg neighbours: Pair<Triple<Int, Int, Int>, Cell>): Result {
        // not (0, 0, 0) modulo the neighbourhood size: a fresh BlockNeighborhood believes it is already there
        val (x, y, z) = Triple(1, 65, 1)
        val cells = HashMap<Triple<Int, Int, Int>, Cell>()
        cells[Triple(x, y, z)] = center
        for ((offset, cell) in neighbours) cells[Triple(x + offset.first, y + offset.second, z + offset.third)] = cell
        val block = BlockNeighborhood(World(cells), pack, settings, DimensionType.OVERWORLD)
        block.set(x, y, z)
        val variants = ArrayList<Variant>()
        pack.getBlockState(center.state)!!.forEach(center.state, x, y, z) { variants.add(it) }
        val variant = variants.single()
        val recorder = Recorder()
        val color = Color().set(0f, 0f, 0f, 0f, true)
        variant.renderer.create(pack, gallery, settings).render(block, variant, TileModelView(recorder).initialize(), color)
        return Result(recorder.triangles(), color, variant)
    }

    companion object {
        fun state(id: String, meta: Int) = BlockState(Key.parse(id), mapOf("meta" to meta.toString()))

        /** Unit normal of a mesh quad (front side). */
        fun normal(q: MeshQuad): FloatArray = QuadModel.normal(q.positions)
    }
}
