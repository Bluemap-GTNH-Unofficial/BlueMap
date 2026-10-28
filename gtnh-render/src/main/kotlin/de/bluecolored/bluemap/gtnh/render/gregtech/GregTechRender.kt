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
package de.bluecolored.bluemap.gtnh.render.gregtech

import de.bluecolored.bluemap.core.map.hires.block.BlockRendererFactory
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType

/**
 * GregTech rendering: the exporter's blockstates select `gtnh:gt_machine` (`gt.blockmachines`), `gtnh:gt_ore`
 * (`gt.blockores2..7`) and `gtnh:gt_frame` (`gt.blockframes`); the renderers read the GT dump through
 * [GregTechExtension] and machine/pipe state from the [GtTileEntity] block entities.
 */
object GregTechRender {
    @JvmField
    val MACHINE: BlockRendererType =
        BlockRendererType.Impl(Key("gtnh", "gt_machine"), BlockRendererFactory(::GtMachineRenderer))

    @JvmField
    val ORE: BlockRendererType = BlockRendererType.Impl(Key("gtnh", "gt_ore"), BlockRendererFactory(::GtOreRenderer))

    @JvmField
    val FRAME: BlockRendererType = BlockRendererType.Impl(Key("gtnh", "gt_frame"), BlockRendererFactory(::GtFrameRenderer))

    @Volatile
    private var registered = false

    /** Registers the renderers, the dump extension and the GT block entity types. Idempotent. */
    @JvmStatic
    @Synchronized
    fun register() {
        if (registered) return
        registered = true
        BlockRendererType.REGISTRY.register(MACHINE)
        BlockRendererType.REGISTRY.register(ORE)
        BlockRendererType.REGISTRY.register(FRAME)
        ResourcePack.Extension.REGISTRY.register(GregTechExtension.TYPE)
        BlockEntityType.REGISTRY.register(GtTileEntity.MACHINE)
        BlockEntityType.REGISTRY.register(GtTileEntity.PIPE)
    }
}
