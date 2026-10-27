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

import com.google.gson.JsonParser
import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.BlockState
import de.bluecolored.bluemap.core.world.mca.blockentity.SignBlockEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FlatteningTest {
    private fun s(state: String): BlockState = BlockState.fromString(state)

    // ---- contract C1: resource key normalization ----

    @Test
    fun resourceKeyVectors() {
        val file = Path.of("../testing/fixtures/resource-key-vectors.json")
        val vectors = JsonParser.parseString(Files.readString(file)).asJsonObject.getAsJsonArray("vectors")
        assertTrue(vectors.size() >= 20)
        val seen = HashSet<Pair<String, String>>()
        for (element in vectors) {
            val v = element.asJsonObject
            val input = v.get("input").asString
            val expected = v.get("namespace").asString to v.get("value").asString
            assertEquals(expected, ResourceKeys.normalizeRegistryName(input), input)
            assertEquals(Key(expected.first, expected.second), RegistryBlockStateMapper.resourceKey(input), input)
            // injective: case-colliding names stay distinct
            assertTrue(seen.add(expected), "collision for $input")
            for (c in expected.first + expected.second) assertTrue(c in 'a'..'z' || c in '0'..'9' || c in "_.-~!", input)
        }
    }

    @Test
    fun moddedBlocksKeepNormalizedIdentity() {
        val registry =
            FmlBlockRegistry(
                mapOf(
                    1 to "minecraft:stone",
                    955 to "BiomesOPlenty:colorizedLeaves2",
                    3005 to "gregtech:gt.blockcasings2",
                ),
            )
        val mapper = FlatteningBlockStateMapper(registry)
        assertEquals(s("~biomes~o~plenty:colorized~leaves2[meta=3]"), mapper.map(955, 3))
        assertEquals(s("gregtech:gt.blockcasings2[meta=12]"), mapper.map(3005, 12))
        assertEquals(null, mapper.fixer(mapper.map(3005, 12)))
        assertEquals(s("minecraft:stone"), mapper.map(1, 0))
        assertSame(BlockState.AIR, mapper.map(0, 0))
    }

    // ---- flattening table ----

    @Test
    fun representativeBlocks() {
        val mapper = FlatteningBlockStateMapper(FmlBlockRegistry.VANILLA_1_7_10)

        fun flat(
            id: Int,
            meta: Int,
        ) = mapper.map(id, meta)
        assertEquals(s("minecraft:granite"), flat(1, 1))
        assertEquals(s("minecraft:grass_block[snowy=false]"), flat(2, 0))
        assertEquals(s("minecraft:podzol[snowy=false]"), flat(3, 2))
        assertEquals(s("minecraft:spruce_log[axis=x]"), flat(17, 5))
        assertEquals(s("minecraft:oak_wood[axis=y]"), flat(17, 12)) // completed with the 1.13.2 default
        assertEquals(s("minecraft:red_wool"), flat(35, 14))
        assertEquals(s("minecraft:stone_slab[type=top,waterlogged=false]"), flat(44, 8))
        assertEquals(s("minecraft:smooth_stone"), flat(43, 8))
        assertEquals(s("minecraft:wall_torch[facing=east]"), flat(50, 1))
        assertEquals(s("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"), flat(53, 7))
        assertEquals(s("minecraft:redstone_wire[east=none,north=none,power=15,south=none,west=none]"), flat(55, 15))
        assertEquals(s("minecraft:wheat[age=7]"), flat(59, 7))
        assertEquals(s("minecraft:comparator[facing=south,mode=compare,powered=true]"), flat(149, 8))
        assertEquals(s("minecraft:light_blue_terracotta"), flat(159, 3))
        assertEquals(s("minecraft:large_fern[half=lower]"), flat(175, 3))
        assertEquals(s("minecraft:peony[half=upper]"), flat(175, 8)) // the upper half's type comes from below
        // unused metas render like the lowest meta (1.7 look), but are marked as unknown to Mojang's table
        assertEquals(s("minecraft:redstone_ore[lit=false]"), flat(73, 4))
        assertFalse(VanillaFlattening.isRegistered("minecraft:redstone_ore", 4))
        assertTrue(VanillaFlattening.isRegistered("minecraft:redstone_ore", 0))
    }

    @Test
    fun forgeIdsAreResolvedByName() {
        // a Forge world could assign other ids; flattening goes by registry name
        val registry = FmlBlockRegistry(mapOf(1234 to "minecraft:wool"))
        assertEquals(s("minecraft:red_wool"), FlatteningBlockStateMapper(registry).map(1234, 14))
    }

    // ---- neighbour-dependent shapes, also across chunk borders (x = 15 | 16) ----

    @Test
    fun fencesPanesWallsAcrossChunkBorder() {
        val w = TestWorld()
        for (x in 14..17) w.set(x, 4, 0, "fence")
        w.set(15, 4, 1, "stone").set(15, 4, -1, "glass")
        for (x in 14..17) w.set(x, 4, 5, "glass_pane")
        w.set(16, 4, 6, "glass")
        for (x in 14..16) w.set(x, 4, 10, "cobblestone_wall")
        w.set(16, 4, 9, "nether_brick_fence")
        w.set(15, 5, 10, "torch", 5)

        // fences connect to fences and solid stone, not to glass
        assertEquals(s("minecraft:oak_fence[east=true,north=false,south=true,waterlogged=false,west=true]"), w.state(15, 4, 0))
        assertEquals(s("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]"), w.state(16, 4, 0))
        // panes attach to glass (in 1.13.2, unlike fences)
        assertEquals(s("minecraft:glass_pane[east=true,north=false,south=true,waterlogged=false,west=true]"), w.state(16, 4, 5))
        // straight wall: post only where something sits on top; a nether fence does not attach to it
        assertEquals(
            s("minecraft:cobblestone_wall[east=true,north=false,south=false,up=true,waterlogged=false,west=true]"),
            w.state(15, 4, 10),
        )
        assertEquals(
            s("minecraft:cobblestone_wall[east=false,north=false,south=false,up=true,waterlogged=false,west=true]"),
            w.state(16, 4, 10),
        )
        assertEquals(s("minecraft:nether_brick_fence[east=false,north=false,south=false,waterlogged=false,west=false]"), w.state(16, 4, 9))
        w.set(15, 5, 10, "air")
        assertEquals(
            s("minecraft:cobblestone_wall[east=true,north=false,south=false,up=false,waterlogged=false,west=true]"),
            w.state(15, 4, 10),
        )
    }

    @Test
    fun stairsChestsAndWireAcrossChunkBorder() {
        val w = TestWorld()
        // stairs facing east (meta 0) at x=15 with a south-facing stair (meta 2) in front at x=16: outer corner
        w.set(15, 4, 0, "oak_stairs", 0).set(16, 4, 0, "oak_stairs", 2)
        // north-facing chests (meta 2) pairing across the border
        w.set(15, 4, 4, "chest", 2).set(16, 4, 4, "chest", 2)
        // three chests in a row pair from the west end
        w.set(1, 4, 8, "chest", 2).set(2, 4, 8, "chest", 2).set(3, 4, 8, "chest", 2)
        // wire line across the border, climbing onto a stone block
        w
            .set(14, 4, 12, "redstone_wire")
            .set(15, 4, 12, "redstone_wire")
            .set(16, 4, 12, "stone")
            .set(16, 5, 12, "redstone_wire")

        assertEquals("outer_right", w.state(15, 4, 0).properties["shape"])
        assertEquals("straight", w.state(16, 4, 0).properties["shape"])
        // a north-facing chest whose partner is to the east (its clockwise side) is the left half
        assertEquals("left", w.state(15, 4, 4).properties["type"])
        assertEquals("right", w.state(16, 4, 4).properties["type"])
        assertEquals(listOf("left", "right", "single"), (1..3).map { w.state(it, 4, 8).properties["type"] })
        val wire = w.state(15, 4, 12).properties
        assertEquals("side", wire["west"])
        assertEquals("up", wire["east"])
        assertEquals("side", w.state(16, 5, 12).properties["west"])
    }

    @Test
    fun verticalFixes() {
        val w = TestWorld()
        // door: lower half (facing/open) + upper half (hinge/powered)
        w.set(0, 4, 0, "wooden_door", 1 or 4).set(0, 5, 0, "wooden_door", 8 or 1)
        // double plant: upper half takes the type of the lower half
        w.set(2, 4, 0, "double_plant", 4).set(2, 5, 0, "double_plant", 8)
        // snowy grass below snow layers and snow blocks, not below stone
        w.set(4, 3, 0, "grass").set(4, 4, 0, "snow_layer")
        w.set(5, 3, 0, "grass").set(5, 4, 0, "snow")
        w.set(6, 3, 0, "grass").set(6, 4, 0, "stone")
        // carved pumpkin on dirt becomes a plain pumpkin, on stone it keeps its face
        w.set(8, 3, 0, "dirt").set(8, 4, 0, "pumpkin", 2)
        w.set(9, 3, 0, "stone").set(9, 4, 0, "pumpkin", 2)
        // a bed half without its other half is gone
        w.set(10, 4, 0, "bed", 0)

        val door = s("minecraft:oak_door[facing=south,half=lower,hinge=right,open=true,powered=false]")
        assertEquals(door, w.state(0, 4, 0))
        assertEquals(s("minecraft:oak_door[facing=south,half=upper,hinge=right,open=true,powered=false]"), w.state(0, 5, 0))
        assertEquals(s("minecraft:rose_bush[half=upper]"), w.state(2, 5, 0))
        assertEquals("true", w.state(4, 3, 0).properties["snowy"])
        assertEquals("true", w.state(5, 3, 0).properties["snowy"])
        assertEquals("false", w.state(6, 3, 0).properties["snowy"])
        assertEquals(s("minecraft:pumpkin"), w.state(8, 4, 0))
        assertEquals(s("minecraft:carved_pumpkin[facing=north]"), w.state(9, 4, 0))
        assertSame(BlockState.AIR, w.state(10, 4, 0))
    }

    // ---- block-entity dependent states ----

    @Test
    fun flowerPotsSkullsAndSigns() {
        val w = TestWorld()
        w.set(0, 4, 0, "flower_pot").tileEntity(0, 4, 0, "FlowerPot") {
            name("Item").value(38) // numeric 1.7 item id of minecraft:red_flower
            name("Data").value(1)
        }
        w.set(1, 4, 0, "flower_pot").tileEntity(1, 4, 0, "FlowerPot") {
            name("Item").value(1) // stone can not be potted
            name("Data").value(0)
        }
        w.set(2, 4, 0, "flower_pot").tileEntity(2, 4, 0, "FlowerPot") {
            name("Item").value("minecraft:sapling") // newer saves store the name
            name("Data").value(4)
        }
        w.set(3, 4, 0, "skull", 1).tileEntity(3, 4, 0, "Skull") {
            name("SkullType").value(2.toByte())
            name("Rot").value(5.toByte())
        }
        w.set(4, 4, 0, "skull", 3).tileEntity(4, 4, 0, "Skull") {
            name("SkullType").value(4.toByte())
            name("Rot").value(0.toByte())
        }
        w.set(5, 4, 0, "skull", 1)
        w.set(6, 4, 0, "noteblock").tileEntity(6, 4, 0, "Music") { name("note").value(12.toByte()) }
        w.set(7, 4, 0, "standing_sign", 4).tileEntity(7, 4, 0, "Sign") {
            name("Text1").value("Hello")
            name("Text2").value("GTNH")
            name("Text3").value("")
            name("Text4").value("1.7.10")
        }

        assertEquals(s("minecraft:potted_blue_orchid"), w.state(0, 4, 0))
        assertEquals(s("minecraft:flower_pot"), w.state(1, 4, 0))
        assertEquals(s("minecraft:potted_acacia_sapling"), w.state(2, 4, 0))
        assertEquals(s("minecraft:zombie_head[rotation=5]"), w.state(3, 4, 0))
        assertEquals(s("minecraft:creeper_wall_head[facing=south]"), w.state(4, 4, 0))
        assertSame(BlockState.AIR, w.state(5, 4, 0)) // no block entity: 1.7 renders nothing
        assertEquals("12", w.state(6, 4, 0).properties["note"])

        val chunk = w.chunk(0, 0)
        assertEquals(Key.minecraft("skull"), chunk.getBlockEntity(3, 4, 0)!!.id)
        val sign = chunk.getBlockEntity(7, 4, 0) as SignBlockEntity
        assertEquals(Key.minecraft("sign"), sign.id)
        assertEquals(listOf<Any>("Hello", "GTNH", "", "1.7.10"), sign.frontText!!.messages)
    }

    @Test
    fun fixedStatesAreCachedPerPosition() {
        val w = TestWorld()
        w.set(0, 4, 0, "fence").set(1, 4, 0, "fence")
        val chunk = w.chunk(0, 0)
        assertSame(chunk.getBlockState(0, 4, 0), chunk.getBlockState(0, 4, 0))
        // the base state is the plain table state
        assertEquals("false", chunk.getBaseBlockState(0, 4, 0).properties["east"])
        assertEquals("true", chunk.getBlockState(0, 4, 0).properties["east"])
    }
}
