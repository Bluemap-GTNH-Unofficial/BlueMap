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

import com.google.gson.GsonBuilder
import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePackExtension
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockProperties
import de.bluecolored.bluemap.core.world.BlockState
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug statistic: which block states BlueMap could not resolve to a model while rendering.
 *
 * Enabled with the system property `bluemap.gtnh.coverageReport=<file.json>`; the report is written when the JVM
 * exits. Every distinct non-air state whose block properties BlueMap computes (i.e. every state it meets while
 * rendering) is classified once:
 * - `ok`: a variant matching the state resolves to a model (or uses a non-default renderer that has its data),
 * - `fallback`: no variant condition matches, BlueMap falls back to the blockstate's first variant (which resolves),
 * - `no_blockstate`: no blockstate file for the state's id (BlueMap renders nothing),
 * - `no_variant`: a blockstate file exists but yields no variant at all (renders nothing),
 * - `missing_model`: the variants point at models that do not exist (BlueMap's missing-model renderer).
 */
object CoverageReport {
    const val PROPERTY = "bluemap.gtnh.coverageReport"

    enum class Status { OK, FALLBACK, NO_BLOCKSTATE, NO_VARIANT, MISSING_MODEL }

    private val states = ConcurrentHashMap<BlockState, Status>()

    val enabled: Boolean get() = System.getProperty(PROPERTY) != null

    @JvmField
    val TYPE: ResourcePack.Extension<Extension> = object : ResourcePack.Extension<Extension> {
        private val key = Key("gtnh", "coverage_report")

        override fun getKey(): Key = key

        override fun create(pack: ResourcePack): Extension = Extension(pack)
    }

    class Extension(private val pack: ResourcePack) : ResourcePackExtension {
        override fun getBlockProperties(blockState: BlockState, propertiesBuilder: BlockProperties.Builder) {
            if (blockState.isAir) return
            states[blockState] = classify(pack, blockState)
        }
    }

    fun classify(pack: ResourcePack, state: BlockState): Status {
        val resource = pack.getBlockState(state) ?: return Status.NO_BLOCKSTATE
        var variants = 0
        var resolved = 0
        val quads = pack.getExtension(QuadModelExtension.TYPE)
        resource.forEach(state, 0, 0, 0) { variant ->
            variants++
            val found = when (variant.renderer) {
                GtnhRender.QUADS -> quads?.get(variant.model) != null
                BlockRendererType.DEFAULT, BlockRendererType.LIQUID -> variant.model.getResource(pack.models::get) != null
                BlockRendererType.MISSING -> false
                else -> true
            }
            if (found) resolved++
        }
        return when {
            variants == 0 -> Status.NO_VARIANT
            resolved == 0 -> Status.MISSING_MODEL
            isFallback(resource, state) -> Status.FALLBACK
            else -> Status.OK
        }
    }

    private fun isFallback(
        resource: de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState,
        state: BlockState,
    ): Boolean {
        val variants = resource.variants ?: return false
        if (variants.defaultVariant != null) return false
        return variants.variants.none { it.condition.matches(state) }
    }

    /** Snapshot: namespace -> status -> sorted state strings. */
    fun snapshot(): Map<String, Map<String, List<String>>> {
        val result = TreeMap<String, TreeMap<String, MutableList<String>>>()
        for ((state, status) in states) {
            result.getOrPut(state.id.namespace) { TreeMap() }
                .getOrPut(status.name.lowercase()) { ArrayList() }
                .add(state.toString())
        }
        result.values.forEach { byStatus -> byStatus.values.forEach { it.sort() } }
        return result
    }

    fun write(file: Path) {
        val snapshot = snapshot()
        val summary = snapshot.mapValues { (_, byStatus) -> byStatus.mapValues { it.value.size } }
        val json = mapOf("summary" to summary, "states" to snapshot)
        file.parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(file).use { GsonBuilder().setPrettyPrinting().create().toJson(json, it) }
    }

    internal fun install() {
        val file = System.getProperty(PROPERTY) ?: return
        ResourcePack.Extension.REGISTRY.register(TYPE)
        Runtime.getRuntime().addShutdownHook(
            Thread({
                try {
                    write(Path.of(file))
                } catch (ex: Exception) {
                    Logger.global.logError("Failed to write GTNH coverage report to $file", ex)
                }
            }, "gtnh-coverage-report"),
        )
    }
}
