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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.util.Key
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A small synthetic GregTech dump in the exporter's schema (`tools/gtnh-exporter/README.md` "GregTech dump"):
 * one generator-like machine, a basic machine, a hatch, a multiblock controller, a bare and an insulated cable,
 * a fluid pipe, ore blocks and materials. Textures are `t:block/<name>`.
 */
object GtFixtures {
    fun tex(name: String) = Key("t", "block/$name")

    private fun icon(name: String, rgba: String = "[255,255,255,0]", extra: String = "") =
        """{"type":"icon","icon":"t:$name","key":"t:block/$name","rgba":$rgba,"glow":false$extra}"""

    // mte.json layer table
    private val mteLayers =
        listOf(
            icon("casing_side"), // 0
            icon("casing_top"), // 1
            """{"type":"icon","icon":"t:front","key":"t:block/front","overlay":"t:front_overlay","overlayKey":"t:block/front_overlay","rgba":[255,255,255,0],"glow":false}""", // 2
            """{"type":"icon","icon":"t:front_active","key":"t:block/front_active","rgba":[255,255,255,0],"glow":true}""", // 3
            """{"type":"background"}""", // 4
            icon("hatch_overlay"), // 5
            icon("out_pipe"), // 6
            icon("wire", "[80,80,250,0]"), // 7
            icon("insulation", "[64,64,64,0]"), // 8
            icon("pipe_end", "[100,50,0,0]"), // 9
            icon("pipe_side", "[100,50,0,0]"), // 10
            icon("ctrl_front", extra = ""","extFacing":true"""), // 11
            """{"type":"block","block":"gregtech:gt.blockcasings","meta":2,"copiedSide":6,"icon":"t:copied","key":"t:block/copied"}""", // 12
            icon("glass", extra = ""","pass":1"""), // 13
            """{"type":"icon","invisible":true,"icon":"t:hidden","key":"t:block/hidden","overlay":"t:shown","overlayKey":"t:block/shown","rgba":[255,255,255,0],"glow":false}""", // 14
        )

    // mte.json face table
    private val mteFaces =
        listOf(
            "[0]", // 0 side
            "[1]", // 1 top
            "[0,2]", // 2 front inactive
            "[0,3]", // 3 front active
            "[4,5]", // 4 hatch front
            "[0,6]", // 5 output side
            "[1,6]", // 6 output up
            "[0,1,6]", // 7 output down
            "[7]", // 8 bare wire
            "[7,8]", // 9 insulated cable end
            "[8]", // 10 insulation
            "[9]", // 11 pipe end
            "[10]", // 12 pipe side
            "[12,11]", // 13 controller front
            "[13,0]", // 14 pass 1 layer listed first
            "[14]", // 15 invisible base with overlay
            "[0,5]", // 16 hatch front as GT resolves it with the default background
        )

    /** `textures[facing][active][side]`: [front] (inactive / active) on the facing side, top on up, [side] elsewhere. */
    private fun textures(frontInactive: Int, frontActive: Int, side: Int = 0, top: Int = 1): String =
        (0 until 6).joinToString(",", "[", "]") { facing ->
            listOf(frontInactive, frontActive).joinToString(",", "[", "]") { front ->
                (0 until 6).joinToString(",", "[", "]") { s -> if (s == facing) "$front" else if (s == 1) "$top" else "$side" }
            }
        }

    private val mteEntries =
        listOf(
            """{"id":10,"name":"generator.test","kind":"generator","baseType":1,"tier":1,"textures":${textures(2, 3)}}""",
            """{"id":20,"name":"basicmachine.test","kind":"basic_machine","baseType":1,"tier":1,"textures":${textures(2, 3)},
                "outputFace":[{"down":7,"up":6,"side":5},{"down":7,"up":6,"side":5}]}""",
            """{"id":30,"name":"hatch.test","kind":"hatch","baseType":0,"tier":1,"textures":${textures(16, 16)},
                "hatch":{"front":[4,4],"defaultBackground":[0,1,0,0,0,0]}}""",
            """{"id":40,"name":"multimachine.test","kind":"multiblock_controller","baseType":2,"alignment":true,"textures":${textures(13, 13)}}""",
            """{"id":50,"name":"glass.test","kind":"machine","baseType":0,"textures":${textures(14, 14, side = 14, top = 14)}}""",
            """{"id":60,"name":"hidden.test","kind":"machine","baseType":0,"textures":${textures(15, 15, side = 15, top = 15)}}""",
            """{"id":1200,"name":"wire.test","kind":"cable","baseType":8,
                "pipe":{"thickness":0.125,"material":{"name":"Cobalt","id":33,"rgba":[80,80,250,0],"iconSet":"METALLIC"},"insulated":false,"connected":8,"unconnected":8}}""",
            """{"id":1206,"name":"cable.test","kind":"cable","baseType":8,
                "pipe":{"thickness":0.25,"material":{"name":"Cobalt","id":33,"rgba":[80,80,250,0],"iconSet":"METALLIC"},"insulated":true,"connected":9,"unconnected":10}}""",
            """{"id":5101,"name":"pipe.test","kind":"fluid_pipe","baseType":4,
                "pipe":{"thickness":0.375,"material":{"name":"Wood","id":809,"rgba":[100,50,0,0],"iconSet":"WOOD"},"connected":11,"unconnected":12}}""",
            """{"id":4102,"name":"frame.test","kind":"frame","baseType":6,
                "pipe":{"thickness":1.0,"material":{"name":"Lithium","id":6,"rgba":[225,220,255,0],"iconSet":"DULL"},"connected":0,"unconnected":1}}""",
        )

