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

import com.flowpowered.math.vector.Vector3d
import de.bluecolored.bluemap.common.plugin.text.Text
import de.bluecolored.bluemap.common.serverinterface.Gamemode
import de.bluecolored.bluemap.common.serverinterface.Player
import de.bluecolored.bluemap.common.serverinterface.ServerWorld
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.potion.Potion
import net.minecraft.util.MathHelper
import net.minecraft.world.EnumSkyBlock
import net.minecraft.world.WorldSettings
import java.util.UUID

/** Snapshot of an online player, refreshed on the server thread by [update]. */
class GtnhPlayer(
    private val server: GtnhServer,
    player: EntityPlayerMP,
) : Player() {
    private val uuid: UUID = player.uniqueID

    @Volatile private lateinit var name: Text

    @Volatile private lateinit var world: ServerWorld

    @Volatile private lateinit var position: Vector3d

    @Volatile private lateinit var rotation: Vector3d

    @Volatile private var skyLight = 0

    @Volatile private var blockLight = 0

    @Volatile private var sneaking = false

    @Volatile private var invisible = false

    @Volatile private lateinit var gamemode: Gamemode

    init {
        update(player)
    }

    override fun getUuid(): UUID = uuid

    override fun getName(): Text = name

    override fun getWorld(): ServerWorld = world

    override fun getPosition(): Vector3d = position

    override fun getRotation(): Vector3d = rotation

    override fun getSkyLight(): Int = skyLight

    override fun getBlockLight(): Int = blockLight

    override fun isSneaking(): Boolean = sneaking

    override fun isInvisible(): Boolean = invisible

    override fun getGamemode(): Gamemode = gamemode

    /** Server thread only. */
    fun update() {
        server.findPlayer(uuid)?.let(::update)
    }

    private fun update(player: EntityPlayerMP) {
        gamemode =
            when (player.theItemInWorldManager.gameType) {
                WorldSettings.GameType.CREATIVE -> Gamemode.CREATIVE
                WorldSettings.GameType.ADVENTURE -> Gamemode.ADVENTURE
                else -> Gamemode.SURVIVAL
            }
        invisible = player.isPotionActive(Potion.invisibility)
        name = Text.of(player.commandSenderName)
        position = Vector3d(player.posX, player.posY, player.posZ)
        rotation = Vector3d(player.rotationPitch.toDouble(), player.rotationYawHead.toDouble(), 0.0)
        sneaking = player.isSneaking

        val x = MathHelper.floor_double(player.posX)
        val y = MathHelper.floor_double(player.posY)
        val z = MathHelper.floor_double(player.posZ)
        skyLight = player.worldObj.getSavedLightValue(EnumSkyBlock.Sky, x, y, z)
        blockLight = player.worldObj.getSavedLightValue(EnumSkyBlock.Block, x, y, z)

        world = server.getWorld(player.dimension)
    }
}
