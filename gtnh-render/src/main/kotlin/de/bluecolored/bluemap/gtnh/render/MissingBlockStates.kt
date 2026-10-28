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

import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePackExtension
import de.bluecolored.bluemap.core.util.Key

/**
 * Modded (non-`minecraft`) block ids without any blockstate file resolve to BlueMap's `bluemap:missing` blockstate,
 * so they are drawn as the missing-model cube (through `bluemap:missing`, which also lets fallback renderer types
 * claim them) instead of nothing. Without this, BlueMap renders no geometry for them while their default block
 * properties (culling, occluding) still hide the neighbours' faces, leaving holes in the map.
 *
 * Blocks that have a blockstate file but no variant for a meta keep BlueMap's behaviour (first variant).
 */
class MissingBlockStates(
    private val pack: ResourcePack,
) : ResourcePackExtension {
    override fun getBlockStateKey(key: Key): Key {
        if (key.namespace == Key.MINECRAFT_NAMESPACE || key.namespace == Key.BLUEMAP_NAMESPACE) return key
        return if (pack.blockStates[key] == null) ResourcePack.MISSING_BLOCK_STATE else key
    }

    companion object {
        @JvmField
        val TYPE: ResourcePack.Extension<MissingBlockStates> =
            object : ResourcePack.Extension<MissingBlockStates> {
                private val key = Key("gtnh", "missing_block_states")

                override fun getKey(): Key = key

                override fun create(pack: ResourcePack): MissingBlockStates = MissingBlockStates(pack)
            }
    }
}
