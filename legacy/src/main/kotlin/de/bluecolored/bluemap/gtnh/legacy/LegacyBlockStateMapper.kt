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
import de.bluecolored.bluemap.core.world.BlockState
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps a legacy numeric block (id, 16-bit meta) to a BlueMap [BlockState].
 *
 * Implementations must be thread-safe and should return the same instance for repeated calls,
 * chunks only call this once per distinct (id, meta) in a section.
 */
fun interface LegacyBlockStateMapper {
    fun map(
        id: Int,
        meta: Int,
    ): BlockState
}

/**
 * Identity mapping of the FML registry:
 * `BlockState(Key(modid, name), {meta: "<meta>"})` with the registry name verbatim,
 * id 0 -> [BlockState.AIR], unregistered ids -> `bluemap-gtnh:unknown[id=<id>,meta=<meta>]`.
 */
class RegistryBlockStateMapper(
    private val registry: FmlBlockRegistry,
) : LegacyBlockStateMapper {
    private val cache = ConcurrentHashMap<Long, BlockState>()

    override fun map(
        id: Int,
        meta: Int,
    ): BlockState {
        if (id == 0) return BlockState.AIR
        return cache.computeIfAbsent((id.toLong() shl 16) or (meta.toLong() and 0xFFFF)) { create(id, meta) }
    }

    private fun create(
        id: Int,
        meta: Int,
    ): BlockState {
        val name =
            registry.name(id)
                ?: return BlockState(UNKNOWN_KEY, mapOf("id" to id.toString(), "meta" to meta.toString()))
        return BlockState(Key.parse(name), mapOf(META to meta.toString()))
    }

    companion object {
        const val META: String = "meta"

        @JvmField
        val UNKNOWN_KEY: Key = Key("bluemap-gtnh", "unknown")
    }
}
