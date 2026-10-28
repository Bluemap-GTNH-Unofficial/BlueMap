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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import de.bluecolored.bluemap.core.util.Key

/**
 * Parses the exporter's GregTech dump (`gtnh/gregtech/{mte,casings,materials,ores}.json`, schema in
 * `tools/gtnh-exporter/README.md` "GregTech dump"). Each file is optional; a missing one leaves its part empty.
 */
object GtDataParser {
    fun parse(mte: JsonObject?, casings: JsonObject?, materials: JsonObject?, ores: JsonObject?): GtData {
        val render = mte?.obj("render")
        val mteLayers = mte?.let { layers(it.array("layers")) } ?: emptyList()
        val mteFaces = mte?.array("faces")?.map { face(it.asJsonArray, mteLayers) } ?: emptyList()

        val mtes = HashMap<Int, GtMte>()
        mte?.array("entries")?.forEach { element ->
            val entry = element.asJsonObject
            val parsed = mteEntry(entry, mteFaces) ?: return@forEach
            mtes[parsed.id] = parsed
        }

        val casingLayers = casings?.let { layers(it.array("layers")) } ?: emptyList()
        val casingFaces = HashMap<Int, GtSides>()
        casings?.obj("casings")?.entrySet()?.forEach { (id, sides) ->
            casingFaces[id.toInt()] = sides.asJsonArray.map { face(it.asJsonArray, casingLayers) }
        }
        val machineCasings =
            casings?.array("machineCasings")?.map { tier ->
                tier.asJsonArray.map { colour -> colour.asJsonArray.map { face(it.asJsonArray, casingLayers) } }
            } ?: emptyList()

        val materialMap = HashMap<Int, GtMaterial>()
        materials?.obj("materials")?.entrySet()?.forEach { (id, value) ->
            val m = value.asJsonObject
            materialMap[id.toInt()] =
                GtMaterial(
                    id = id.toInt(),
                    name = m.string("name") ?: "",
                    rgb = rgb(m.get("rgba")),
                    iconSet = m.string("iconSet") ?: "NONE",
                    glowingOre = m.bool("glowingOre"),
                )
        }
        val iconSets = HashMap<String, Map<String, GtIconRef>>()
        materials?.obj("iconSets")?.entrySet()?.forEach { (name, value) ->
            val icons = HashMap<String, GtIconRef>()
            value.asJsonObject.obj("blockIcons")?.entrySet()?.forEach { (type, icon) -> icons[type] = iconRef(icon.asJsonObject) }
            iconSets[name] = icons
        }
        val dyes = IntArray(GtData.DYES) { GtLayer.WHITE }
        materials?.array("dyes")?.forEach { element ->
            val dye = element.asJsonObject
            val index = dye.get("index")?.asInt ?: return@forEach
            if (index in dyes.indices) dyes[index] = rgb(dye.get("rgba"))
        }

        val oreLayers = ores?.let { layers(it.array("layers")) } ?: emptyList()
        val oreBlocks = HashMap<Key, GtOreBlock>()
        ores?.array("blocks")?.forEach { element ->
            val block = element.asJsonObject
            val key = Key(block.string("block") ?: return@forEach)
            val stones =
                block.array("stoneTypes")?.map { stone ->
                    if (stone == null || stone.isJsonNull) {
                        null
                    } else {
                        val s = stone.asJsonObject
                        GtOreStone(s.string("name") ?: "", face(s.array("background") ?: JsonArray(), oreLayers))
                    }
                } ?: emptyList()
            oreBlocks[key] = GtOreBlock(key, stones)
        }
        val oreTextures = HashMap<String, GtOreTextures>()
        ores?.obj("oreTextures")?.entrySet()?.forEach { (set, value) ->
            val o = value.asJsonObject
            val perStone = HashMap<String, GtOreTexture>()
            o.obj("perStone")?.entrySet()?.forEach { (stone, textures) -> perStone[stone] = oreTexture(textures.asJsonObject) }
            oreTextures[set] = GtOreTextures(oreTexture(o), perStone)
        }

        return GtData(
            fixedBottomFaceUV = render?.bool("fixedBottomFaceUV") ?: false,
            renderGlowTextures = render?.get("renderGlowTextures")?.asBoolean ?: true,
            mtes = mtes,
            casings = casingFaces,
            machineCasings = machineCasings,
            materials = materialMap,
            iconSets = iconSets,
            dyes = dyes,
            oreBlocks = oreBlocks,
            oreTextures = oreTextures,
            noMaterialOre = ores?.obj("noMaterial")?.let(::iconRef),
        )
    }

