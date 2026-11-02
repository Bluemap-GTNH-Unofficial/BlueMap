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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.mask.Mask
import de.bluecolored.bluemap.core.resources.MinecraftVersion
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture
import de.bluecolored.bluemap.gtnh.legacy.BlockShapes
import de.bluecolored.bluemap.gtnh.legacy.GtnhLegacy
import de.bluecolored.bluemap.gtnh.legacy.LegacyDimensions
import de.bluecolored.bluemap.gtnh.legacy.VanillaLegacyBiomeMapper
import de.bluecolored.bluemap.gtnh.render.GtnhRender
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.imageio.ImageIO

/**
 * The resources a BlueMap map renders with, loaded like `BlueMapService` / `BmMap` do: the resource pack from the
 * pack roots plus the vanilla client jar, the datapack from the same roots plus the vanilla datapack jar, and a
 * fresh [TextureGallery] holding the pack's textures. [textures] is the gallery as the webapp receives it.
 */
class OracleResources(
    /** The resource pack roots in priority order (the first root wins). */
    val packRoots: List<Path>,
    val resourcePack: ResourcePack,
    val dataPack: DataPack,
) {
    val textureGallery: TextureGallery = TextureGallery().apply { put(resourcePack.textures) }
    val textures: TextureTable = TextureTable.parse(StringWriter().also { textureGallery.writeTexturesFile(it) }.toString())

    /** The synthetic chunks, with the mapping and biomes of `LegacyWorldLoader` for the overworld. */
    fun stateChunks(): StateChunks =
        StateChunks(
            BlockShapes.load(dataPack),
            VanillaLegacyBiomeMapper(dataPack::getBiome),
            LegacyDimensions.dimensionType(DataPack.DIMENSION_OVERWORLD),
        )

    companion object {
        const val MINECRAFT_VERSION: String = "1.7.10"
        private const val RESOURCE_EXTENSIONS = "/de/bluecolored/bluemap/resourceExtensions.zip"

        /** Registers the GTNH world loader, renderers and resource-pack extensions (before any pack is loaded). */
        fun registerAddons() {
            GtnhLegacy.register()
            GtnhRender.register()
        }

        /**
         * The roots `BlueMapService.getPackRoots` builds without a config: the [packs] sorted by file name descending
         * (BlueMap lists its `packs` folder in reverse order), then BlueMap's `resourceExtensions.zip`, copied to
         * [workDir]. No `defaultBlockstates.zip`.
         */
        fun packRoots(
            packs: List<Path>,
            workDir: Path,
        ): List<Path> {
            val extensions = workDir.resolve("resourceExtensions.zip")
            val source =
                requireNotNull(ResourcePack::class.java.getResourceAsStream(RESOURCE_EXTENSIONS)) { "Missing $RESOURCE_EXTENSIONS" }
            source.use { Files.copy(it, extensions, StandardCopyOption.REPLACE_EXISTING) }
            // plusElement: a Path is an Iterable<Path> of its name segments
            return packs.sortedByDescending { it.fileName.toString() }.plusElement(extensions)
        }

        /**
         * Loads everything for Minecraft [MINECRAFT_VERSION] (`MinecraftVersion.load` picks the 1.13 client jar as
         * resource pack and the 1.19.4 one as datapack from [minecraftCache], downloading them if missing).
         */
        fun load(
            packs: List<Path>,
            minecraftCache: Path,
            workDir: Path,
        ): OracleResources {
            registerAddons()
            val version = MinecraftVersion.load(MINECRAFT_VERSION, minecraftCache, true)
            val roots = packRoots(packs, workDir)
            val resourcePack = ResourcePack(version.resourcePackVersion)
            val resourceRoots = roots.plusElement(version.resourcePack)
            resourcePack.loadResources(resourceRoots)
            val dataPack = DataPack(version.dataPackVersion)
            dataPack.loadResources(roots.plusElement(version.dataPack))
            return OracleResources(resourceRoots, resourcePack, dataPack)
        }
    }
}

/** The map settings of the oracle: no cave removal, render edges, everything inside the mask. */
object OracleRenderSettings : RenderSettings {
    const val AMBIENT_LIGHT: Float = 0.1f

    override fun getRemoveCavesBelowY(): Int = Int.MIN_VALUE

    override fun getCaveDetectionOceanFloor(): Int = 0

    override fun isCaveDetectionUsesBlockLight(): Boolean = false

    override fun getAmbientLight(): Float = AMBIENT_LIGHT

    override fun isRenderEdges(): Boolean = true

    override fun getEdgeLightStrength(): Int = 15

    override fun getRenderMask(): Mask = Mask.ALL

    override fun isSaveHiresLayer(): Boolean = true

    override fun isRenderTopOnly(): Boolean = false
}

/**
 * The texture gallery as the webapp reads `textures.json` (index = texture id): the PNG data, `halfTransparent` and
 * the animation's first frame. Parsed from the raw JSON because BlueMap's `AnimationMeta` reader expects the
 * `.mcmeta` layout and would drop the frame list. Textures are decoded on first use; thread-safe.
 */
class TextureTable private constructor(
    private val entries: JsonArray,
) {
    val size: Int get() = entries.size()

    private val decoded = AtomicReferenceArray<RasterTexture>(entries.size())
    private val missing =
        BooleanArray(entries.size()) {
            it == 0 ||
                entries[it].asJsonObject.get("texture")?.asString == Texture.MISSING.texture
        }

    /** The texture of id [id], or null if there is none (the webapp then has no material and draws nothing). */
    operator fun get(id: Int): RasterTexture? {
        if (id < 0 || id >= size) return null
        decoded.get(id)?.let { return it }
        val texture = decode(entries[id].asJsonObject) ?: return null
        decoded.compareAndSet(id, null, texture)
        return decoded.get(id)
    }

    /** True for id 0 (`bluemap:block/missing`) and for textures BlueMap replaced by the missing-texture image. */
    fun isMissing(id: Int): Boolean = id < 0 || id >= size || missing[id]

    private fun decode(entry: JsonObject): RasterTexture? {
        val data = entry.get("texture")?.asString ?: return null
        if (!data.startsWith(DATA_PREFIX)) return null
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(data.substring(DATA_PREFIX.length)))) ?: return null
        val animation = entry.get("animation")?.takeIf { it.isJsonObject }?.asJsonObject
        val frames = animation?.get("frames")?.takeIf { it.isJsonArray }?.asJsonArray
        val firstFrame =
            frames
                ?.firstOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("index")
                ?.asInt ?: 0
        return RasterTexture(
            image.width,
            image.height,
            image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
            entry.get("halfTransparent")?.asBoolean ?: false,
            animated = animation != null,
            frame = firstFrame,
        )
    }

    companion object {
        private const val DATA_PREFIX = "data:image/png;base64,"

        fun parse(texturesJson: String): TextureTable = TextureTable(JsonParser.parseString(texturesJson).asJsonArray)
    }
}
