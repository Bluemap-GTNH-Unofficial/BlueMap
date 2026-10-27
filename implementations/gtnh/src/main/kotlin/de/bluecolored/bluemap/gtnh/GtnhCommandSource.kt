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
import de.bluecolored.bluemap.common.commands.TextFormat
import de.bluecolored.bluemap.common.serverinterface.CommandSource
import de.bluecolored.bluemap.common.serverinterface.ServerWorld
import net.kyori.adventure.text.Component
import net.minecraft.command.ICommandSender
import net.minecraft.command.server.CommandBlockLogic
import net.minecraft.entity.Entity
import net.minecraft.network.rcon.RConConsoleSource
import java.util.Optional

class GtnhCommandSource(
    private val server: GtnhServer,
    private val delegate: ICommandSender,
) : CommandSource {
    override fun sendMessage(text: Component) {
        var message = text
        if (TextFormat.lineCount(message) > 1) {
            message = Component.newline().append(message).appendNewline()
        }
        // RCON concatenates all messages of a command into one reply
        if (delegate is RConConsoleSource) message = message.appendNewline()
        delegate.addChatMessage(ChatComponents.toMinecraft(message))
    }

    /** Like upstream: every BlueMap permission requires the moderator level (any op in 1.7.10). */
    override fun hasPermission(permission: String): Boolean = delegate.canCommandSenderUseCommand(PERMISSION_LEVEL, BlueMapMod.MOD_ID)

    override fun getPosition(): Optional<Vector3d> =
        when (delegate) {
            is Entity -> Optional.of(Vector3d(delegate.posX, delegate.posY, delegate.posZ))
            is CommandBlockLogic ->
                delegate.playerCoordinates.let { Optional.of(Vector3d(it.posX + 0.5, it.posY + 0.5, it.posZ + 0.5)) }
            else -> Optional.empty() // server console, RCON
        }

    override fun getWorld(): Optional<ServerWorld> =
        when (delegate) {
            is Entity, is CommandBlockLogic -> Optional.of(server.getWorld(delegate.entityWorld.provider.dimensionId))
            else -> Optional.empty()
        }

    private companion object {
        const val PERMISSION_LEVEL = 1
    }
}
