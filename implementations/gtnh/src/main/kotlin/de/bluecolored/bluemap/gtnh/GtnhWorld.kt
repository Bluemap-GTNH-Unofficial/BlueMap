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
package de.bluecolored.bluemap.gtnh

import de.bluecolored.bluemap.common.serverinterface.ServerWorld
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.legacy.GtnhLegacy
import de.bluecolored.bluemap.gtnh.legacy.LegacyDimensions
import net.minecraft.world.MinecraftException
import net.minecraft.world.storage.ThreadedFileIOBase
import net.minecraftforge.common.DimensionManager
import java.io.IOException
import java.nio.file.Path
import java.util.Optional

/**
 * One Forge dimension of the server's save. It is identified by its numeric id, not by a `WorldServer` instance:
 * Forge loads and unloads dimensions at runtime, the map stays.
 */
class GtnhWorld(
    private val serverThread: ServerThread,
    private val worldFolder: Path,
    val dimensionId: Int,
) : ServerWorld {
    private val dimension: Key = LegacyDimensions.dimensionKey(dimensionId)

    override fun getWorldFolder(): Path = worldFolder

    override fun getDimension(): Key = dimension

    /** Non-vanilla dimensions use the dimension type generated from their `WorldProvider`, see [RuntimeDataPack]. */
    override fun getDimensionType(): Optional<Key> =
        if (dimensionId in VANILLA_DIMENSIONS) Optional.empty() else Optional.of(RuntimeDataPack.dimensionTypeKey(dimensionId))

    override fun getWorldLoader(): Optional<Key> = Optional.of(GtnhLegacy.ANVIL_1_7_KEY)

    /**
     * Saves all chunks of the dimension on the server thread, then waits until the chunk IO thread has written them.
     *
     * Deliberately not `saveChunkData()` (what `save-all flush` does): writing the queue from a second thread races
     * with the IO thread, and the older write of a chunk can land last (see Hodgepodge PR #1018).
     */
    @Throws(IOException::class)
    override fun persistWorldChanges(): Boolean {
        val saved =
            serverThread.call(SAVE_TIMEOUT_SECONDS) {
                val world = DimensionManager.getWorld(dimensionId) ?: return@call true // not loaded: nothing unsaved
                if (world.levelSaving) return@call false // saving is disabled (save-off)
                try {
                    world.saveAllChunks(true, null)
                } catch (e: MinecraftException) {
                    throw IOException(e)
                }
                true
            }
        if (!saved) return false

        try {
            ThreadedFileIOBase.threadedIOInstance.waitForFinish()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(e)
        }
        return true
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is GtnhWorld && dimensionId == other.dimensionId && worldFolder == other.worldFolder)

    override fun hashCode(): Int = 31 * worldFolder.hashCode() + dimensionId

    override fun toString(): String = "GtnhWorld($worldFolder, DIM$dimensionId)"

    private companion object {
        val VANILLA_DIMENSIONS = setOf(0, -1, 1)
        const val SAVE_TIMEOUT_SECONDS = 60L
    }
}
