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

import de.bluecolored.bluecommands.Command
import de.bluecolored.bluecommands.LiteralCommand
import de.bluecolored.bluecommands.ParseMatch
import de.bluecolored.bluecommands.ParseResult
import de.bluecolored.bluemap.common.commands.Commands
import de.bluecolored.bluemap.common.commands.TextFormat.BASE_COLOR
import de.bluecolored.bluemap.common.commands.TextFormat.NEGATIVE_COLOR
import de.bluecolored.bluemap.common.plugin.Plugin
import de.bluecolored.bluemap.common.serverinterface.CommandSource
import de.bluecolored.bluemap.core.BlueMap
import de.bluecolored.bluemap.core.logger.Logger
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.ComponentLike
import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import net.minecraft.network.rcon.RConConsoleSource
import java.util.concurrent.CompletableFuture

/**
 * `/bluemap` as a 1.7.10 [CommandBase], driving BlueMap's bluecommands tree directly (1.7.10 has no brigadier).
 *
 * Execution mirrors upstream `CommandExecutor` (async on [BlueMap.THREAD_POOL]); how long the command waits for the
 * result before returning is [CommandWait]'s policy. An RCON command still running after its wait says so in the reply.
 */
class BlueMapCommand(
    private val server: GtnhServer,
    private val serverThread: ServerThread,
    private val plugin: Plugin,
) : CommandBase() {
    private val commands: Command<CommandSource, Any> = Commands.create(plugin)
    private val name: String = (commands as LiteralCommand<*, *>).literal

    override fun getCommandName(): String = name

    override fun getCommandUsage(sender: ICommandSender): String = "/$name"

    /** BlueMap checks the permission of each subcommand itself ([GtnhCommandSource.hasPermission]). */
    override fun getRequiredPermissionLevel(): Int = 0

    override fun canCommandSenderUseCommand(sender: ICommandSender): Boolean = true

    override fun processCommand(
        sender: ICommandSender,
        args: Array<String>,
    ) {
        val source = GtnhCommandSource(server, sender)
        val result = commands.parse(source, input(args))
        val rcon = sender is RConConsoleSource

        if (result.matches.isEmpty()) {
            if (!Commands.checkPluginLoaded(plugin, source)) return
            val failure = result.failures.maxByOrNull { it.position }
            source.sendMessage(text(failure?.reason ?: "Unknown or incomplete command!").color(NEGATIVE_COLOR))
            return
        }

        execute(result, result.matches.maxBy { it.priority }, rcon)
    }

    private fun execute(
        result: ParseResult<CommandSource, Any>,
        match: ParseMatch<CommandSource, Any>,
        rcon: Boolean,
    ) {
        if (!Commands.checkExecutablePreconditions(plugin, match.context, match.executable)) return

        val execution =
            CompletableFuture
                .supplyAsync(match::execute, BlueMap.THREAD_POOL)
                .thenAccept { value -> if (value is ComponentLike) match.context.sendMessage(value.asComponent()) }
                .exceptionally { e ->
                    Logger.global.logError("Command execution for '${result.input}' failed", e)
                    result.context.sendMessage(
                        text("There was an error executing this command! See logs or console for details.").color(NEGATIVE_COLOR),
                    )
                    null
                }

        val completed = CommandWait.await(execution, CommandWait.budgetMillis(rcon, serverThread.isCurrent), serverThread)
        if (!completed && rcon) {
            match.context.sendMessage(text("Still running, see /$name for the status and the server log for the result.").color(BASE_COLOR))
        }
    }

    override fun addTabCompletionOptions(
        sender: ICommandSender,
        args: Array<String>,
    ): List<String>? {
        val input = input(args)
        val position = input.lastIndexOf(' ') + 1
        val prefix = input.substring(position)
        val result = commands.parse(GtnhCommandSource(server, sender), input)

        return result.failures
            .asSequence()
            .filter { it.position == position }
            .flatMap { it.suggestions.asSequence() }
            .map { it.string }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
            .ifEmpty { null }
    }

    private fun input(args: Array<String>): String = if (args.isEmpty()) name else name + " " + args.joinToString(" ")
}
