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

import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.DimensionType
import de.bluecolored.bluemap.core.world.mca.MCAWorld
import java.nio.file.Path

/**
 * Forge 1.7.10 dimensions: numeric ids stored in `DIM<id>` folders (overworld = world root).
 *
 * Keys: `minecraft:overworld` (0), `minecraft:the_nether` (-1), `minecraft:the_end` (1), `gtnh:dim_<id>` (any id).
 */
object LegacyDimensions {
    const val NAMESPACE: String = "gtnh"
    private const val DIM_PREFIX = "dim_"

    /** 1.7 overworld-like dimension: y 0..255, skylight. Used for every non-vanilla dimension by default. */
    @JvmField
    val OVERWORLD_TYPE: DimensionType = LegacyDimensionType(hasSkylight = true, hasCeiling = false, ambientLight = 0f)

    /** The vanilla nether/end builtins already use y 0..255. */
    @JvmField
    val NETHER_TYPE: DimensionType = DimensionType.NETHER

    @JvmField
    val END_TYPE: DimensionType = DimensionType.END

    /** The numeric dimension id for a dimension key, or null if the key is not a legacy dimension key. */
    @JvmStatic
    fun dimensionId(dimension: Key): Int? =
        when (dimension) {
            DataPack.DIMENSION_OVERWORLD -> {
                0
            }

            DataPack.DIMENSION_THE_NETHER -> {
                -1
            }

            DataPack.DIMENSION_THE_END -> {
                1
            }

            else -> {
                if (dimension.namespace == NAMESPACE && dimension.value.startsWith(DIM_PREFIX)) {
                    dimension.value.substring(DIM_PREFIX.length).toIntOrNull()
                } else {
                    null
                }
            }
        }

    /** The dimension key for a numeric dimension id. */
    @JvmStatic
    fun dimensionKey(id: Int): Key =
        when (id) {
            0 -> DataPack.DIMENSION_OVERWORLD
            -1 -> DataPack.DIMENSION_THE_NETHER
            1 -> DataPack.DIMENSION_THE_END
            else -> Key(NAMESPACE, "$DIM_PREFIX$id")
        }

    /** The save folder of a dimension: the world root for id 0, `DIM<id>` otherwise. */
    @JvmStatic
    fun resolveFolder(
        worldFolder: Path,
        dimension: Key,
    ): Path {
        val id = dimensionId(dimension) ?: return MCAWorld.resolveDimensionFolder(worldFolder, dimension)
        return if (id == 0) worldFolder else worldFolder.resolve("DIM$id")
    }

    /** The default dimension type of a legacy dimension. */
    @JvmStatic
    fun dimensionType(dimension: Key): DimensionType =
        when (dimensionId(dimension)) {
            -1 -> NETHER_TYPE
            1 -> END_TYPE
            else -> OVERWORLD_TYPE
        }

    private class LegacyDimensionType(
        private val hasSkylight: Boolean,
        private val hasCeiling: Boolean,
        private val ambientLight: Float,
    ) : DimensionType {
        override fun hasSkylight(): Boolean = hasSkylight

        override fun hasCeiling(): Boolean = hasCeiling

        override fun getAmbientLight(): Float = ambientLight

        override fun getMinY(): Int = 0

        override fun getHeight(): Int = 256

        override fun getFixedTime(): Long? = null

        override fun getCoordinateScale(): Double = 1.0
    }
}
