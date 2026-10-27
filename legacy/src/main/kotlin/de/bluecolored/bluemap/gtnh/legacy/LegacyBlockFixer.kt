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

import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.World

/**
 * Block access in world coordinates for [LegacyBlockFixer]s, across chunk borders.
 * Positions outside the world (or in chunks that do not exist) read as air.
 */
interface LegacyBlockAccess {
    /** The mapped state before any [LegacyBlockFixer] ran (the flattened table state for vanilla blocks). */
    fun baseState(
        x: Int,
        y: Int,
        z: Int,
    ): BlockState

    /** The final state (fixers applied). Fixers may only call this for neighbours whose fixers use [baseState] only. */
    fun state(
        x: Int,
        y: Int,
        z: Int,
    ): BlockState

    companion object {
        /** Everything is air (no world, e.g. a chunk loaded on its own). */
        @JvmField
        val EMPTY: LegacyBlockAccess =
            object : LegacyBlockAccess {
                override fun baseState(
                    x: Int,
                    y: Int,
                    z: Int,
                ): BlockState = BlockState.AIR

                override fun state(
                    x: Int,
                    y: Int,
                    z: Int,
                ): BlockState = BlockState.AIR
            }
    }
}

/**
 * Computes the final state of a block from its mapped [state], its neighbours and its block entity
 * (the parts of a 1.13 world upgrade that the flattening table alone can not express).
 *
 * Chunks call this lazily, once per block position that has a fixer, and cache the result. Must be thread-safe
 * and deterministic.
 */
fun interface LegacyBlockFixer {
    fun fix(
        state: BlockState,
        x: Int,
        y: Int,
        z: Int,
        blocks: LegacyBlockAccess,
        blockEntity: BlockEntity?,
    ): BlockState
}

/** [LegacyBlockAccess] over the chunks of a world (used for neighbours outside the asking chunk). */
class WorldBlockAccess(
    private val world: World,
) : LegacyBlockAccess {
    override fun baseState(
        x: Int,
        y: Int,
        z: Int,
    ): BlockState =
        when (val chunk = world.getChunkAtBlock(x, z)) {
            is Chunk_1_7 -> chunk.getBaseBlockState(x, y, z)
            else -> chunk.getBlockState(x, y, z)
        }

    override fun state(
        x: Int,
        y: Int,
        z: Int,
    ): BlockState = world.getChunkAtBlock(x, z).getBlockState(x, y, z)
}
