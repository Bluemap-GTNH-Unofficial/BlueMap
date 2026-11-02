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
@file:JvmName("RenderStates")

package de.bluecolored.bluemap.gtnh.oracle

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.Closeable
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private const val USAGE =
    "Usage: renderStates --index <client index.json> --out <bluemap dir> --pack <file> [--pack <file>...] " +
        "--mc-cache <dir> [--only <regex on key>] [--threads N] [--limit N]"

/**
 * State oracle, BlueMap side: renders every state of a client index (contract `client/index.json`) through BlueMap's
 * hires pipeline ([StateRenderer]) and writes `<out>/<path>.png`, `<out>/<path>.faces.png` and `<out>/index.json`
 * (entries in input order).
 */
fun main(args: Array<String>) {
    val options =
        try {
            Options.parse(args)
        } catch (ex: IllegalArgumentException) {
            System.err.println(ex.message)
            System.err.println(USAGE)
            exitProcess(2)
        }
    renderStates(options)
}

private class Options(
    val index: Path,
    val out: Path,
    val packs: List<Path>,
    val minecraftCache: Path,
    val only: Regex?,
    val threads: Int,
    val limit: Int?,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var index: Path? = null
            var out: Path? = null
            val packs = ArrayList<Path>()
            var minecraftCache: Path? = null
            var only: Regex? = null
            var threads = Runtime.getRuntime().availableProcessors()
            var limit: Int? = null
            var i = 0
            while (i < args.size) {
                val option = args[i]
                require(i + 1 < args.size) { "Missing value for $option" }
                val value = args[i + 1]
                when (option) {
                    "--index" -> {
                        index = Path.of(value)
                    }

                    "--out" -> {
                        out = Path.of(value)
                    }

                    "--pack" -> {
                        packs.add(Path.of(value))
                    }

                    "--mc-cache" -> {
                        minecraftCache = Path.of(value)
                    }

                    "--only" -> {
                        only = Regex(value)
                    }

                    "--threads" -> {
                        threads =
                            value.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid --threads $value")
                    }

                    "--limit" -> {
                        limit = value.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Invalid --limit $value")
                    }

                    else -> {
                        throw IllegalArgumentException("Unknown option $option")
                    }
                }
                i += 2
            }
            requireNotNull(index) { "--index is required" }
            requireNotNull(out) { "--out is required" }
            requireNotNull(minecraftCache) { "--mc-cache is required" }
            require(packs.isNotEmpty()) { "At least one --pack is required" }
            require(Files.isRegularFile(index)) { "No client index at $index" }
            for (pack in packs) require(Files.exists(pack)) { "No pack at $pack" }
            return Options(index, out, packs, minecraftCache, only, threads, limit)
        }
    }
}

