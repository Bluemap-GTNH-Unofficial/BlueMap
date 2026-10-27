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

import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.resources.pack.PackVersion
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.gtnh.legacy.ResourceKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Loads exporter-shaped resource packs through BlueMap's real [ResourcePack] loader. */
class ResourcePackIntegrationTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun registerGtnhRender() = GtnhRender.register()

        private val VECTORS: Path = Paths.get("../testing/fixtures/resource-key-vectors.json")
    }

    @TempDir
    lateinit var root: Path

    private fun file(path: String, content: String) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun png(path: String, rgb: Int) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, rgb or (0xFF shl 24))
        ImageIO.write(image, "png", file.toFile())
    }

    private val cube = """
        {"textures": {"all": "~mod:block/side"},
         "elements": [{"from": [0,0,0], "to": [16,16,16], "faces": {
           "up": {"texture": "#all", "cullface": "up"}, "down": {"texture": "#all", "cullface": "down"}}}]}
    """.trimIndent()

    private fun load(): ResourcePack {
        file("assets/minecraft/atlases/blocks.json", """{"sources": [{"type": "minecraft:directory", "source": "", "prefix": ""}]}""")
        val pack = ResourcePack(PackVersion(4, 0))
        pack.loadResources(listOf(root))
        return pack
    }

    private fun state(namespace: String, value: String, meta: Int) = BlockState(Key(namespace, value), mapOf("meta" to meta.toString()))

    @Test
    fun `quad models, their textures and coverage classification`() {
        file(
            "assets/~mod/blockstates/thing.json",
            """
            {"variants": {
              "meta=0": {"renderer": "gtnh:quads", "model": "~mod:gtnh_quads/thing/0"},
              "meta=1": {"model": "~mod:block/does_not_exist"},
              "meta=2": {"model": "~mod:block/cube"},
              "meta=4": {"renderer": "gtnh:quads", "model": "~mod:gtnh_quads/thing/missing"}
            }}
            """.trimIndent(),
        )
        file(
            "assets/~mod/models/gtnh_quads/thing/0.json",
            """
            {"textures": {"t0": "~mod:block/top"},
             "gtnh_quads": [
               {"texture": "#t0", "vertices": [[16,16,16,16,16],[16,16,0,16,0],[0,16,0,0,0],[0,16,16,0,16]]},
               {"texture": "~mod:block/extra", "vertices": [[0,0,8,0,16],[16,0,8,16,16],[16,16,8,16,0],[0,16,8,0,0]]}
             ]}
            """.trimIndent(),
        )
        file("assets/~mod/models/block/cube.json", cube)
        file("assets/bluemap/blockstates/missing.json", """{"variants": {"": {"renderer": "bluemap:missing", "model": "bluemap:block/missing"}}}""")
        png("assets/~mod/textures/block/top.png", 0x00FF00)
        png("assets/~mod/textures/block/side.png", 0x0000FF)
        png("assets/~mod/textures/block/extra.png", 0xFF0000)
        png("assets/~mod/textures/block/unused.png", 0xFFFFFF)

        val pack = load()

        val quads = pack.getExtension(QuadModelExtension.TYPE)
        val model = requireNotNull(quads[Key("~mod", "gtnh_quads/thing/0")]) { "quad model loaded" }
        assertEquals(2, model.quads.size)

        // textures referenced only inline by quads are loaded too; unreferenced ones are not
        assertNotNull(pack.textures[Key("~mod", "block/top")])
        assertNotNull(pack.textures[Key("~mod", "block/extra")])
        assertNotNull(pack.textures[Key("~mod", "block/side")])
        assertEquals(null, pack.textures[Key("~mod", "block/unused")])

        assertEquals(CoverageReport.Status.OK, CoverageReport.classify(pack, state("~mod", "thing", 0)))
        assertEquals(CoverageReport.Status.MISSING_MODEL, CoverageReport.classify(pack, state("~mod", "thing", 1)))
        assertEquals(CoverageReport.Status.OK, CoverageReport.classify(pack, state("~mod", "thing", 2)))
        // no variant for meta=3: BlueMap renders the first variant (meta=0)
        assertEquals(CoverageReport.Status.FALLBACK, CoverageReport.classify(pack, state("~mod", "thing", 3)))
        assertEquals(CoverageReport.Status.MISSING_MODEL, CoverageReport.classify(pack, state("~mod", "thing", 4)))
        assertEquals(CoverageReport.Status.NO_BLOCKSTATE, CoverageReport.classify(pack, state("~mod", "other", 0)))
        // ... but it is drawn as BlueMap's missing block instead of nothing; vanilla ids are left alone
        val missing = requireNotNull(pack.blockStates[ResourcePack.MISSING_BLOCK_STATE])
        assertSame(missing, pack.getBlockState(state("~mod", "other", 0)))
        assertEquals(null, pack.getBlockState(BlockState(Key("minecraft", "not_a_block"))))

        val variants = ArrayList<Key>()
        pack.getBlockState(state("~mod", "thing", 0))!!.forEach(state("~mod", "thing", 0), 0, 0, 0) { variants.add(it.model) }
        assertEquals(listOf(Key("~mod", "gtnh_quads/thing/0")), variants)
    }

    @Test
    fun `normalized registry names resolve to distinct blockstate files`() {
        val vectors = JsonParser.parseString(VECTORS.readText()).asJsonObject.getAsJsonArray("vectors")
        assertTrue(vectors.size() > 0)

        val expected = ArrayList<Pair<Key, String>>()
        for (element in vectors) {
            val vector = element.asJsonObject
            val input = vector.get("input").asString
            val namespace = vector.get("namespace").asString
            val value = vector.get("value").asString
            assertEquals(namespace to value, ResourceKeys.normalizeRegistryName(input), "N($input)")
            if (value.isEmpty()) continue
            // the model name records which registry name the file was written for
            val model = "test:block/" + expected.size
            file("assets/$namespace/blockstates/$value.json", """{"variants": {"meta=0": {"model": "$model"}}}""")
            expected.add(Key(namespace, value) to model)
        }
        file("assets/test/models/block/0.json", cube)

        val pack = load()
        for ((key, model) in expected) {
            val state = BlockState(key, mapOf("meta" to "0"))
            val blockState = requireNotNull(pack.getBlockState(state)) { "blockstate for $key" }
            val models = ArrayList<String>()
            blockState.forEach(state, 0, 0, 0) { models.add(it.model.formatted) }
            assertEquals(listOf(model), models, "no other name's file overwrote $key (case-insensitive file systems)")
        }
        assertEquals(expected.size, expected.map { it.first }.toSet().size)
    }
}
