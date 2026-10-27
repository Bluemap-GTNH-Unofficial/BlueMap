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

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLServerStartedEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import cpw.mods.fml.common.event.FMLServerStoppingEvent
import net.minecraft.launchwrapper.Launch
import net.minecraftforge.common.MinecraftForge

/**
 * Forge entry point. Kept free of BlueMap types: its static initialiser excludes the shaded libraries from the
 * class transformers before anything can load them, everything else lives in [GtnhServer].
 */
@Mod(
    modid = BlueMapMod.MOD_ID,
    name = "BlueMap",
    version = Tags.VERSION,
    useMetadata = true,
    acceptableRemoteVersions = "*",
    dependencies = "required-after:forgelin",
    modLanguageAdapter = "net.shadowfacts.forgelin.KotlinAdapter",
)
object BlueMapMod {
    const val MOD_ID = "bluemap"

    /**
     * Pure-Java libraries in this jar (BlueMap itself and its relocated dependencies). No Forge/mod transformer has
     * business with them, and running every ASM transformer of the pack over thousands of library classes only costs
     * startup time and risks breaking modern bytecode.
     */
    private val TRANSFORMER_EXCLUSIONS =
        listOf(
            "de.bluecolored.shadow.",
            "de.bluecolored.bluemap.api.",
            "de.bluecolored.bluemap.core.",
            "de.bluecolored.bluemap.common.",
            "de.bluecolored.bluemap.gtnh.legacy.",
            "de.bluecolored.bluemap.gtnh.render.",
            "de.bluecolored.bluenbt.",
            "de.bluecolored.bluecommands.",
            "com.flowpowered.math.",
        )

    init {
        TRANSFORMER_EXCLUSIONS.forEach(Launch.classLoader::addTransformerExclusion)
    }

    private var server: GtnhServer? = null

    @Mod.EventHandler
    fun serverStarting(event: FMLServerStartingEvent) {
        val server = GtnhServer(event.server)
        this.server = server
        FMLCommonHandler.instance().bus().register(server)
        MinecraftForge.EVENT_BUS.register(server)
        server.onServerStarting(event)
    }

    @Mod.EventHandler
    fun serverStarted(event: FMLServerStartedEvent) {
        server?.onServerStarted()
    }

    @Mod.EventHandler
    fun serverStopping(event: FMLServerStoppingEvent) {
        val server = this.server ?: return
        server.onServerStopping()
        FMLCommonHandler.instance().bus().unregister(server)
        MinecraftForge.EVENT_BUS.unregister(server)
        this.server = null
    }
}
