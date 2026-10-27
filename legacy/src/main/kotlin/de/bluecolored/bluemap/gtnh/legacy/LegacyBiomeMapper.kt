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

import de.bluecolored.bluemap.core.util.Key
import de.bluecolored.bluemap.core.world.biome.Biome

/**
 * Maps a legacy numeric biome id (0..65535, EndlessIDs) to a BlueMap [Biome]. Must be thread-safe.
 */
fun interface LegacyBiomeMapper {
    fun biome(id: Int): Biome
}

/**
 * Resolves legacy biome ids through a key lookup (normally the loaded `DataPack`):
 * explicit [overrides] first (e.g. a runtime biome dump of the server), then the vanilla 1.7.10 id table,
 * each key tried with its current (1.18+) name first and its 1.13 name second, then [runtimeKey]
 * (`gtnh:biome_<id>`, written as a datapack biome by the server mod for non-vanilla ids).
 * Ids none of these resolve go to [fallbackKey], or [Biome.DEFAULT] if that is not available either.
 */
class VanillaLegacyBiomeMapper
    @JvmOverloads
    constructor(
        private val lookup: (Key) -> Biome?,
        private val overrides: Map<Int, Key> = emptyMap(),
        private val fallbackKey: Key = Key.minecraft("plains"),
    ) : LegacyBiomeMapper {
        private val cache = arrayOfNulls<Biome>(MAX_BIOME_ID + 1)
        private val fallback: Biome by lazy { lookup(fallbackKey) ?: Biome.DEFAULT }

        override fun biome(id: Int): Biome {
            if (id < 0 || id > MAX_BIOME_ID) return fallback
            // benign race: resolution is idempotent
            return cache[id] ?: resolve(id).also { cache[id] = it }
        }

        private fun resolve(id: Int): Biome {
            overrides[id]?.let { key -> resolveKey(key)?.let { return it } }
            legacyKey(id)?.let { key -> resolveKey(key)?.let { return it } }
            return lookup(runtimeKey(id)) ?: fallback
        }

        private fun resolveKey(key: Key): Biome? {
            MODERN_ALIASES[key]?.let { modern -> lookup(modern)?.let { return it } }
            return lookup(key)
        }

        companion object {
            const val MAX_BIOME_ID: Int = 0xFFFF

            /** Vanilla 1.7.10 biome ids with their 1.13 keys (the names `LegacyBiomes`/DataConverter use). */
            private val LEGACY_KEYS: Array<Key?> =
                arrayOfNulls<Key>(256).also { keys ->
                    fun put(
                        id: Int,
                        name: String,
                    ) {
                        keys[id] = Key.minecraft(name)
                    }
                    put(0, "ocean")
                    put(1, "plains")
                    put(2, "desert")
                    put(3, "mountains") // extreme_hills
                    put(4, "forest")
                    put(5, "taiga")
                    put(6, "swamp") // swampland
                    put(7, "river")
                    put(8, "nether") // hell
                    put(9, "the_end") // sky
                    put(10, "frozen_ocean")
                    put(11, "frozen_river")
                    put(12, "snowy_tundra") // ice_plains
                    put(13, "snowy_mountains") // ice_mountains
                    put(14, "mushroom_fields") // mushroom_island
                    put(15, "mushroom_field_shore")
                    put(16, "beach")
                    put(17, "desert_hills")
                    put(18, "wooded_hills") // forest_hills
                    put(19, "taiga_hills")
                    put(20, "mountain_edge") // extreme_hills_edge
                    put(21, "jungle")
                    put(22, "jungle_hills")
                    put(23, "jungle_edge")
                    put(24, "deep_ocean")
                    put(25, "stone_shore") // stone_beach
                    put(26, "snowy_beach") // cold_beach
                    put(27, "birch_forest")
                    put(28, "birch_forest_hills")
                    put(29, "dark_forest") // roofed_forest
                    put(30, "snowy_taiga") // cold_taiga
                    put(31, "snowy_taiga_hills")
                    put(32, "giant_tree_taiga") // mega_taiga
                    put(33, "giant_tree_taiga_hills")
                    put(34, "wooded_mountains") // extreme_hills_plus
                    put(35, "savanna")
                    put(36, "savanna_plateau")
                    put(37, "badlands") // mesa
                    put(38, "wooded_badlands_plateau") // mesa_plateau_f
                    put(39, "badlands_plateau") // mesa_plateau
                    put(129, "sunflower_plains")
                    put(130, "desert_lakes")
                    put(131, "gravelly_mountains")
                    put(132, "flower_forest")
                    put(133, "taiga_mountains")
                    put(134, "swamp_hills")
                    put(140, "ice_spikes")
                    put(149, "modified_jungle")
                    put(151, "modified_jungle_edge")
                    put(155, "tall_birch_forest")
                    put(156, "tall_birch_hills")
                    put(157, "dark_forest_hills")
                    put(158, "snowy_taiga_mountains")
                    put(160, "giant_spruce_taiga")
                    put(161, "giant_spruce_taiga_hills")
                    put(162, "modified_gravelly_mountains")
                    put(163, "shattered_savanna")
                    put(164, "shattered_savanna_plateau")
                    put(165, "eroded_badlands")
                    put(166, "modified_wooded_badlands_plateau")
                    put(167, "modified_badlands_plateau")
                }

            /** 1.13 biome keys that were renamed or merged in 1.16-1.18 (Mojang's biome renames / 1.18 merges). */
            private val MODERN_ALIASES: Map<Key, Key> =
                mapOf(
                    "mountains" to "windswept_hills",
                    "nether" to "nether_wastes",
                    "snowy_tundra" to "snowy_plains",
                    "snowy_mountains" to "snowy_plains",
                    "mushroom_field_shore" to "mushroom_fields",
                    "desert_hills" to "desert",
                    "wooded_hills" to "forest",
                    "taiga_hills" to "taiga",
                    "mountain_edge" to "windswept_hills",
                    "jungle_hills" to "jungle",
                    "jungle_edge" to "sparse_jungle",
                    "stone_shore" to "stony_shore",
                    "birch_forest_hills" to "birch_forest",
                    "snowy_taiga_hills" to "snowy_taiga",
                    "giant_tree_taiga" to "old_growth_pine_taiga",
                    "giant_tree_taiga_hills" to "old_growth_pine_taiga",
                    "wooded_mountains" to "windswept_forest",
                    "wooded_badlands_plateau" to "wooded_badlands",
                    "badlands_plateau" to "badlands",
                    "desert_lakes" to "desert",
                    "gravelly_mountains" to "windswept_gravelly_hills",
                    "taiga_mountains" to "taiga",
                    "swamp_hills" to "swamp",
                    "modified_jungle" to "jungle",
                    "modified_jungle_edge" to "sparse_jungle",
                    "tall_birch_forest" to "old_growth_birch_forest",
                    "tall_birch_hills" to "old_growth_birch_forest",
                    "dark_forest_hills" to "dark_forest",
                    "snowy_taiga_mountains" to "snowy_taiga",
                    "giant_spruce_taiga" to "old_growth_spruce_taiga",
                    "giant_spruce_taiga_hills" to "old_growth_spruce_taiga",
                    "modified_gravelly_mountains" to "windswept_gravelly_hills",
                    "shattered_savanna" to "windswept_savanna",
                    "shattered_savanna_plateau" to "windswept_savanna",
                    "modified_wooded_badlands_plateau" to "wooded_badlands",
                    "modified_badlands_plateau" to "badlands",
                ).entries.associate { (old, new) -> Key.minecraft(old) to Key.minecraft(new) }

            /** The 1.13 key of a vanilla 1.7.10 biome id, or null if the id is not a vanilla biome. */
            @JvmStatic
            fun legacyKey(id: Int): Key? = if (id in LEGACY_KEYS.indices) LEGACY_KEYS[id] else null

            /** The datapack key under which the server mod publishes a (non-vanilla) runtime biome: `gtnh:biome_<id>`. */
            @JvmStatic
            fun runtimeKey(id: Int): Key = Key("gtnh", "biome_$id")

            /** The current (1.18+) key of a 1.13 biome key, or the key itself if it was not renamed. */
            @JvmStatic
            fun modernKey(key: Key): Key = MODERN_ALIASES[key] ?: key
        }
    }
