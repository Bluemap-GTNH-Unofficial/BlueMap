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

import de.bluecolored.bluemap.core.map.TextureGallery
import de.bluecolored.bluemap.core.resources.pack.ResourcePool
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.AnimationMeta
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture
import de.bluecolored.bluemap.core.util.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.StringWriter

class TextureTableTest {
    private fun image(
        height: Int,
        argb: (Int) -> Int,
    ) = BufferedImage(16, height, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until 16) setRGB(x, y, argb(y))
    }

    @Test
    fun `reads the texture gallery like the webapp`() {
        val frames = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val pool = ResourcePool<Texture>()
        pool.put(Key("foo", "block/plain"), Texture.from(Key("foo", "block/plain"), image(16) { 0xFF102030.toInt() }))
        pool.put(Key("foo", "block/glass"), Texture.from(Key("foo", "block/glass"), image(16) { 0x80FFFFFF.toInt() }))
        val animation = AnimationMeta(false, 1, 1, 2, listOf(AnimationMeta.FrameMeta(2, 2), AnimationMeta.FrameMeta(0, 2)))
        pool.put(Key("foo", "block/anim"), Texture.from(Key("foo", "block/anim"), image(48) { frames[it / 16] }, animation))
        pool.put(
            Key("foo", "block/ticking"),
            Texture.from(
                Key("foo", "block/ticking"),
                image(48) {
                    frames[it / 16]
                },
                AnimationMeta(false, 1, 1, 1, null),
            ),
        )
        val gallery = TextureGallery().apply { put(pool) }
        val table = TextureTable.parse(StringWriter().also { gallery.writeTexturesFile(it) }.toString())

        assertEquals(5, table.size)
        assertEquals(0, gallery.get(ResourcePack.MISSING_TEXTURE))
        assertTrue(table.isMissing(0))
        assertTrue(table.isMissing(-1) && table.isMissing(5))
        assertNull(table[5])

        val plain = requireNotNull(table[gallery.get(Key("foo", "block/plain"))])
        assertFalse(table.isMissing(gallery.get(Key("foo", "block/plain"))))
        assertFalse(plain.halfTransparent || plain.animated)
        assertEquals(0xFF102030.toInt(), plain.texel(0.5, 0.5))

        val glass = requireNotNull(table[gallery.get(Key("foo", "block/glass"))])
        assertTrue(glass.halfTransparent)
        assertEquals(0x80FFFFFF.toInt(), glass.texel(0.5, 0.5))

        // the first entry of the frame list, which BlueMap's own Gson reader would drop
        val anim = requireNotNull(table[gallery.get(Key("foo", "block/anim"))])
        assertTrue(anim.animated)
        assertEquals(2, anim.frame)
        assertEquals(frames[2], anim.texel(0.5, 0.0))
        assertEquals(frames[2], anim.texel(0.5, 0.99))

        val ticking = requireNotNull(table[gallery.get(Key("foo", "block/ticking"))])
        assertTrue(ticking.animated)
        assertEquals(frames[0], ticking.texel(0.5, 0.99))
    }
}
