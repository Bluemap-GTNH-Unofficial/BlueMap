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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CommandWaitTest {
    @Test
    fun budgets() {
        assertEquals(100, CommandWait.budgetMillis(rcon = false, onServerThread = true))
        assertEquals(100, CommandWait.budgetMillis(rcon = false, onServerThread = false))
        assertEquals(1_000, CommandWait.budgetMillis(rcon = true, onServerThread = true))
        assertEquals(60_000, CommandWait.budgetMillis(rcon = true, onServerThread = false))
    }

    @Test
    fun waitingOnTheServerThreadRunsTheCommandsServerThreadTasks() {
        val serverThread = ServerThread(Thread.currentThread())
        // like `/bluemap update`: the async command needs the server thread to save the world
        val execution = CompletableFuture.supplyAsync { serverThread.call(10) { "saved" } }

        val started = System.nanoTime()
        assertTrue(CommandWait.await(execution, 1_000, serverThread))
        assertTrue(elapsedMillis(started) < 1_000)
        assertEquals("saved", execution.get())
    }

    @Test
    fun theServerThreadIsReleasedAfterItsBudget() {
        val serverThread = ServerThread(Thread.currentThread())
        val execution = CompletableFuture<Unit>() // a command that takes longer (e.g. `/bluemap reload`)

        val started = System.nanoTime()
        assertFalse(CommandWait.await(execution, CommandWait.RCON_ON_SERVER_THREAD_MILLIS, serverThread))
        val elapsed = elapsedMillis(started)
        assertTrue(elapsed in 1_000..1_500, "waited $elapsed ms")
    }

    @Test
    fun serverThreadTasksOnlyRunOnTheServerThread() {
        val serverThread = ServerThread(Thread()) // some other thread is the server thread
        val execution = CompletableFuture.supplyAsync { serverThread.call(10) { "saved" } }

        assertFalse(CommandWait.await(execution, 200, serverThread))
        assertFalse(execution.isDone)
        serverThread.close()
    }

    private fun elapsedMillis(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
}
