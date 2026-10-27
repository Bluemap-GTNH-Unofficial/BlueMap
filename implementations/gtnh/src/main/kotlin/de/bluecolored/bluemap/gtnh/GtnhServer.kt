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

import cpw.mods.fml.common.event.FMLServerStartingEvent
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.PlayerEvent
import cpw.mods.fml.common.gameevent.TickEvent
import de.bluecolored.bluemap.common.plugin.Plugin
import de.bluecolored.bluemap.common.serverinterface.Player
import de.bluecolored.bluemap.common.serverinterface.Server
import de.bluecolored.bluemap.common.serverinterface.ServerEventListener
import de.bluecolored.bluemap.common.serverinterface.ServerWorld
import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.gtnh.legacy.GtnhLegacy
import de.bluecolored.bluemap.gtnh.legacy.LegacyDimensions
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.server.MinecraftServer
import net.minecraft.world.MinecraftException
import net.minecraft.world.World
import net.minecraft.world.WorldServer
import net.minecraft.world.biome.BiomeGenBase
import net.minecraft.world.storage.ThreadedFileIOBase
import net.minecraftforge.common.DimensionManager
import net.minecraftforge.event.world.WorldEvent
import org.apache.logging.log4j.LogManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.name

/**
 * BlueMap's [Server] for a running 1.7.10 Forge server. Created on `FMLServerStartingEvent` (worlds are loaded, server
 * thread), lives until `FMLServerStoppingEvent`.
 */
