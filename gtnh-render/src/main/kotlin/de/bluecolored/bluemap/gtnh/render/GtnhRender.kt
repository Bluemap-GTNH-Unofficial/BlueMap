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

import de.bluecolored.bluemap.core.map.hires.block.BlockRendererFactory
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.util.Key

/**
 * Entry point of the BlueMap-side GTNH rendering support. Call [register] before BlueMap loads its resources.
 * Blockstate variants then select the captured-quads renderer with `"renderer": "gtnh:quads"`.
 */
object GtnhRender {
    @JvmField
    val QUADS_KEY: Key = Key("gtnh", "quads")

    @JvmField
    val QUADS: BlockRendererType = BlockRendererType.Impl(
        QUADS_KEY,
        BlockRendererFactory { pack, textureGallery, renderSettings -> QuadModelRenderer(pack, textureGallery, renderSettings) },
    )

    @Volatile
    private var registered = false

    /**
     * Registers the `gtnh:quads` renderer and its resource-pack extension, plus the coverage report if the system
     * property [CoverageReport.PROPERTY] is set. Idempotent; touches no Minecraft classes.
     */
    @JvmStatic
    @Synchronized
    fun register() {
        if (registered) return
        registered = true
        BlockRendererType.REGISTRY.register(QUADS)
        ResourcePack.Extension.REGISTRY.register(QuadModelExtension.TYPE)
        CoverageReport.install()
    }
}

/** Native BlueMap addon entrypoint (see `bluemap.addon.json` in the addon jar). */
class GtnhRenderAddon : Runnable {
    override fun run() = GtnhRender.register()
}
