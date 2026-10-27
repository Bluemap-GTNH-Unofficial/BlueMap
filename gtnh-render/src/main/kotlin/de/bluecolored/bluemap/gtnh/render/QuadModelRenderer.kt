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
package de.bluecolored.bluemap.gtnh.render

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.map.hires.RenderSettings
import de.bluecolored.bluemap.core.map.hires.TileModelView
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer
import de.bluecolored.bluemap.core.map.hires.block.color.BlockColorCalculator
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant
import de.bluecolored.bluemap.core.util.math.Color
import de.bluecolored.bluemap.core.util.math.VectorM3f
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.core.world.block.ExtendedBlock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a [QuadModel] (blockstate variants with `"renderer": "gtnh:quads"`; the variant's `model` names the quad
 * model). Lighting, cave removal, culling and the map color follow BlueMap's `ResourceModelRenderer`; there is no
 * ambient occlusion because captured quads have no cuboid structure to test against.
 */
class QuadModelRenderer(
    resourcePack: ResourcePack,
    private val textureGallery: TextureGallery,
    private val renderSettings: RenderSettings,
) : BlockRenderer {
    private val extension: QuadModelExtension = resourcePack.getExtension(QuadModelExtension.TYPE)
    private val textures = resourcePack.textures
    private val blockColorCalculator: BlockColorCalculator = resourcePack.createBlockColorCalculator()

    private val tintColor = Color()
    private val mapColor = Color()
    private val direction = VectorM3f(0f, 0f, 0f)
    private val textureIds = IntArray(64)

    override fun render(block: BlockNeighborhood, variant: Variant, blockModel: TileModelView, color: Color) {
        val model = extension[variant.model] ?: return
        tintColor.set(0f, 0f, 0f, -1f, true)
        var blockColorOpacity = 0f

        val textureCount = model.textures.size
        val ids = if (textureCount <= textureIds.size) textureIds else IntArray(textureCount)
        for (i in 0 until textureCount) ids[i] = textureGallery.get(model.textures[i])

        val modelStart = blockModel.start
        val ownLight = block.lightData
        for (quad in model.quads) {
            val faced = relativeBlock(block, variant, quad.facing.toVector().x, quad.facing.toVector().y, quad.facing.toVector().z)
            val facedLight = faced.lightData
            val sunLight = max(ownLight.skyLight, facedLight.skyLight)
            val blockLight = max(ownLight.blockLight, facedLight.blockLight)

            if (block.isRemoveIfCave &&
                (if (renderSettings.isCaveDetectionUsesBlockLight) max(blockLight, sunLight) else sunLight) == 0
            ) continue

            if (renderSettings.isRenderTopOnly && quad.normalY < 0.01f) continue

            val cullface = quad.cullface
            if (cullface != null) {
                val v = cullface.toVector()
                val neighbor = relativeBlock(block, variant, v.x, v.y, v.z)
                val properties = neighbor.properties
                if (properties.isCulling) continue
                if (properties.cullingIdentical && neighbor.blockState == block.blockState) continue
            }

            blockModel.initialize()
            blockModel.add(2)
            val tileModel = blockModel.tileModel
            val face1 = blockModel.start
            val face2 = face1 + 1
            val p = quad.positions
            tileModel.setPositions(face1, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8])
            tileModel.setPositions(face2, p[0], p[1], p[2], p[6], p[7], p[8], p[9], p[10], p[11])
            val t = quad.uvs
            tileModel.setUvs(face1, t[0], t[1], t[2], t[3], t[4], t[5])
            tileModel.setUvs(face2, t[0], t[1], t[4], t[5], t[6], t[7])

            val textureId = ids[quad.texture]
            tileModel.setMaterialIndex(face1, textureId)
            tileModel.setMaterialIndex(face2, textureId)

            var r = quad.r
            var g = quad.g
            var b = quad.b
            if (quad.tint) {
                if (tintColor.a < 0) blockColorCalculator.getBlockColor(block, tintColor)
                r *= tintColor.r
                g *= tintColor.g
                b *= tintColor.b
            }
            tileModel.setColor(face1, r, g, b)
            tileModel.setColor(face2, r, g, b)

            val emissive = max(blockLight, quad.lightEmission)
            tileModel.setBlocklight(face1, emissive)
            tileModel.setBlocklight(face2, emissive)
            tileModel.setSunlight(face1, sunLight)
            tileModel.setSunlight(face2, sunLight)
            tileModel.setAOs(face1, 1f, 1f, 1f)
            tileModel.setAOs(face2, 1f, 1f, 1f)

            if (quad.normalY > 0.01f) {
                val texture = model.textures[quad.texture].getResource(textures::get)
                if (texture != null) {
                    mapColor.set(texture.colorPremultiplied)
                    mapColor.r *= r
                    mapColor.g *= g
                    mapColor.b *= b
                    var light = max(sunLight / 15f, blockLight / 15f)
                    light = (1 - renderSettings.ambientLight) * light + renderSettings.ambientLight
                    mapColor.r *= light
                    mapColor.g *= light
                    mapColor.b *= light
                    if (mapColor.a > blockColorOpacity) blockColorOpacity = mapColor.a
                    color.add(mapColor)
                }
            }
        }

        if (color.a > 0) {
            color.flatten().straight()
            color.a = blockColorOpacity
        }

        blockModel.initialize(modelStart)
        if (variant.isTransformed) blockModel.transform(variant.transformMatrix)

        if (block.properties.isRandomOffset) {
            val dx = (hashToFloat(block.x, block.z, 123984) - 0.5f) * 0.75f
            val dz = (hashToFloat(block.x, block.z, 345542) - 0.5f) * 0.75f
            blockModel.translate(dx, 0f, dz)
        }
    }

    private fun relativeBlock(block: BlockNeighborhood, variant: Variant, dx: Int, dy: Int, dz: Int): ExtendedBlock {
        if (!variant.isTransformed) return block.getNeighborBlock(dx, dy, dz)
        direction.set(dx.toFloat(), dy.toFloat(), dz.toFloat())
        direction.rotateAndScale(variant.transformMatrix)
        return block.getNeighborBlock(direction.x.roundToInt(), direction.y.roundToInt(), direction.z.roundToInt())
    }

    private fun hashToFloat(x: Int, z: Int, seed: Long): Float {
        val hash = x * 73428767L xor z * 4382893L xor seed * 457
        return (hash * (hash + 456149) and 0x00ffffff) / 0x01000000.toFloat()
    }
}