class GtnhServer(
    private val minecraftServer: MinecraftServer,
) : Server {
    private val serverThread = ServerThread(Thread.currentThread())
    private val worldFolder: Path = DimensionManager.getCurrentSaveRootDirectory().toPath().toAbsolutePath().normalize()
    private val worlds = ConcurrentHashMap<Int, GtnhWorld>()
    private val runtimeDataPack = RuntimeDataPack(getConfigFolder().resolve("packs").resolve(RuntimeDataPack.PACK_NAME))

    private val listeners = CopyOnWriteArrayList<ServerEventListener>()
    private val onlinePlayers = ConcurrentHashMap<UUID, Player>()
    private val playerList: MutableList<GtnhPlayer> = Collections.synchronizedList(ArrayList())
    private var playerUpdateIndex = 0

    private val plugin: Plugin

    init {
        Logger.global.clear()
        Logger.global.put(Log4jLogger(LogManager.getLogger(Plugin.PLUGIN_NAME)))
        plugin = Plugin("gtnh", this)
    }

    fun onServerStarting(event: FMLServerStartingEvent) {
        GtnhLegacy.register()
        registerGtnhRender()
        event.registerServerCommand(BlueMapCommand(this, serverThread, plugin))
    }

    fun onServerStarted() {
        // write level.dat and all loaded chunks, so the world-files are complete before BlueMap reads them
        for (world in DimensionManager.getWorlds()) {
            try {
                world.saveAllChunks(true, null)
            } catch (e: MinecraftException) {
                Logger.global.logWarning("Failed to save DIM${world.provider.dimensionId}: $e")
            }
        }
        writeRuntimeDataPack()

        Thread({
            Logger.global.logInfo("Loading...")
            try {
                ThreadedFileIOBase.threadedIOInstance.waitForFinish()
                createMissingMapConfigs()
                plugin.load()
                if (plugin.isLoaded) Logger.global.logInfo("Loaded!")
            } catch (e: IOException) {
                Logger.global.logError("Failed to load bluemap!", e)
                plugin.unload()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "BlueMap-Plugin-Loading").start()
    }

    fun onServerStopping() {
        // nothing queued for the server thread can run anymore while BlueMap shuts down on it
        serverThread.close()
        plugin.unload()
        Logger.global.logInfo("BlueMap unloaded!")
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.START) {
            serverThread.drain()
            return
        }
        try {
            updateSomePlayers()
        } catch (e: RuntimeException) {
            Logger.global.logError("Exception trying to update players", e)
        }
    }

    /** Dimensions loaded later (portals, Galacticraft travel) refresh their dimension type for the next (re)load. */
    @SubscribeEvent
    fun onWorldLoad(event: WorldEvent.Load) {
        val world = event.world
        if (world.isRemote || world !is WorldServer) return
        writeDimensionType(world)
    }

    @SubscribeEvent
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.player as? EntityPlayerMP ?: return
        val gtnhPlayer = GtnhPlayer(this, player)
        val uuid = gtnhPlayer.getUuid()
        onlinePlayers[uuid] = gtnhPlayer
        playerList.add(gtnhPlayer)
        for (listener in listeners) listener.onPlayerJoin(uuid)
    }

    @SubscribeEvent
    fun onPlayerLeave(event: PlayerEvent.PlayerLoggedOutEvent) {
        val uuid = event.player.uniqueID
        onlinePlayers.remove(uuid)
        synchronized(playerList) { playerList.removeIf { it.getUuid() == uuid } }
        for (listener in listeners) listener.onPlayerLeave(uuid)
    }

    override fun getMinecraftVersion(): String = "1.7.10"

    override fun getConfigFolder(): Path = Path.of("config", BlueMapMod.MOD_ID)

    override fun getModsFolder(): Optional<Path> = Optional.of(Path.of("mods"))

    /**
     * Every dimension with at least one region file, loaded or not (Forge unloads idle dimensions; their maps should
     * still exist and update). Auto-generated map configs are created for exactly these (D17).
     */
    override fun getLoadedServerWorlds(): Collection<ServerWorld> = dimensionsWithRegions().map(::getWorld)

    override fun getServerWorld(world: Any): Optional<ServerWorld> =
        when (world) {
            is World -> Optional.of(getWorld(world.provider.dimensionId))
            is Int -> Optional.of(getWorld(world))
            is Key -> Optional.ofNullable(LegacyDimensions.dimensionId(world)).map(::getWorld)
            is String -> Optional.ofNullable(Key.parse(world)).flatMap { getServerWorld(it) }
            else -> super.getServerWorld(world)
        }

    override fun getOnlinePlayers(): Map<UUID, Player> = onlinePlayers

    /** Block states come from the world's FML registry through the legacy loader, not from the server. */
    override fun getDefaultBlockstates(): Map<Key, BlockState>? = null

    override fun registerListener(listener: ServerEventListener) {
        listeners.add(listener)
    }

    override fun unregisterAllListeners() {
        listeners.clear()
    }

    fun getWorld(dimensionId: Int): GtnhWorld = worlds.computeIfAbsent(dimensionId) { GtnhWorld(serverThread, worldFolder, it) }

    /** Server thread only. */
    fun findPlayer(uuid: UUID): EntityPlayerMP? =
        minecraftServer.configurationManager.playerEntityList
            .asSequence()
            .filterIsInstance<EntityPlayerMP>()
            .firstOrNull { it.uniqueID == uuid }

    private fun dimensionsWithRegions(): List<Int> {
        val ids = ArrayList<Int>()
        if (hasRegionFiles(worldFolder)) ids.add(0)
        if (Files.isDirectory(worldFolder)) {
            Files.list(worldFolder).use { folders ->
                folders.forEach { folder ->
                    val name = folder.name
                    if (!name.startsWith("DIM")) return@forEach
                    val id = name.substring(3).toIntOrNull() ?: return@forEach
                    if (hasRegionFiles(folder)) ids.add(id)
                }
            }
        }
        return ids.sorted()
    }

    private fun hasRegionFiles(dimensionFolder: Path): Boolean {
        val regionFolder = dimensionFolder.resolve("region")
        if (!Files.isDirectory(regionFolder)) return false
        return Files.list(regionFolder).use { files -> files.anyMatch { REGION_FILE.matches(it.name) } }
    }

    /** D17 on every start: map-configs for dimensions that got region files since the last start (see [MapConfigAutoCreator]). */
    private fun createMissingMapConfigs() {
        try {
            MapConfigAutoCreator(getConfigFolder()).createMissing(getLoadedServerWorlds())
        } catch (e: IOException) {
            Logger.global.logError("Failed to create map-configs for new dimensions", e)
        }
    }

    private fun writeRuntimeDataPack() {
        try {
            val biomes =
                BiomeGenBase.getBiomeGenArray().filterNotNull().map {
                    RuntimeDataPack.BiomeInfo(it.biomeID, it.temperature, it.rainfall, it.waterColorMultiplier)
                }
            runtimeDataPack.writeBiomes(biomes)
        } catch (e: IOException) {
            RuntimeDataPack.logWriteFailure("biomes", e)
        }
        DimensionManager.getWorlds().forEach(::writeDimensionType)
    }

    private fun writeDimensionType(world: WorldServer) {
        val provider = world.provider
        val id = provider.dimensionId
        if (id == 0 || id == -1 || id == 1) return
        try {
            runtimeDataPack.writeDimensionType(
                RuntimeDataPack.DimensionInfo(
                    id = id,
                    natural = provider.isSurfaceWorld,
                    hasSkylight = !provider.hasNoSky,
                    hasCeiling = provider.isHellWorld,
                    ambientLight = provider.lightBrightnessTable[0],
                    coordinateScale = provider.movementFactor,
                ),
            )
        } catch (e: IOException) {
            RuntimeDataPack.logWriteFailure("dimension type of DIM$id", e)
        }
    }

    /**
     * Registers the GTNH block renderers of the optional `gtnh-render` module (shaded only when it is built).
     * Called reflectively so the mod builds and runs without it.
     */
    private fun registerGtnhRender() {
        val renderClass =
            try {
                Class.forName(GTNH_RENDER_CLASS)
            } catch (e: ClassNotFoundException) {
                return
            }
        renderClass.getMethod("register").invoke(null)
        Logger.global.logInfo("Registered GTNH renderers")
    }

    /**
     * Only update some of the online players each tick to minimize performance impact on the server-thread.
     * Only call this method on the server-thread.
     */
    private fun updateSomePlayers() {
        synchronized(playerList) {
            val onlinePlayerCount = playerList.size
            if (onlinePlayerCount == 0) return

            val playersToBeUpdated = (onlinePlayerCount / 20).coerceAtLeast(1) // with 20 tps, each player is updated once a second
            repeat(playersToBeUpdated) {
                playerUpdateIndex++
                if (playerUpdateIndex >= 20 && playerUpdateIndex >= onlinePlayerCount) playerUpdateIndex = 0
                if (playerUpdateIndex < onlinePlayerCount) playerList[playerUpdateIndex].update()
            }
        }
    }

    private companion object {
        const val GTNH_RENDER_CLASS = "de.bluecolored.bluemap.gtnh.render.GtnhRender"
        val REGION_FILE = Regex("""r\.-?\d+\.-?\d+\.mca""")
    }
}
