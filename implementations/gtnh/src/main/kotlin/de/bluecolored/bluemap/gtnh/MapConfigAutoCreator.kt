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

import de.bluecolored.bluemap.common.config.BlueMapConfigManager
import de.bluecolored.bluemap.common.config.ConfigManager
import de.bluecolored.bluemap.common.config.ConfigurationException
import de.bluecolored.bluemap.common.config.MapConfig
import de.bluecolored.bluemap.common.serverinterface.ServerWorld
import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.util.Key
import org.spongepowered.configurate.hocon.HoconConfigurationLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * Creates the map-configs of dimensions that have none yet, on every start (upstream BlueMap only does this when the
 * `maps` folder does not exist). Uses the same templates as upstream ([BlueMapConfigManager.createMapConfigTemplate]).
 *
 * Every dimension that got (or already had) a map-config is remembered in [RECORD_FILE_NAME], per world folder name,
 * and never gets one generated again: a map-config an admin deleted stays deleted. Existing map-configs are never
 * modified; a dimension counts as having one if any config in `maps/` points to its world folder and dimension.
 */
class MapConfigAutoCreator(
    configRoot: Path,
) {
    private val configManager = ConfigManager(configRoot)
    private val mapsFolder = configRoot.resolve(BlueMapConfigManager.MAPS_CONFIG_FOLDER_NAME)
    private val recordFile = configRoot.resolve(RECORD_FILE_NAME)

    /** Creates the missing map-configs for [worlds] and returns the ids of the created maps. */
    @Throws(IOException::class)
    fun createMissing(worlds: Collection<ServerWorld>): List<String> {
        Files.createDirectories(mapsFolder)
        val known = readRecord()
        val existingIds = HashSet<String>()
        val configured = HashSet<Pair<Path, Key>>()
        readExistingConfigs(existingIds, configured)

        val created = ArrayList<String>()
        // overworlds first: their map-id omits the dimension (as upstream)
        for (world in worlds.sortedBy { if (it.dimension == DataPack.DIMENSION_OVERWORLD) 0 else 1 }) {
            val worldFolder = world.worldFolder.toAbsolutePath().normalize()
            val folderName = worldFolder.fileName.toString()
            val knownOfWorld = known.getOrPut(folderName) { sortedSetOf() }
            val dimension = world.dimension.formatted

            if (dimension in knownOfWorld) continue
            knownOfWorld.add(dimension)
            if ((worldFolder to world.dimension) in configured) continue

            val id = createConfig(world, folderName, existingIds)
            existingIds.add(id)
            created.add(id)
        }

        writeRecord(known)
        return created
    }

    private fun createConfig(
        world: ServerWorld,
        folderName: String,
        existingIds: Set<String>,
    ): String {
        val dimension = world.dimension
        val dimensionName = if (dimension.namespace == "minecraft") dimension.value else dimension.formatted

        // same ids and names as upstream's first-start generation
        val folderId = sanitiseMapId(folderName)
        val id = if (dimension == DataPack.DIMENSION_OVERWORLD && folderId !in existingIds) folderId else sanitiseMapId("${folderName}_$dimensionName")
        var i = 1
        var uniqueId = id
        while (uniqueId in existingIds) uniqueId = id + "_" + (++i)
        var name = "$folderName ($dimensionName)"
        if (i > 1) name = "$name ($i)"

        val template = BlueMapConfigManager.createMapConfigTemplate(configManager, world, name, i - 1)
        Files.writeString(
            configManager.resolveConfigFile(BlueMapConfigManager.MAPS_CONFIG_FOLDER_NAME + "/" + uniqueId),
            template.build(),
            StandardOpenOption.CREATE_NEW,
        )
        Logger.global.logInfo("Created map-config '$uniqueId' for dimension '${dimension.formatted}'")
        return uniqueId
    }

    private fun readExistingConfigs(
        ids: MutableSet<String>,
        configured: MutableSet<Pair<Path, Key>>,
    ) {
        Files.list(mapsFolder).use { files ->
            for (file in files.filter(configManager::isConfigFile).sorted().toList()) {
                ids.add(sanitiseMapId(configManager.getConfigName(file)))
                try {
                    val config = configManager.loadConfig(file, MapConfig::class.java)
                    val world = config.world ?: continue
                    configured.add(world.toAbsolutePath().normalize() to (config.dimension ?: DataPack.DIMENSION_OVERWORLD))
                } catch (e: ConfigurationException) {
                    Logger.global.logWarning("Could not read map-config '$file' to check its dimension: ${e.message}")
                }
            }
        }
    }

    private fun readRecord(): MutableMap<String, MutableSet<String>> {
        val record = HashMap<String, MutableSet<String>>()
        if (!Files.isRegularFile(recordFile)) return record
        val root = HoconConfigurationLoader.builder().path(recordFile).build().load()
        for ((folder, dimensions) in root.node(RECORD_KEY).childrenMap()) {
            record[folder.toString()] = dimensions.getList(String::class.java).orEmpty().toSortedSet()
        }
        return record
    }

    private fun writeRecord(record: Map<String, Set<String>>) {
        val content =
            buildString {
                append("# Dimensions that BlueMap created a map-config for (or found one for), per world folder.\n")
                append("# A dimension listed here never gets a map-config generated again, so deleted map-configs stay deleted.\n")
                append("# Remove a dimension from its list to have its map-config generated on the next server start.\n")
                append(RECORD_KEY).append(": {\n")
                for ((folder, dimensions) in record.toSortedMap()) {
                    append("  ").append(quote(folder)).append(": [")
                    dimensions.joinTo(this, ", ") { quote(it) }
                    append("]\n")
                }
                append("}\n")
            }
        if (Files.isRegularFile(recordFile) && Files.readString(recordFile) == content) return
        Files.writeString(recordFile, content)
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun sanitiseMapId(id: String): String = id.replace(Regex("\\W"), "_").lowercase(Locale.ROOT)

    companion object {
        const val RECORD_FILE_NAME: String = "gtnh-known-dimensions.conf"
        private const val RECORD_KEY = "known-dimensions"
    }
}