    val mte: String =
        """{"render":{"fixedBottomFaceUV":false,"renderGlowTextures":true},
            "layers":${mteLayers.joinToString(",", "[", "]")},
            "faces":${mteFaces.joinToString(",", "[", "]")},
            "classes":{},"entries":${mteEntries.joinToString(",", "[", "]")},"errors":[]}"""

    /** Dye `c` rgb: red for 1, otherwise grey levels. */
    fun dyeRgb(c: Int): Int = if (c == 1) 0xFF0000 else (c * 10) * 0x010101

    private fun rgba(rgb: Int) = "[${rgb shr 16 and 0xFF},${rgb shr 8 and 0xFF},${rgb and 0xFF},0]"

    // casings.json: layer 0 = the unpainted machine casing (same as mte layer 0), 1 = clean stainless, 2.. = painted (2 + dye)
    val casings: String =
        run {
            val layers = ArrayList<String>()
            layers.add(icon("casing_side"))
            layers.add(icon("casing_cleanstainless"))
            for (c in 0 until 16) layers.add(icon("casing_side", rgba(dyeRgb(c))))
            val tier = (0..16).joinToString(",", "[", "]") { colour ->
                val layer = if (colour == 0) 0 else 2 + colour - 1
                (0 until 6).joinToString(",", "[", "]") { "[$layer]" }
            }
            """{"errorTextureId":225,"layers":${layers.joinToString(",", "[", "]")},
                "casings":{"49":[[1],[1],[1],[1],[1],[1]]},
                "machineCasings":[$tier,$tier],"errors":[]}"""
        }

    val materials: String =
        """{"materials":{
              "6":{"id":6,"name":"Lithium","rgba":[225,220,255,0],"iconSet":"DULL"},
              "32":{"id":32,"name":"Iron","rgba":[200,200,200,0],"iconSet":"METALLIC"},
              "86":{"id":86,"name":"Gold","rgba":[255,255,30,0],"iconSet":"SHINY","glowingOre":true}},
            "unindexed":[],
            "iconSets":{
              "DULL":{"name":"DULL","blockIcons":{"FRAME_GT":{"icon":"t:frame_dull","key":"t:block/frame_dull"}}},
              "METALLIC":{"name":"METALLIC","blockIcons":{"FRAME_GT":{"icon":"t:frame_metallic","key":"t:block/frame_metallic"}}},
              "SHINY":{"name":"SHINY","blockIcons":{}}},
            "dyes":${(0 until 16).joinToString(",", "[", "]") { """{"index":$it,"name":"dye$it","colorName":"C$it","rgba":${rgba(dyeRgb(it))}}""" }},
            "machineMetal":[255,255,255,0]}"""

    val ores: String =
        """{"metaEncoding":{"materialModulo":1000,"stoneDivisor":1000,"naturalOffset":8000,"smallOffset":16000},
            "layers":[${icon("stone")},${icon("netherrack")},${icon("mars")}],
            "blocks":[
              {"block":"gregtech:gt.blockores2","stoneTypes":[
                 {"index":0,"name":"Stone","icons":[],"background":[0]},
                 {"index":1,"name":"Netherrack","icons":[],"background":[1]},
                 null,null,null,null,null,null],"examples":[]},
              {"block":"gregtech:gt.blockores3","stoneTypes":[{"index":0,"name":"Mars","icons":[],"background":[2]}],"examples":[]}],
            "oreTextures":{
              "METALLIC":{"normal":{"icon":"t:ore_metallic","key":"t:block/ore_metallic","overlay":"t:ore_metallic_overlay","overlayKey":"t:block/ore_metallic_overlay","pass":1},
                          "small":{"icon":"t:ore_small_metallic","key":"t:block/ore_small_metallic","pass":1},
                          "perStone":{"Netherrack":{"normal":{"icon":"t:ore_nether","key":"t:block/ore_nether","pass":1},
                                                    "small":{"icon":"t:ore_small_nether","key":"t:block/ore_small_nether","pass":1}}}},
              "SHINY":{"normal":{"icon":"t:ore_shiny","key":"t:block/ore_shiny","pass":1},"small":{"icon":"t:ore_small_shiny","key":"t:block/ore_small_shiny","pass":1}}},
            "noMaterial":{"icon":"t:ore_none","key":"t:block/ore_none","pass":1},
            "exampleMaterial":{"id":32,"name":"Iron"}}"""

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    fun data(): GtData = GtDataParser.parse(json(mte), json(casings), json(materials), json(ores))

    /** Writes the dump into `<root>/gtnh/gregtech/`. */
    fun write(root: Path) {
        val dir = root.resolve("gtnh/gregtech")
        dir.createDirectories()
        dir.resolve("mte.json").writeText(mte)
        dir.resolve("casings.json").writeText(casings)
        dir.resolve("materials.json").writeText(materials)
        dir.resolve("ores.json").writeText(ores)
    }
}
