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

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs tasks on the Minecraft server thread (1.7.10 has no `MinecraftServer#addScheduledTask`).
 * [GtnhServer] drains the queue at the start of every server tick.
 */
class ServerThread(
    private val thread: Thread,
) {
    private val queue = ConcurrentLinkedQueue<FutureTask<*>>()

    @Volatile private var closed = false

    val isCurrent: Boolean
        get() = Thread.currentThread() === thread

    /** Runs [task] on the server thread and waits for its result; runs it directly if called on the server thread. */
    @Throws(IOException::class)
    fun <T> call(
        timeoutSeconds: Long,
        task: () -> T,
    ): T {
        if (isCurrent) return task()
        if (closed) throw IOException("The server is stopping")

        val future = FutureTask(task)
        queue.add(future)
        if (closed) future.cancel(false) // raced with close()
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is IOException) throw cause
            if (cause is RuntimeException) throw cause
            throw IOException(cause)
        } catch (e: TimeoutException) {
            future.cancel(false)
            throw IOException("The server thread did not run the task within $timeoutSeconds s", e)
        } catch (e: CancellationException) {
            throw IOException("The server stopped before running the task", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(false)
            throw IOException(e)
        }
    }

    /** Server thread only. */
    fun drain() {
        while (true) {
            val task = queue.poll() ?: return
            task.run()
        }
    }

    /** Cancels everything still queued and rejects new tasks: the server stops ticking. */
    fun close() {
        closed = true
        while (true) {
            val task = queue.poll() ?: return
            task.cancel(false)
        }
    }
}
