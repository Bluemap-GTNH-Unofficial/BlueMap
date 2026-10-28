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
import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePackExtension
import de.bluecolored.bluemap.core.util.Key
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Loads the exporter's GregTech dump (the JSON files in `gtnh/gregtech/`) once per resource-pack load. Each file comes from the
 * highest-priority pack that has it. Its textures are added to the atlas.
 */
class GregTechExtension(
    private val pack: ResourcePack,
) : ResourcePackExtension {
    @Volatile
    var data: GtData? = null
        private set

    override fun loadResources(roots: Iterable<Path>) {
        val files = HashMap<String, JsonObject>()
        for (root in roots) {
            pack.loadResourcePath(root) { path ->
                for (name in FILES) {
                    if (files.containsKey(name)) continue
                    val file = path.resolve(DIR).resolve("$name.json")
                    if (!file.isRegularFile()) continue
                    try {
                        Files.newBufferedReader(file).use { files[name] = JsonParser.parseReader(it).asJsonObject }
                    } catch (ex: Exception) {
                        Logger.global.logWarning("Failed to read GregTech dump file '$file': $ex")
                    }
                }
            }
        }
        if (files.isEmpty()) {
            Logger.global.logDebug("No GregTech dump ($DIR) in the resource packs")
            return
        }
        val loaded = GtDataParser.parse(files["mte"], files["casings"], files["materials"], files["ores"])
        data = loaded
        Logger.global.logInfo(
            "Loaded GregTech dump: ${loaded.mtes.size} MTEs, ${loaded.casings.size} casings, " +
                "${loaded.materials.size} materials, ${loaded.oreBlocks.size} ore blocks",
        )
    }

    override fun collectUsedTextureKeys(): Set<Key> = data?.textureKeys() ?: emptySet()

    companion object {
        const val DIR = "gtnh/gregtech"
        private val FILES = listOf("mte", "casings", "materials", "ores")

        @JvmField
        val TYPE: ResourcePack.Extension<GregTechExtension> =
            object : ResourcePack.Extension<GregTechExtension> {
                private val key = Key("gtnh", "gregtech")

                override fun getKey(): Key = key

                override fun create(pack: ResourcePack): GregTechExtension = GregTechExtension(pack)
            }
    }
}
