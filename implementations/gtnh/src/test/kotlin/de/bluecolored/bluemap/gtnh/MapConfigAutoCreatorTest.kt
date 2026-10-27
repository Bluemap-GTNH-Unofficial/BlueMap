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

import de.bluecolored.bluemap.common.config.ConfigManager
import de.bluecolored.bluemap.common.config.MapConfig
import de.bluecolored.bluemap.common.serverinterface.ServerWorld
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.gtnh.legacy.GtnhLegacy
import de.bluecolored.bluemap.gtnh.legacy.LegacyDimensions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

class MapConfigAutoCreatorTest {
    @TempDir lateinit var configRoot: Path

    @TempDir lateinit var serverRoot: Path

    private val maps: Path get() = configRoot.resolve("maps")

    @Test
    fun firstStartCreatesAMapPerDimensionLikeUpstream() {
        val created = MapConfigAutoCreator(configRoot).createMissing(listOf(world(64), world(0), world(1)))
        assertEquals(listOf("world", "world_gtnh_dim_64", "world_the_end"), created)
        val dim64 = load("world_gtnh_dim_64")
        assertEquals(GtnhLegacy.ANVIL_1_7, dim64.loader)
        assertEquals(Key("gtnh", "dim_64"), dim64.dimension)
        assertEquals(RuntimeDataPack.dimensionTypeKey(64), dim64.dimensionType)
        assertEquals("World (gtnh:dim_64)", dim64.name)
        assertEquals(worldFolder(), dim64.world!!.toAbsolutePath().normalize())
        assertEquals(LegacyDimensions.dimensionKey(1), load("world_the_end").dimension)
    }

    @Test
    fun deletedConfigStaysDeletedAndNewDimensionIsAdded() {
        MapConfigAutoCreator(configRoot).createMissing(listOf(world(0), world(64)))
        Files.delete(maps.resolve("world_gtnh_dim_64.conf"))
        val overworld = Files.readString(maps.resolve("world.conf"))

        val created = MapConfigAutoCreator(configRoot).createMissing(listOf(world(0), world(64), world(7)))

        assertEquals(listOf("world_gtnh_dim_7"), created)
        assertEquals(setOf("world.conf", "world_gtnh_dim_7.conf"), mapFiles())
        assertEquals(overworld, Files.readString(maps.resolve("world.conf")))
        assertEquals(emptyList<String>(), MapConfigAutoCreator(configRoot).createMissing(listOf(world(0), world(64), world(7))))
    }

    @Test
    fun dimensionWithAHandWrittenConfigIsNotDuplicated() {
        Files.createDirectories(maps)
        MapConfigAutoCreator(configRoot).createMissing(listOf(world(0)))
        Files.move(maps.resolve("world.conf"), maps.resolve("my-overworld.conf"))
        Files.delete(configRoot.resolve(MapConfigAutoCreator.RECORD_FILE_NAME)) // e.g. a config folder from elsewhere

        val created = MapConfigAutoCreator(configRoot).createMissing(listOf(world(0), world(-1)))

        assertEquals(listOf("world_the_nether"), created)
        assertEquals(setOf("my-overworld.conf", "world_the_nether.conf"), mapFiles())
    }

    private fun mapFiles(): Set<String> = Files.list(maps).use { files -> files.map { it.fileName.toString() }.toList().toSet() }

    private fun load(id: String): MapConfig = ConfigManager(configRoot).loadConfig(maps.resolve("$id.conf"), MapConfig::class.java)

    private fun worldFolder(): Path = serverRoot.resolve("World").toAbsolutePath().normalize()

    private fun world(dimensionId: Int): ServerWorld =
        object : ServerWorld {
            override fun getWorldFolder(): Path = worldFolder()

            override fun getDimension(): Key = LegacyDimensions.dimensionKey(dimensionId)

            override fun getDimensionType(): Optional<Key> =
                if (dimensionId in -1..1) Optional.empty() else Optional.of(RuntimeDataPack.dimensionTypeKey(dimensionId))

            override fun getWorldLoader(): Optional<Key> = Optional.of(GtnhLegacy.ANVIL_1_7_KEY)
        }

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerLoader() {
            GtnhLegacy.register()
        }
    }
}
