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

import com.google.gson.GsonBuilder
import de.bluecolored.bluemap.core.logger.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Counts, per mod renderer, the blocks it drew itself and the blocks it handed to the variant's static model
 * (exporter capture / bounding box). Written as JSON at shutdown when the system property [PROPERTY] names a file.
 */
object ModRenderStats {
    const val PROPERTY = "bluemap.gtnh.modRenderStats"

    private val rendered = ConcurrentHashMap<String, LongAdder>()
    private val fallbacks = ConcurrentHashMap<String, LongAdder>()

    private val enabled: Boolean = System.getProperty(PROPERTY) != null

    fun rendered(renderer: Any) {
        if (enabled) rendered.computeIfAbsent(renderer.javaClass.simpleName) { LongAdder() }.increment()
    }

    fun fallback(renderer: Any) {
        if (enabled) fallbacks.computeIfAbsent(renderer.javaClass.simpleName) { LongAdder() }.increment()
    }

    fun snapshot(): Map<String, Map<String, Long>> {
        val result = TreeMap<String, Map<String, Long>>()
        for (name in rendered.keys + fallbacks.keys) {
            result[name] =
                mapOf(
                    "rendered" to (rendered[name]?.sum() ?: 0L),
                    "fallback" to (fallbacks[name]?.sum() ?: 0L),
                )
        }
        return result
    }

    internal fun install() {
        val file = System.getProperty(PROPERTY) ?: return
        Runtime.getRuntime().addShutdownHook(
            Thread({
                try {
                    val path = Path.of(file)
                    path.parent?.let { Files.createDirectories(it) }
                    Files.newBufferedWriter(path).use { GsonBuilder().setPrettyPrinting().create().toJson(snapshot(), it) }
                } catch (ex: Exception) {
                    Logger.global.logError("Failed to write GTNH mod render stats to $file", ex)
                }
            }, "gtnh-mod-render-stats"),
        )
    }
}
