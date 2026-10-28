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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePackExtension
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.render.mods.chisel.ChiselData
import de.bluecolored.bluemap.gtnh.render.mods.ctm.MyCtmData
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Loads the exporter's mod data (`gtnh/mods/`): the raw asset index `assets.json` (jar asset name -> path in the
 * pack), Chisel's CTM table `chisel.json`, MyCTMLib's `myctmlib.json`, Thaumcraft's aspect colours `aspects.json`,
 * and the Wavefront / JSON models the index lists. Each file comes from the highest-priority pack that has it. Only
 * textures a renderer can use enter the atlas.
 */
class ModAssets(
    private val pack: ResourcePack,
) : ResourcePackExtension {
    @Volatile
    private var assets: Map<String, String> = emptyMap()

    @Volatile
    var chisel: ChiselData? = null
        private set

    @Volatile
    var myCtm: MyCtmData? = null
        private set

    private val objModels = HashMap<String, ObjModel>()

    private val jsonModels = HashMap<String, JsonObject>()

    /** Thaumcraft aspect tag -> colour (`Aspect.getColor()`, `0xRRGGBB`) of every registered aspect. */
    @Volatile
    var aspectColors: Map<String, Int> = emptyMap()
        private set

    /** The texture key of a raw jar asset (`<domain>:textures/<path>.png`), or null if the pack does not have it. */
    fun texture(raw: String): Key? = assets[raw]?.let(::textureKey)

    /** A Wavefront model by raw jar name (`<domain>:models/<name>.obj`). */
    fun obj(raw: String): ObjModel? = objModels[raw]

    /** A JSON (block) model by raw jar name (`<domain>:models/<name>.json`). */
    fun json(raw: String): JsonObject? = jsonModels[raw]

    override fun loadResources(roots: Iterable<Path>) {
        var index: JsonObject? = null
        var chiselJson: JsonObject? = null
        var myCtmJson: JsonObject? = null
        var aspectsJson: JsonObject? = null
        val objs = HashMap<String, ObjModel>()
        val jsons = HashMap<String, JsonObject>()
        for (root in roots) {
            pack.loadResourcePath(root) { path ->
                val dir = path.resolve(DIR)
                if (index == null) index = readJson(dir.resolve("assets.json"))
                if (chiselJson == null) chiselJson = readJson(dir.resolve("chisel.json"))
                if (myCtmJson == null) myCtmJson = readJson(dir.resolve("myctmlib.json"))
                if (aspectsJson == null) aspectsJson = readJson(dir.resolve("aspects.json"))
                // models must be read while the (zip) root is open
                index?.getAsJsonObject("assets")?.entrySet()?.forEach { (raw, value) ->
                    val obj = raw.endsWith(".obj")
                    if (!obj && !raw.endsWith(".json")) return@forEach
                    if (objs.containsKey(raw) || jsons.containsKey(raw)) return@forEach
                    val file = path.resolve(value.asString)
                    if (!file.isRegularFile()) return@forEach
                    try {
                        if (obj) {
                            Files.newBufferedReader(file).use { objs[raw] = ObjModel.parse(it) }
                        } else {
                            readJson(file)?.let { jsons[raw] = it }
                        }
                    } catch (ex: Exception) {
                        Logger.global.logWarning("Failed to read model '$file': $ex")
                    }
                }
            }
        }
        assets = index?.getAsJsonObject("assets")?.entrySet()?.associate { (k, v) -> k to v.asString } ?: emptyMap()
        chisel = chiselJson?.let(ChiselData::parse)
        myCtm = myCtmJson?.let(MyCtmData::parse)
        aspectColors = aspectsJson
            ?.getAsJsonObject("aspects")
            ?.entrySet()
            ?.mapNotNull { (tag, color) ->
                color.asString
                    .removePrefix("#")
                    .toIntOrNull(16)
                    ?.let { tag to it }
            }?.toMap() ?: emptyMap()
        synchronized(objModels) {
            objModels.clear()
            objModels.putAll(objs)
            jsonModels.clear()
            jsonModels.putAll(jsons)
        }
        if (index != null || chiselJson != null || myCtmJson != null) {
            Logger.global.logInfo(
                "Loaded GTNH mod data: ${assets.size} assets (${objs.size + jsons.size} models), " +
                    "${chisel?.size ?: 0} Chisel CTM variants, ${myCtm?.size ?: 0} MyCTMLib textures, ${aspectColors.size} aspects",
            )
        }
    }

    override fun collectUsedTextureKeys(): Set<Key> {
        val keys = HashSet<Key>()
        chisel?.let { keys.addAll(it.textureKeys()) }
        myCtm?.let { keys.addAll(it.textureKeys()) }
        for (raw in ModRenderers.RAW_TEXTURES) texture(raw)?.let(keys::add)
        return keys
    }

    private fun readJson(file: Path): JsonObject? {
        if (!file.isRegularFile()) return null
        return try {
            Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        } catch (ex: Exception) {
            Logger.global.logWarning("Failed to read GTNH mod data '$file': $ex")
            null
        }
    }

    companion object {
        const val DIR = "gtnh/mods"

        /** `assets/<ns>/textures/<path>.png` -> `<ns>:<path>`. */
        fun textureKey(packPath: String): Key? {
            val parts = packPath.split('/', limit = 4)
            if (parts.size < 4 || parts[0] != "assets" || parts[2] != "textures" || !parts[3].endsWith(".png")) return null
            return Key(parts[1], parts[3].removeSuffix(".png"))
        }

        @JvmField
        val TYPE: ResourcePack.Extension<ModAssets> =
            object : ResourcePack.Extension<ModAssets> {
                private val key = Key("gtnh", "mods")

                override fun getKey(): Key = key

                override fun create(pack: ResourcePack): ModAssets = ModAssets(pack)
            }
    }
}
