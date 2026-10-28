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
package de.bluecolored.bluemap.gtnh.render.mods.addons

import de.bluecolored.bluemap.core.util.Direction
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluemap.gtnh.render.mods.FaceTexture
import de.bluecolored.bluemap.gtnh.render.mods.Mesh
import de.bluecolored.bluemap.gtnh.render.mods.MeshBuilder
import de.bluecolored.bluemap.gtnh.render.mods.ModAssets
import de.bluecolored.bluemap.gtnh.render.mods.Transform
import de.bluecolored.bluenbt.NBTName
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GregTech's `RenderingTileEntityLaser` (beam colour, visibility and the controller's facing as an axis-angle
 * rotation in degrees) plus the `range` both addons' laser beacons add.
 */
class GtLaserTileEntity : MCABlockEntity() {
    @field:NBTName("rgb_red")
    var red: Float = 0f
        private set

    @field:NBTName("rgb_green")
    var green: Float = 0f
        private set

    @field:NBTName("rgb_blue")
    var blue: Float = 0f
        private set

    @field:NBTName("shouldRender")
    var shouldRender: Boolean = false
        private set

    @field:NBTName("rotAxisX")
    var rotAxisX: Double = 0.0
        private set

    @field:NBTName("rotAxisY")
    var rotAxisY: Double = 0.0
        private set

    @field:NBTName("rotAxisZ")
    var rotAxisZ: Double = 0.0
        private set

    @field:NBTName("rotationAngle")
    var rotationAngle: Double = 0.0
        private set

    @field:NBTName("range")
    var range: Double = 0.0
        private set
}

/**
 * The meteor miners' laser beacons (TST `MeteorMinerRenderer`, GTNL `LaserBeconRenderer`): the block is a normal
 * cube; while the miner works its TESR adds a full-bright untextured column 0.5 wide from the block centre up to
 * `range`, turned about the block centre with the controller's facing.
 */
object GtLaserBeacon : AddonTesr<GtLaserTileEntity>(
    listOf("MeteorMinerRenderer", "laser_beacon_tile_entity"),
    GtLaserTileEntity::class.java,
) {
    override val rawTextures = setOf(AddonTesrs.WHITE)

    override val drawsBlockModel: Boolean get() = true

    override fun mesh(
        assets: ModAssets,
        entity: GtLaserTileEntity,
        block: BlockNeighborhood,
    ): Mesh? {
        if (!entity.shouldRender) return Mesh.EMPTY
        val white = assets.texture(AddonTesrs.WHITE) ?: return null
        return beam(white, entity)
    }

    fun beam(
        texture: Key,
        laser: GtLaserTileEntity,
    ): Mesh {
        val y1 = min(0.5, laser.range).toFloat() * 16
        val y2 = max(0.5, laser.range).toFloat() * 16
        val face = FaceTexture(texture, rgb = rgb(laser.red, laser.green, laser.blue), glow = true)
        // renderFakeLine(x + .5, y + range, z + .5, y + .5) with radius .25; the bottom lies inside the block
        val column = MeshBuilder().box(4f, y1, 4f, 12f, y2, 12f) { if (it == Direction.DOWN) null else face }.build()
        return column.transformed(rotation(laser)).doubleSided()
    }

    /** `glTranslated(.5, .5, .5); glRotated(rotationAngle, rotAxis); glTranslated(-.5, -.5, -.5)`. */
    fun rotation(laser: GtLaserTileEntity): Transform {
        val x = laser.rotAxisX.toFloat()
        val y = laser.rotAxisY.toFloat()
        val z = laser.rotAxisZ.toFloat()
        if (laser.rotationAngle == 0.0 || sqrt(x * x + y * y + z * z) < 1e-6f) return Transform.IDENTITY
        return Transform.gl(CENTER, Transform.rotate(laser.rotationAngle.toFloat(), x, y, z), Transform.translate(-0.5f, -0.5f, -0.5f))
    }
}
