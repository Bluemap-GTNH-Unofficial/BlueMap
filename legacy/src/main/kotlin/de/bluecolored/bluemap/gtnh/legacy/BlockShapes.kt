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
package de.bluecolored.bluemap.gtnh.legacy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.logger.Logger
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shape facts of one modded block + meta, as exported from the 1.7.10 client (`data/gtnh/block_shapes.json`,
 * written by the GTNH exporter): [solidSides] is a bitmask over the [Face] ordinals (= Forge's `ForgeDirection`).
 */
data class ModdedShape(
    val fullCube: Boolean,
    val opaque: Boolean,
    val solidSides: Int,
    val fenceConnect: Boolean,
    val paneConnect: Boolean,
) {
    internal fun isSolid(face: Face): Boolean = solidSides and (1 shl face.ordinal) != 0
}

/**
 * Shape lookups for the neighbour fixers: vanilla states by [VanillaBlockShapes], modded states by [modded] data when
 * the exporter provided it, otherwise modded blocks count as solid opaque full cubes.
 *
 * Format of `data/gtnh/block_shapes.json`:
 * `{"format": 1, "blocks": {"<N(modid)>:<N(name)>": {"<meta>": {...}, "*": {... for metas not listed}}}}`
 * with the [ModdedShape] fields. Earlier pack roots win.
 */
class BlockShapes(
    private val modded: Map<Key, Map<String, ModdedShape>> = emptyMap(),
) {
    private fun moddedShape(state: BlockState): ModdedShape? {
        if (state.id.namespace == Key.MINECRAFT_NAMESPACE) return null
        val metas = modded[state.id] ?: return null
        return metas[state.properties[RegistryBlockStateMapper.META]] ?: metas["*"]
    }

    internal fun isFullCube(state: BlockState): Boolean = moddedShape(state)?.fullCube ?: VanillaBlockShapes.isFullCube(state)

    internal fun isNormalCube(state: BlockState): Boolean =
        moddedShape(state)?.let { it.fullCube && it.opaque } ?: VanillaBlockShapes.isNormalCube(state)

    internal fun isBlockNormalCube(state: BlockState): Boolean = moddedShape(state)?.fullCube ?: VanillaBlockShapes.isBlockNormalCube(state)

    internal fun isTopSolid(state: BlockState): Boolean = moddedShape(state)?.isSolid(Face.UP) ?: VanillaBlockShapes.isTopSolid(state)

    internal fun faceShape(
        state: BlockState,
        face: Face,
    ): FaceShape {
        val shape = moddedShape(state) ?: return VanillaBlockShapes.faceShape(state, face)
        return if (shape.isSolid(face)) FaceShape.SOLID else FaceShape.OTHER
    }

    /** Whether a fence or wall attaches to [neighbour]'s [face] (the face pointing back at the fence). */
    internal fun fenceAttaches(
        neighbour: BlockState,
        face: Face,
    ): Boolean {
        moddedShape(neighbour)?.let { return it.fenceConnect }
        return faceShape(neighbour, face) == FaceShape.SOLID && !VanillaBlockShapes.isFenceAttachException(neighbour)
    }

    /** Whether a glass pane or iron bars attach to [neighbour]'s [face] (1.7: block-level connect or a solid side). */
    internal fun paneAttaches(
        neighbour: BlockState,
        face: Face,
    ): Boolean {
        moddedShape(neighbour)?.let { return it.paneConnect || it.isSolid(face) }
        return faceShape(neighbour, face) == FaceShape.SOLID && !VanillaBlockShapes.isPaneAttachException(neighbour)
    }

    /** Whether a vine attaches to [neighbour]'s [face]. */
    internal fun vineAttaches(
        neighbour: BlockState,
        face: Face,
    ): Boolean = faceShape(neighbour, face) == FaceShape.SOLID && !VanillaBlockShapes.isVineException(neighbour)

    companion object {
        const val FILE: String = "data/gtnh/block_shapes.json"

        /** No modded shape data: modded blocks are solid opaque full cubes. */
        @JvmField
        val DEFAULT: BlockShapes = BlockShapes()

        /** Reads [FILE] from every root of the [dataPack] (earlier roots win per block). */
        @JvmStatic
        fun load(dataPack: DataPack): BlockShapes {
            val blocks = HashMap<Key, Map<String, ModdedShape>>()
            for (root in dataPack.roots) {
                try {
                    dataPack.loadResourcePath(root) { path ->
                        val file = path.resolve(FILE)
                        if (Files.isRegularFile(file)) {
                            for ((key, metas) in parse(file)) blocks.putIfAbsent(key, metas)
                        }
                    }
                } catch (ex: Exception) {
                    Logger.global.logWarning("Failed to read $FILE from '$root': $ex")
                }
            }
            return if (blocks.isEmpty()) DEFAULT else BlockShapes(blocks)
        }

        @JvmStatic
        fun parse(file: Path): Map<Key, Map<String, ModdedShape>> = parse(JsonParser.parseString(Files.readString(file)).asJsonObject)

        @JvmStatic
        fun parse(json: JsonObject): Map<Key, Map<String, ModdedShape>> {
            require(json.get("format")?.asInt == 1) { "Unsupported block_shapes format ${json.get("format")}" }
            val out = HashMap<Key, Map<String, ModdedShape>>()
            for ((block, metas) in json.getAsJsonObject("blocks").entrySet()) {
                out[Key.parse(block)] =
                    metas.asJsonObject.entrySet().associate { (meta, value) ->
                        val o = value.asJsonObject
                        meta to
                            ModdedShape(
                                fullCube = o.get("fullCube").asBoolean,
                                opaque = o.get("opaque").asBoolean,
                                solidSides = o.get("solidSides").asInt,
                                fenceConnect = o.get("fenceConnect").asBoolean,
                                paneConnect = o.get("paneConnect").asBoolean,
                            )
                    }
            }
            return out
        }
    }
}
