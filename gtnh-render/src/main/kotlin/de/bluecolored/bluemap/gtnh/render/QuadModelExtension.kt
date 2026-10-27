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
package de.bluecolored.bluemap.gtnh.render

import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePackExtension
import de.bluecolored.bluemap.core.util.Key
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Loads the [QuadModel]s of all resource packs (every `.json` below `assets/<ns>/models/gtnh_quads/`) and makes their textures
 * part of the atlas. Higher-priority roots come first and win, like BlueMap's own resource pools.
 */
class QuadModelExtension(private val pack: ResourcePack) : ResourcePackExtension {
    private val models = ConcurrentHashMap<Key, QuadModel>()

    operator fun get(key: Key): QuadModel? = models[key]

    val size: Int get() = models.size

    override fun loadResources(roots: Iterable<Path>) {
        for (root in roots) pack.loadResourcePath(root, ::loadRoot)
        Logger.global.logDebug("Loaded ${models.size} GTNH quad models")
    }

    private fun loadRoot(root: Path) {
        val assets = root.resolve("assets")
        if (!assets.isDirectory()) return
        Files.list(assets).use { namespaces ->
            for (namespace in namespaces) {
                val dir = namespace.resolve("models").resolve(MODEL_DIR)
                if (!dir.isDirectory()) continue
                val ns = namespace.name.removeSuffix("/")
                Files.walk(dir).use { files ->
                    files.filter { it.isRegularFile() && it.extension == "json" }.forEach { file ->
                        val relative = dir.relativize(file).toString().replace(file.fileSystem.separator, "/")
                        val key = Key(ns, MODEL_DIR + "/" + relative.removeSuffix(".json"))
                        if (models.containsKey(key)) return@forEach
                        try {
                            Files.newBufferedReader(file).use { models[key] = QuadModel.parse(it) }
                        } catch (ex: Exception) {
                            Logger.global.logDebug("Failed to load quad model '$key': $ex")
                        }
                    }
                }
            }
        }
    }

    override fun collectUsedTextureKeys(): Set<Key> {
        val keys = HashSet<Key>()
        for (model in models.values) keys.addAll(model.textures)
        return keys
    }

    companion object {
        const val MODEL_DIR = "gtnh_quads"

        @JvmField
        val TYPE: ResourcePack.Extension<QuadModelExtension> =
            object : ResourcePack.Extension<QuadModelExtension> {
                private val key = Key("gtnh", "quads")

                override fun getKey(): Key = key

                override fun create(pack: ResourcePack): QuadModelExtension = QuadModelExtension(pack)
            }
    }
}