private fun renderStates(options: Options) {
    val started = System.nanoTime()
    val index = ClientIndex.read(options.index)
    val states = index.states.filter { options.only?.containsMatchIn(it.key) ?: true }.take(options.limit ?: Int.MAX_VALUE)
    println("[oracle] ${states.size} of ${index.states.size} states from ${options.index}")

    val workDir = Files.createTempDirectory("bluemap-oracle")
    try {
        val resources = OracleResources.load(options.packs, options.minecraftCache, workDir)
        val chunks = resources.stateChunks()
        val loadSeconds = (System.nanoTime() - started) / 1e9
        println(
            "[oracle] resources loaded in %.1f s, %d textures, %d threads".format(
                Locale.ROOT,
                loadSeconds,
                resources.textures.size,
                options.threads,
            ),
        )

        val out = options.out.toAbsolutePath().normalize()
        Files.createDirectories(out)
        val renderers = ThreadLocal.withInitial { StateRenderer(resources, chunks, index.spec) }
        val threadCount = AtomicInteger()
        val executor =
            Executors.newFixedThreadPool(options.threads) { task ->
                Thread(task, "oracle-${threadCount.incrementAndGet()}").apply { isDaemon = true }
            }
        val stats = Stats()
        val part = out.resolve("index.json.part")
        val renderStarted = System.nanoTime()
        try {
            IndexWriter(Files.newBufferedWriter(part), describeRoots(resources.packRoots, workDir)).use { writer ->
                val pending = ArrayDeque<Future<StateResult>>()
                var next = 0
                var lastReport = System.nanoTime()
                while (next < states.size || pending.isNotEmpty()) {
                    while (next < states.size && pending.size < options.threads * IN_FLIGHT_PER_THREAD) {
                        val state = states[next++]
                        pending.addLast(executor.submit(Callable { renderAndWrite(renderers.get(), state, out) }))
                    }
                    val result = pending.removeFirst().get()
                    writer.write(entry(result))
                    stats.add(result)
                    if (System.nanoTime() - lastReport > REPORT_INTERVAL_NANOS) {
                        lastReport = System.nanoTime()
                        val seconds = (lastReport - renderStarted) / 1e9
                        println("[oracle] %d/%d states, %.1f states/s".format(Locale.ROOT, stats.count, states.size, stats.count / seconds))
                    }
                }
                writer.finish((System.nanoTime() - started) / 1e9)
            }
        } finally {
            executor.shutdownNow()
        }
        Files.move(part, out.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        println(stats.summary((System.nanoTime() - renderStarted) / 1e9, options.threads))
        println("[oracle] wrote ${out.resolve("index.json")} in %.1f s".format(Locale.ROOT, (System.nanoTime() - started) / 1e9))
    } finally {
        workDir.toFile().deleteRecursively()
    }
}

private const val IN_FLIGHT_PER_THREAD = 16
private const val REPORT_INTERVAL_NANOS = 10_000_000_000L

/** Renders [state] and writes its images; output problems become the state's error rather than ending the run. */
private fun renderAndWrite(
    renderer: StateRenderer,
    state: ClientState,
    out: Path,
): StateResult {
    val result = renderer.render(state)
    val base = out.resolve(state.path).normalize()
    val failure =
        if (state.path.isEmpty() || !base.startsWith(out) || base == out) {
            "invalid path '${state.path}', no images written"
        } else {
            try {
                result.image.write(base.resolveSibling("${base.fileName}.png"), base.resolveSibling("${base.fileName}.faces.png"))
                null
            } catch (ex: IOException) {
                "failed to write the images: $ex"
            }
        }
    if (failure == null) return result
    return StateResult(
        state,
        result.blockState,
        result.blockEntity,
        result.variants,
        result.triangles,
        result.missingTexture,
        listOfNotNull(result.error, failure).joinToString("; "),
        result.image,
        result.nanos,
    )
}

/** The pack roots as written to the index; the temporary copy of `resourceExtensions.zip` by its classpath URL. */
private fun describeRoots(
    roots: List<Path>,
    workDir: Path,
): List<String> =
    roots.map { root ->
        if (root.startsWith(workDir)) {
            OracleResources::class.java.getResource("/de/bluecolored/bluemap/${root.fileName}")?.toString() ?: root.toString()
        } else {
            root.toAbsolutePath().normalize().toString()
        }
    }

private fun entry(result: StateResult): JsonObject =
    JsonObject().apply {
        addProperty("key", result.state.key)
        addProperty("path", result.state.path)
        addProperty("blockState", result.blockState)
        addProperty("blockEntity", result.blockEntity)
        add(
            "variants",
            JsonArray().apply {
                for (variant in result.variants) {
                    add(
                        JsonObject().apply {
                            addProperty("renderer", variant.renderer)
                            addProperty("model", variant.model)
                        },
                    )
                }
            },
        )
        addProperty("triangles", result.triangles)
        addProperty("missingTexture", result.missingTexture)
        addProperty("blank", result.blank)
        addProperty("error", result.error)
    }

/** Streams `index.json`: one state entry per line, in input order. */
private class IndexWriter(
    private val out: Writer,
    packRoots: List<String>,
) : Closeable {
    private var count = 0

    init {
        out.write("{\"format\": 1, \"packRoots\": ${GSON.toJson(packRoots)},\n\"states\": [\n")
    }

    fun write(entry: JsonObject) {
        if (count++ > 0) out.write(",\n")
        out.write(GSON.toJson(entry))
    }

    fun finish(seconds: Double) {
        out.write("\n],\n\"seconds\": ${GSON.toJson(seconds)}}\n")
    }

    override fun close() = out.close()

    private companion object {
        val GSON: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    }
}

/** Per-state timing and outcome counters for the final summary. */
private class Stats {
    var count = 0
        private set
    private val nanos = ArrayList<Long>()
    private var slowest: StateResult? = null
    private var errors = 0
    private var blank = 0
    private var missingTexture = 0

    fun add(result: StateResult) {
        count++
        nanos += result.nanos
        if (result.error != null) errors++
        if (result.blank) blank++
        if (result.missingTexture) missingTexture++
        if (result.nanos > (slowest?.nanos ?: -1L)) slowest = result
    }

    fun summary(
        seconds: Double,
        threads: Int,
    ): String {
        if (count == 0) return "[oracle] no states rendered"
        val sorted = nanos.sorted()

        fun millis(quantile: Double) = sorted[((sorted.size - 1) * quantile).toInt()] / 1e6
        return buildString {
            append(
                "[oracle] %d states in %.1f s (%.1f states/s, %d threads); ".format(Locale.ROOT, count, seconds, count / seconds, threads),
            )
            append(
                "per state: mean %.2f ms, p50 %.2f ms, p95 %.2f ms, max %.1f ms (%s)\n".format(
                    Locale.ROOT,
                    sorted.average() / 1e6,
                    millis(0.5),
                    millis(0.95),
                    sorted.last() / 1e6,
                    slowest?.state?.key,
                ),
            )
            append("[oracle] errors %d, blank %d, missingTexture %d".format(Locale.ROOT, errors, blank, missingTexture))
        }
    }
}