    private fun mteEntry(entry: JsonObject, faces: List<GtFace>): GtMte? {
        val id = entry.get("id")?.asInt ?: return null
        fun face(element: JsonElement?): GtFace = element?.asInt?.let { faces.getOrNull(it) } ?: emptyList()

        val textures =
            entry.array("textures")?.map { facing ->
                facing.asJsonArray.map { state -> state.asJsonArray.map(::face) }
            }
        val outputFace =
            entry.array("outputFace")?.map {
                val o = it.asJsonObject
                GtOutputFace(face(o.get("down")), face(o.get("up")), face(o.get("side")))
            }
        val hatch =
            entry.obj("hatch")?.let { h ->
                GtHatch(
                    front = h.array("front")?.map(::face) ?: emptyList(),
                    defaultBackground = h.array("defaultBackground")?.map(::face) ?: emptyList(),
                )
            }
        val pipe =
            entry.obj("pipe")?.let { p ->
                GtPipe(
                    thickness = p.get("thickness")?.asFloat ?: 1f,
                    materialRgb = p.obj("material")?.get("rgba")?.let(::rgb),
                    insulated = p.bool("insulated"),
                    connected = face(p.get("connected")),
                    unconnected = face(p.get("unconnected")),
                )
            }
        return GtMte(
            id = id,
            name = entry.string("name") ?: "",
            kind = entry.string("kind") ?: "machine",
            tier = entry.get("tier")?.asInt ?: 0,
            alignment = entry.bool("alignment"),
            tesr = entry.bool("tesr"),
            customRender = entry.string("customRender"),
            textures = textures,
            outputFace = outputFace,
            hatch = hatch,
            pipe = pipe,
        )
    }

    private fun layers(array: JsonArray?): List<GtLayer> = array?.map { layer(it.asJsonObject) } ?: emptyList()

    internal fun layer(o: JsonObject): GtLayer =
        when (o.string("type")) {
            "background" -> {
                GtLayer.BACKGROUND
            }

            "block" -> {
                GtLayer(
                    texture = o.key("key"),
                    pass = o.get("pass")?.asInt ?: 0,
                    uv = UvMode.COPIED_BLOCK,
                )
            }

            else -> {
                GtLayer(
                    texture = if (o.bool("invisible")) null else o.key("key"),
                    overlay = o.key("overlayKey"),
                    rgb = o.get("rgba")?.let(::rgb) ?: GtLayer.WHITE,
                    glow = o.bool("glow"),
                    pass = o.get("pass")?.asInt ?: 0,
                    uv = if (o.bool("extFacing")) UvMode.EXT_FACING else UvMode.RENDERED,
                )
            }
        }

    /** A face from layer indices; pass-1 layers are drawn after pass-0 ones, as GT's two world passes do. */
    private fun face(indices: JsonArray, layers: List<GtLayer>): GtFace {
        val face = indices.mapNotNull { layers.getOrNull(it.asInt) }
        return if (face.zipWithNext().all { (a, b) -> a.pass <= b.pass }) face else face.sortedBy { it.pass }
    }

    private fun iconRef(o: JsonObject): GtIconRef = GtIconRef(o.key("key"), o.key("overlayKey"), o.get("pass")?.asInt ?: 0)

    private fun oreTexture(o: JsonObject): GtOreTexture = GtOreTexture(o.obj("normal")?.let(::iconRef), o.obj("small")?.let(::iconRef))

    /** GT `short[4]` rgba -> 0xRRGGBB (alpha is unused by GT's tinting). */
    internal fun rgb(element: JsonElement?): Int {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return GtLayer.WHITE
        if (array.size() < 3) return GtLayer.WHITE
        fun channel(i: Int) = array[i].asInt.coerceIn(0, 255)
        return (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.bool(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    private fun JsonObject.key(name: String): Key? = string(name)?.let { Key(it) }
}
