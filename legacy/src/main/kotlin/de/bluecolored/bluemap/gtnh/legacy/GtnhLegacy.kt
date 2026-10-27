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
package de.bluecolored.bluemap.gtnh.legacy

import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.WorldLoaderType

/**
 * Entry point of the GTNH legacy (Minecraft 1.7.10) world support.
 * Call [register] before BlueMap loads its map configs; maps then select it with `loader: "gtnh:anvil_1_7"`.
 */
object GtnhLegacy {
    @JvmField
    val ANVIL_1_7_KEY: Key = Key("gtnh", "anvil_1_7")

    @JvmField
    val ANVIL_1_7: WorldLoaderType = WorldLoaderType.Impl(ANVIL_1_7_KEY, LegacyWorldLoader())

    /** Registers the `gtnh:anvil_1_7` world-loader. Idempotent. */
    @JvmStatic
    fun register() {
        WorldLoaderType.REGISTRY.register(ANVIL_1_7)
    }
}

/** Native BlueMap addon entrypoint (see `bluemap.addon.json` in the addon jar). */
class GtnhLegacyAddon : Runnable {
    override fun run() = GtnhLegacy.register()
}
