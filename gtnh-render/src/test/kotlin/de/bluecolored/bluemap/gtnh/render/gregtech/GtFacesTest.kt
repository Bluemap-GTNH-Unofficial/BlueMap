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
import de.bluecolored.bluemap.gtnh.render.gregtech.GtFixtures.tex
import de.bluecolored.bluemap.gtnh.render.gregtech.GtTestTileEntities.machine
import de.bluecolored.bluemap.gtnh.render.gregtech.GtTestTileEntities.pipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GtFacesTest {
    private val data = GtFixtures.data()

    private fun textures(face: GtFace?) = face?.map { it.texture?.value?.removePrefix("block/") }

    private fun sides(sides: GtSides?) = sides!!.map { textures(it) }

    @Test
    fun `tile entity NBT with GT tag types`() {
        val te =
            machine(10, facing = 5, active = true, color = 3, mainFacing = 4, texturePage = 1, textureIndex = 2, rotation = 3, flip = 1)
        assertEquals(10, te.mteId)
        assertEquals(5, te.facing)
        assertTrue(te.active)
        assertEquals(2, te.colourIndex)
        assertEquals(4, te.mainFacing)
        assertEquals(1 shl 7 or 2, te.casingTextureId)
        assertEquals(3, te.rotation)
        assertEquals(1, te.flip)
        assertEquals(1, te.x)
        assertEquals(3, te.z)

        val p = pipe(1206, connections = 0b110000, color = 16)
        assertEquals(0b110000, p.connections)
        assertEquals(15, p.colourIndex)
        assertEquals(-1, machine(10).colourIndex)
        // other ids stay plain block entities
        assertFalse(GtTestTileEntities.read("Chest") {} is GtTileEntity)
    }

    @Test
    fun `front follows mFacing and the active state`() {
        val mte = data.mtes.getValue(10)
        for (facing in 0 until 6) {
            val inactive = sides(GtFaces.machine(data, mte, machine(10, facing = facing)))
            val active = sides(GtFaces.machine(data, mte, machine(10, facing = facing, active = true)))
            for (side in 0 until 6) {
                val expected =
                    if (side == facing) {
                        null
                    } else if (side == Side.UP) {
                        listOf("casing_top")
                    } else {
                        listOf("casing_side")
                    }
                if (expected != null) {
                    assertEquals(expected, inactive[side], "facing $facing side $side")
                    assertEquals(expected, active[side])
                }
            }
            assertEquals(listOf("casing_side", "front"), inactive[facing])
            assertEquals(listOf("casing_side", "front_active"), active[facing])
        }
        val front = GtFaces.machine(data, mte, machine(10, facing = 2))!![2]
        assertEquals(tex("front_overlay"), front[1].overlay)
        assertTrue(GtFaces.machine(data, mte, machine(10, facing = 2, active = true))!![2][1].glow)
    }

    @Test
    fun `basic machines show the front on mMainFacing and the output overlay on mFacing`() {
        val mte = data.mtes.getValue(20)
        // front north, output south (as GT places them)
        val south = sides(GtFaces.machine(data, mte, machine(20, facing = 3, mainFacing = 2)))
        assertEquals(listOf("casing_side", "front"), south[2])
        assertEquals(listOf("casing_side", "out_pipe"), south[3])
        assertEquals(listOf("casing_top"), south[1])
        // output up / down
        assertEquals(listOf("casing_top", "out_pipe"), sides(GtFaces.machine(data, mte, machine(20, facing = 1, mainFacing = 4)))[1])
        assertEquals(
            listOf("casing_side", "casing_top", "out_pipe"),
            sides(GtFaces.machine(data, mte, machine(20, facing = 0, mainFacing = 4)))[0],
        )
        // output on the front: the front wins
        val same = sides(GtFaces.machine(data, mte, machine(20, facing = 5, mainFacing = 5)))
        assertEquals(listOf("casing_side", "front"), same[5])
        assertEquals(6, same.count { it != null })
    }

    @Test
    fun `hatch background from texturePage and textureIndex, tier casing, or paint`() {
        val mte = data.mtes.getValue(30)
        // default background: the hatch's own (uncoloured) tier casing, front = background + overlay
        val plain = sides(GtFaces.machine(data, mte, machine(30, facing = 2)))
        assertEquals(listOf("casing_side", "hatch_overlay"), plain[2])
        assertEquals(listOf("casing_top"), plain[1])
        assertEquals(sides(GtFaces.hatch(data, mte, mte.hatch!!, 0, Side.NORTH, 0, -1)), plain, "composition with the default background")
        assertEquals(listOf("casing_side"), plain[4])
        // casing id 49 = page 0, index 49
        val clean = sides(GtFaces.machine(data, mte, machine(30, facing = 2, texturePage = 0, textureIndex = 49)))
        assertEquals(listOf("casing_cleanstainless", "hatch_overlay"), clean[2])
        assertEquals(List(5) { listOf("casing_cleanstainless") }, clean.filterIndexed { i, _ -> i != 2 })
        // painted red (mColor = dye 1 + 1): MACHINE_CASINGS[tier][colour + 1]
        val painted = GtFaces.machine(data, mte, machine(30, facing = 2, color = 2))!!
        assertEquals(0xFF0000, painted[2][0].rgb)
        assertEquals(GtLayer.WHITE, painted[2][1].rgb)
        assertEquals(0xFF0000, painted[3][0].rgb)
    }

    @Test
    fun `painted machines swap their casing layers only`() {
        val mte = data.mtes.getValue(10)
        val painted = GtFaces.machine(data, mte, machine(10, facing = 2, color = 2))!!
        assertEquals(0xFF0000, painted[2][0].rgb, "casing under the front")
        assertEquals(tex("casing_side"), painted[2][0].texture)
        assertEquals(GtLayer.WHITE, painted[2][1].rgb, "front overlay untouched")
        assertEquals(0xFF0000, painted[4][0].rgb)
        assertEquals(GtLayer.WHITE, painted[1][0].rgb, "not a machine-casing layer")
    }

    @Test
    fun `controller faces keep their UV modes and pass-1 layers go last`() {
        val front = GtFaces.machine(data, data.mtes.getValue(40), machine(40, facing = 2))!![2]
        assertEquals(listOf(UvMode.COPIED_BLOCK, UvMode.EXT_FACING), front.map { it.uv })
        val glass = GtFaces.machine(data, data.mtes.getValue(50), machine(50))!![0]
        assertEquals(listOf("casing_side", "glass"), textures(glass))
        assertEquals(listOf(0, 1), glass.map { it.pass })
        val hidden = GtFaces.machine(data, data.mtes.getValue(60), machine(60))!![0].single()
        assertNull(hidden.texture, "invisible base icon")
        assertEquals(tex("shown"), hidden.overlay)
    }

    @Test
    fun `pipe sides show the end texture where connected, straight for a single connection`() {
        val pipe = data.mtes.getValue(5101).pipe!!

        fun face(
            connections: Int,
            side: Int,
        ) = textures(GtFaces.pipe(data, pipe, connections, side, -1))!!.single()
        // no connection: end texture everywhere
        for (side in 0 until 6) assertEquals("pipe_end", face(0, side))
        // single connection west: treated as west-east
        val west = 1 shl Side.WEST
        assertEquals("pipe_end", face(west, Side.WEST))
        assertEquals("pipe_end", face(west, Side.EAST))
        assertEquals("pipe_side", face(west, Side.UP))
        // a corner
        val corner = (1 shl Side.WEST) or (1 shl Side.UP)
        assertEquals("pipe_end", face(corner, Side.UP))
        assertEquals("pipe_side", face(corner, Side.EAST))
    }

    @Test
    fun `paint tints a pipe's material layers and a cable's insulation`() {
        val red = 2 // mColor of dye 1
        val fluid = data.mtes.getValue(5101).pipe!!
        assertEquals(0xFF0000, GtFaces.pipe(data, fluid, 0, 0, red - 1).single().rgb)
        assertEquals(0x643200, GtFaces.pipe(data, fluid, 0, 0, -1).single().rgb)

        val wire = data.mtes.getValue(1200).pipe!!
        assertEquals(0xFF0000, GtFaces.pipe(data, wire, 0, 0, red - 1).single().rgb)

        val cable = data.mtes.getValue(1206).pipe!!
        val end = GtFaces.pipe(data, cable, 0, Side.WEST, red - 1)
        assertEquals(listOf(0x5050FA, 0xFF0000), end.map { it.rgb }, "wire keeps its material colour, insulation is painted")
        assertEquals(0xFF0000, GtFaces.pipe(data, cable, 1 shl Side.UP, Side.WEST, red - 1).single().rgb)
    }

    @Test
    fun `ore meta decoding`() {
        assertEquals(GtOreMeta(32, 0, small = false, natural = false), GtOreMeta.decode(32))
        assertEquals(GtOreMeta(32, 1, small = false, natural = false), GtOreMeta.decode(1032))
        assertEquals(GtOreMeta(32, 0, small = false, natural = true), GtOreMeta.decode(8032))
        assertEquals(GtOreMeta(32, 0, small = true, natural = false), GtOreMeta.decode(16032))
        assertEquals(GtOreMeta(86, 7, small = true, natural = true), GtOreMeta.decode(16000 + 8000 + 7086))
        assertEquals(GtOreMeta(999, 5, small = false, natural = false), GtOreMeta.decode(5999))
    }

    @Test
    fun `ores are the stone background plus the tinted ore icon`() {
        val ores2 = Key("gregtech", "gt.blockores2")
        val iron = GtFaces.ore(data, ores2, 32)!!
        assertEquals(listOf("stone", "ore_metallic"), textures(iron))
        assertEquals(0xC8C8C8, iron[1].rgb)
        assertEquals(tex("ore_metallic_overlay"), iron[1].overlay)
        assertEquals(1, iron[1].pass)
        assertEquals(iron, GtFaces.ore(data, ores2, 8032), "natural flag does not change textures")
        assertEquals(listOf("stone", "ore_small_metallic"), textures(GtFaces.ore(data, ores2, 16032)))
        // per-stone ore texture on netherrack
        assertEquals(listOf("netherrack", "ore_nether"), textures(GtFaces.ore(data, ores2, 1032)))
        assertEquals(listOf("netherrack", "ore_small_nether"), textures(GtFaces.ore(data, ores2, 16000 + 1032)))
        // empty stone slot falls back to Stone
        assertEquals(listOf("stone", "ore_metallic"), textures(GtFaces.ore(data, ores2, 2032)))
        // glowing material
        assertTrue(GtFaces.ore(data, ores2, 86)!![1].glow)
        // unknown material: GT's untinted "no material" ore icon
        val none = GtFaces.ore(data, ores2, 999)!!
        assertEquals(listOf("stone", "ore_none"), textures(none))
        assertEquals(GtLayer.WHITE, none[1].rgb)
        // other ore block: its own stone slots
        assertEquals(listOf("mars", "ore_metallic"), textures(GtFaces.ore(data, Key("gregtech", "gt.blockores3"), 32)))
        assertNull(GtFaces.ore(data, Key("gregtech", "gt.blockores9"), 32))
    }

    @Test
    fun `frames use the material frame icon tinted`() {
        val lithium = GtFaces.frame(data, 6)!!.single()
        assertEquals(tex("frame_dull"), lithium.texture)
        assertEquals(0xE1DCFF, lithium.rgb)
        // the MTE bit does not change the material
        assertEquals(lithium, GtFaces.frame(data, 0x1000 or 6)!!.single())
        assertNull(GtFaces.frame(data, 86), "material without a frame icon")
        assertNull(GtFaces.frame(data, 500))
    }

    @Test
    fun `casing fallback and texture collection`() {
        assertEquals(List(6) { listOf("casing_side") }, sides(GtFaces.casingFallback(data)))
        val keys = data.textureKeys()
        for (name in listOf("front_overlay", "casing_cleanstainless", "ore_small_nether", "frame_metallic", "ore_none", "mars", "copied")) {
            assertTrue(tex(name) in keys, name)
        }
        assertNotNull(data.mtes[4102]?.pipe)
    }
}
