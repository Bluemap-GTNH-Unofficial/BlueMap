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

import com.flowpowered.math.vector.Vector3i
import de.bluecolored.bluemap.core.map.TileMetaConsumer
import de.bluecolored.bluemap.core.map.hires.ArrayTileModel
import de.bluecolored.bluemap.core.map.hires.MaxCapacityReachedException
import de.bluecolored.bluemap.core.map.hires.PRBMWriter
import de.bluecolored.bluemap.core.map.hires.RenderPass
import de.bluecolored.bluemap.core.map.hires.RenderPassType
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.world.BlockState
import java.io.ByteArrayOutputStream

/** A blockstate variant BlueMap resolved: renderer type and model key. */
data class VariantInfo(
    val renderer: String,
    val model: String,
)

/** The BlueMap side of one state: the index entry data and the rasterised [image]. */
class StateResult(
    val state: ClientState,
    /** The block state after fixers, `id[properties]` (without brackets if there are none). */
    val blockState: String?,
    val blockEntity: String?,
    val variants: List<VariantInfo>,
    val triangles: Int,
    val missingTexture: Boolean,
    val error: String?,
    val image: RasterImage,
    val nanos: Long,
) {
    val blank: Boolean = image.coveredPixels == 0
}

/**
 * Renders states like `HiresModelManager.render` renders a tile: every [RenderPassType.REGISTRY] pass over the column
 * (0, 0) (only the state's block is not air) with the model anchored at `(0, 64, 0)` into an [ArrayTileModel], sorted
 * and written as PRBM, then decoded and rasterised like the webapp. Not thread-safe (BlueMap's render passes are
 * not); use one instance per thread.
 */
class StateRenderer(
    private val resources: OracleResources,
    private val chunks: StateChunks,
    spec: RasterSpec,
) {
    private val passes: List<RenderPass> =
        RenderPassType.REGISTRY
            .values()
            .toList()
            .map { it.create(resources.resourcePack, resources.textureGallery, OracleRenderSettings) }
    private val model = ArrayTileModel(INITIAL_CAPACITY)
    private val modelView = TileModelView(model)
    private val rasterizer = Rasterizer(spec)
    private val blank = RasterImage(spec.width, spec.height)

    fun render(state: ClientState): StateResult {
        val start = System.nanoTime()
        var blockState: String? = null
        var blockEntity: String? = null
        val variants = ArrayList<VariantInfo>()
        var triangles = 0
        var missingTexture = false
        var error: String? = null
        val image =
            try {
                val world = chunks.world(state.name, state.worldMeta, state.tileEntity)
                val resolved = world.chunk.getBlockState(ORIGIN.x, ORIGIN.y, ORIGIN.z)
                blockState = format(resolved)
                blockEntity =
                    world.chunk
                        .getBlockEntity(ORIGIN.x, ORIGIN.y, ORIGIN.z)
                        ?.id
                        ?.formatted
                resources.resourcePack.getBlockState(resolved)?.forEach(resolved, ORIGIN.x, ORIGIN.y, ORIGIN.z) {
                    variants += VariantInfo(it.renderer.key.formatted, it.model.formatted)
                }

                model.clear()
                try {
                    for (pass in passes) pass.render(world, MODEL_MIN, MODEL_MAX, ORIGIN, modelView.initialize(), NO_TILE_META)
                } catch (ex: MaxCapacityReachedException) {
                    // HiresModelManager keeps (and saves) the incomplete model
                    error = describe(ex)
                }
                triangles = model.size()
                model.sort()
                val prbm = ByteArrayOutputStream()
                PRBMWriter(prbm).use { it.write(model) }
                val mesh = PrbmDecoder.decode(prbm.toByteArray())

                missingTexture = variants.any { it.model == MISSING_MODEL } ||
                    mesh.materials.any { resources.textures.isMissing(it) }
                rasterizer.render(mesh, resources.textures::get, OracleRenderSettings.AMBIENT_LIGHT.toDouble())
            } catch (ex: Throwable) {
                if (ex is VirtualMachineError && ex !is StackOverflowError) throw ex
                // BlueMap would not save the tile at all
                error = describe(ex)
                blank
            }
        return StateResult(state, blockState, blockEntity, variants, triangles, missingTexture, error, image, System.nanoTime() - start)
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
        val ORIGIN = Vector3i(0, 64, 0)

        /**
         * The column of the block over the full height, as `HiresModelManager` renders tiles: with min = max = (0, 64, 0)
         * `BlockRenderPass` would render air, because `BlockNeighborhood.set(0, 64, 0)` hits the ring-buffer slot of
         * its initial position (0, 0, 0) and does not move (a real tile column starts at the chunk's max y, 7 mod 8).
         */
        val MODEL_MIN = Vector3i(0, Int.MIN_VALUE, 0)
        val MODEL_MAX = Vector3i(0, Int.MAX_VALUE, 0)
        val NO_TILE_META = TileMetaConsumer { _, _, _, _, _ -> }
        val MISSING_MODEL: String = ResourcePack.MISSING_BLOCK_MODEL.formatted

        fun format(state: BlockState): String = state.toString().removeSuffix("[]")

        /** The exception and the first BlueMap frame of its stack trace. */
        fun describe(ex: Throwable): String {
            val frame = ex.stackTrace.firstOrNull { it.className.startsWith("de.bluecolored.") } ?: ex.stackTrace.firstOrNull()
            return if (frame == null) ex.toString() else "$ex @ $frame"
        }
    }
}
