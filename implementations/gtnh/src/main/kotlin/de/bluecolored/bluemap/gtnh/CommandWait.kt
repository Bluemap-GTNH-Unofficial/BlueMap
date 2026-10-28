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

import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * How long `/bluemap` waits for a command running asynchronously on BlueMap's thread pool before returning.
 *
 * - Console and players: 100 ms, like upstream `CommandExecutor`; later output still reaches them.
 * - RCON on its own thread (vanilla 1.7.10): up to 60 s, because 1.7.10 replies to RCON with what was sent until
 *   the command returns.
 * - RCON on the server thread (Hodgepodge `fixRconThreading`, the GTNH default): at most 1 s, the server must keep
 *   ticking. Tasks the command queues for the server thread (e.g. saving the world) run while it waits.
 */
object CommandWait {
    const val CONSOLE_MILLIS: Long = 100
    const val RCON_MILLIS: Long = 60_000
    const val RCON_ON_SERVER_THREAD_MILLIS: Long = 1_000

    private val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5)

    fun budgetMillis(
        rcon: Boolean,
        onServerThread: Boolean,
    ): Long =
        when {
            !rcon -> CONSOLE_MILLIS
            onServerThread -> RCON_ON_SERVER_THREAD_MILLIS
            else -> RCON_MILLIS
        }

    /**
     * Waits for [execution] at most [budgetMillis], running the tasks queued on [serverThread] meanwhile when called on
     * the server thread (so a command waiting for the server thread cannot deadlock with it).
     *
     * @return true if [execution] completed (normally or exceptionally) within the budget
     */
    fun await(
        execution: Future<*>,
        budgetMillis: Long,
        serverThread: ServerThread,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        while (!execution.isDone) {
            if (serverThread.isCurrent) serverThread.drain()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            try {
                execution.get(minOf(remaining, POLL_NANOS), TimeUnit.NANOSECONDS)
            } catch (e: TimeoutException) {
                // keep draining
            } catch (e: ExecutionException) {
                return true
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return execution.isDone
            }
        }
        return true
    }
}
