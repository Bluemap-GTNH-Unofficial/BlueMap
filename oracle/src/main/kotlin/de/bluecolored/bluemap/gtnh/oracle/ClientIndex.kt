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
package de.bluecolored.bluemap.gtnh.oracle

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** One state of the client index. */
class ClientState(
    /** `<ns>:<value>[meta=<meta>]`, reused verbatim. */
    val key: String,
    /** The 1.7.10 registry name (`modid:name`, case preserved). */
    val name: String,
    val meta: Int,
    /** Output path below the `bluemap` folder without extension, reused verbatim. */
    val path: String,
    /** The meta stored in the world: the entry's `worldMeta` (e.g. a GregTech machine's base type), else [meta]. */
    val worldMeta: Int,
    /** Uncompressed tile-entity NBT (named root compound), decoded from the entry's base64 `te`. */
    val tileEntity: ByteArray?,
)

/** `client/index.json` of the state oracle: the camera ([spec]) and the states. */
class ClientIndex(
    val spec: RasterSpec,
    val states: List<ClientState>,
) {
    companion object {
        fun read(file: Path): ClientIndex = parse(JsonParser.parseString(Files.readString(file)).asJsonObject)

        fun parse(json: JsonObject): ClientIndex {
            require(json.get("format")?.asInt == 1) { "Unsupported client index format ${json.get("format")}" }
            val states =
                json.getAsJsonArray("states").map { element ->
                    val entry = element.asJsonObject
                    val meta = entry.get("meta").asInt
                    ClientState(
                        key = entry.get("key").asString,
                        name = entry.get("name").asString,
                        meta = meta,
                        path = entry.get("path").asString,
                        worldMeta = entry.get("worldMeta").orNull()?.asInt ?: meta,
                        tileEntity =
                            entry
                                .get("te")
                                .orNull()
                                ?.asString
                                ?.let { Base64.getDecoder().decode(it) },
                    )
                }
            return ClientIndex(spec(json.get("image").orNull()?.asJsonObject), states)
        }

        private fun spec(image: JsonObject?): RasterSpec {
            if (image == null) return RasterSpec()
            val defaults = RasterSpec()
            val target = image.get("target").orNull()?.asJsonArray
            val views =
                image.get("views").orNull()?.asJsonArray?.map { element ->
                    val view = element.asJsonObject
                    RasterView(
                        name = view.get("name").asString,
                        yaw = view.get("yaw").asDouble,
                        pitch = view.get("pitch").asDouble,
                        x = view.get("x").asInt,
                        y = view.get("y").orNull()?.asInt ?: 0,
                    )
                }
            return RasterSpec(
                width = image.get("width").orNull()?.asInt ?: defaults.width,
                height = image.get("height").orNull()?.asInt ?: defaults.height,
                viewSize = image.get("viewSize").orNull()?.asInt ?: defaults.viewSize,
                views = views ?: defaults.views,
                halfExtent = image.get("halfExtent").orNull()?.asDouble ?: defaults.halfExtent,
                depthRange = image.get("depthRange").orNull()?.asDouble ?: defaults.depthRange,
                targetX = target?.get(0)?.asDouble ?: defaults.targetX,
                targetY = target?.get(1)?.asDouble ?: defaults.targetY,
                targetZ = target?.get(2)?.asDouble ?: defaults.targetZ,
            )
        }

        private fun JsonElement?.orNull(): JsonElement? = if (this == null || isJsonNull) null else this
    }
}
