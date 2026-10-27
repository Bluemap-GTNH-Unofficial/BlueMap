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

import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.legacy.VanillaLegacyBiomeMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * A datapack (in BlueMap's `packs/` folder) describing what only the running server knows:
 * - `data/gtnh/dimension_type/dim_<id>_type.json` from each non-vanilla dimension's `WorldProvider`,
 *   used through the `dimension-type` of the auto-generated map configs ([GtnhWorld.getDimensionType]);
 * - `data/gtnh/worldgen/biome/biome_<id>.json` for every modded biome (ids without a vanilla 1.7.10 biome),
 *   which the legacy loader resolves by [VanillaLegacyBiomeMapper.runtimeKey].
 *
 * The files are plain BlueMap datapack resources, so the pack can be copied to a CLI setup rendering the same world.
 * BlueMap reads them on (re)load.
 */
class RuntimeDataPack(
    private val root: Path,
) {
    /** A 1.7.10 `BiomeGenBase`. */
    data class BiomeInfo(
        val id: Int,
        val temperature: Float,
        val rainfall: Float,
        val waterColorMultiplier: Int,
    )

    /** A registered 1.7.10 `WorldProvider` (`registerWorld` sets sky, ceiling and the light table). */
    data class DimensionInfo(
        val id: Int,
        val natural: Boolean,
        val hasSkylight: Boolean,
        val hasCeiling: Boolean,
        val ambientLight: Float,
        val coordinateScale: Double,
    )

    /** Writes the files of the modded biomes, replacing the ones of a previous run. */
    @Throws(IOException::class)
    fun writeBiomes(biomes: Collection<BiomeInfo>) {
        val namespace = VanillaLegacyBiomeMapper.runtimeKey(0).namespace
        val folder = root.resolve("data").resolve(namespace).resolve("worldgen").resolve("biome")
        val written = HashSet<Path>()
        for (biome in biomes) {
            if (VanillaLegacyBiomeMapper.legacyKey(biome.id) != null) continue
            val file = folder.resolve(VanillaLegacyBiomeMapper.runtimeKey(biome.id).value + ".json")
            write(file, biomeJson(biome))
            written.add(file)
        }
        deleteOthers(folder, written)
    }

    /**
     * Writes the dimension type of a non-vanilla dimension. Only loaded dimensions have a registered provider,
     * the file of a dimension that is not loaded is kept from the last time it was.
     */
    @Throws(IOException::class)
    fun writeDimensionType(dimension: DimensionInfo) {
        val key = dimensionTypeKey(dimension.id)
        write(root.resolve("data").resolve(key.namespace).resolve("dimension_type").resolve(key.value + ".json"), dimensionTypeJson(dimension))
    }

    private fun write(
        file: Path,
        content: String,
    ) {
        Files.createDirectories(file.parent)
        if (Files.isRegularFile(file) && Files.readString(file) == content) return
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, content)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun deleteOthers(
        folder: Path,
        keep: Set<Path>,
    ) {
        if (!Files.isDirectory(folder)) return
        Files.list(folder).use { files ->
            files.filter { it !in keep && it.fileName.toString().endsWith(".json") }.forEach(Files::delete)
        }
    }

    companion object {
        const val NAMESPACE: String = "gtnh"

        /** Folder name of the pack inside BlueMap's `packs/` folder. */
        const val PACK_NAME: String = "gtnh-runtime"

        /** 1.13+ default water colour; 1.7.10 stores a multiplier on the (already blue) water texture instead. */
        const val DEFAULT_WATER_COLOR: Int = 0x3F76E4

        /**
         * The dimension type key of a non-vanilla dimension. Must differ from the dimension key `gtnh:dim_<id>`:
         * BlueMap omits `dimension-type` from map configs when both are equal.
         */
        @JvmStatic
        fun dimensionTypeKey(dimensionId: Int): Key = Key(NAMESPACE, "dim_${dimensionId}_type")

        private fun biomeJson(biome: BiomeInfo): String =
            String.format(
                Locale.ROOT,
                "{\n  \"temperature\": %s,\n  \"downfall\": %s,\n  \"effects\": {\n    \"water_color\": %d\n  }\n}\n",
                biome.temperature,
                biome.rainfall,
                multiply(DEFAULT_WATER_COLOR, biome.waterColorMultiplier),
            )

        private fun dimensionTypeJson(dimension: DimensionInfo): String =
            String.format(
                Locale.ROOT,
                "{\n  \"natural\": %b,\n  \"has_skylight\": %b,\n  \"has_ceiling\": %b,\n  \"ambient_light\": %s,\n" +
                    "  \"min_y\": 0,\n  \"height\": 256,\n  \"coordinate_scale\": %s\n}\n",
                dimension.natural,
                dimension.hasSkylight,
                dimension.hasCeiling,
                dimension.ambientLight,
                dimension.coordinateScale,
            )

        /** Per-channel `color * multiplier / 255` of two 0xRRGGBB colours. */
        fun multiply(
            color: Int,
            multiplier: Int,
        ): Int {
            var result = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val channel = ((color shr shift) and 0xFF) * ((multiplier shr shift) and 0xFF) / 255
                result = result or (channel shl shift)
            }
            return result
        }

        fun logWriteFailure(
            what: String,
            e: Exception,
        ) {
            Logger.global.logWarning("Failed to write the GTNH runtime datapack ($what): $e")
        }
    }
}
