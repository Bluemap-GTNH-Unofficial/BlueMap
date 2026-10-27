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
import de.bluecolored.bluemap.core.world.BlockEntity
import de.bluecolored.bluemap.core.world.mca.MCAUtil
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType
import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity
import de.bluecolored.bluemap.core.world.mca.blockentity.SkullBlockEntity
import de.bluecolored.bluenbt.BlueNBT
import de.bluecolored.bluenbt.NBTDeserializer
import de.bluecolored.bluenbt.NBTName
import de.bluecolored.bluenbt.NBTReader
import de.bluecolored.bluenbt.TagType
import de.bluecolored.bluenbt.TypeDeserializer
import de.bluecolored.bluenbt.TypeResolver
import de.bluecolored.bluenbt.TypeToken

/**
 * 1.7 block entities: legacy ids (`Sign`, `Skull`, ...) are read as their modern keys (`minecraft:sign`,
 * `minecraft:skull`, Mojang's 1.11 BlockEntityId fix), so BlueMap's [BlockEntityType.REGISTRY] types apply
 * (signs keep their 1.7 `Text1`..`Text4` plain strings, read by BlueMap's legacy sign format).
 * Flower pots, skulls and note blocks keep the 1.7 data that the flattening fixers need.
 * Modded ids pass through unchanged (`Key.parse`).
 */
object LegacyBlockEntities {
    @JvmField
    val FLOWER_POT: Key = Key.minecraft("flower_pot")

    @JvmField
    val SKULL: Key = Key.minecraft("skull")

    @JvmField
    val NOTE_BLOCK: Key = Key.minecraft("noteblock")

    /** Vanilla 1.7 block entity ids -> modern ids (Mojang's BlockEntityId data fix, 1.11). */
    private val MODERN_IDS: Map<String, Key> =
        mapOf(
            "Airportal" to "end_portal",
            "Beacon" to "beacon",
            "Cauldron" to "brewing_stand",
            "Chest" to "chest",
            "Comparator" to "comparator",
            "Control" to "command_block",
            "DLDetector" to "daylight_detector",
            "Dropper" to "dropper",
            "EnchantTable" to "enchanting_table",
            "EnderChest" to "ender_chest",
            "FlowerPot" to "flower_pot",
            "Furnace" to "furnace",
            "Hopper" to "hopper",
            "MobSpawner" to "mob_spawner",
            "Music" to "noteblock",
            "Piston" to "piston",
            "RecordPlayer" to "jukebox",
            "Sign" to "sign",
            "Skull" to "skull",
            "Trap" to "dispenser",
        ).mapValues { Key.minecraft(it.value) }

    /** The modern key of a block entity id as stored in a 1.7 chunk. */
    @JvmStatic
    fun modernId(legacyId: String): Key = MODERN_IDS[legacyId] ?: Key.parse(legacyId, Key.MINECRAFT_NAMESPACE)

    /** A BlueNBT configured like BlueMap's [MCAUtil.addCommonNbtSettings] plus the legacy id mapping and types. */
    @JvmStatic
    fun blueNbt(): BlueNBT =
        MCAUtil.addCommonNbtSettings(BlueNBT()).also {
            it.register(TypeToken.of(Key::class.java), LegacyIdDeserializer)
            it.register(TypeToken.of(BlockEntity::class.java), LegacyBlockEntityTypeResolver)
        }

    private object LegacyIdDeserializer : TypeDeserializer<Key> {
        override fun read(reader: NBTReader): Key = modernId(reader.nextString())
    }

    private object LegacyBlockEntityTypeResolver : TypeResolver<BlockEntity, MCABlockEntity> {
        private val baseType = TypeToken.of(MCABlockEntity::class.java)
        private val legacyTypes: Map<Key, TypeToken<out BlockEntity>> =
            mapOf(
                FLOWER_POT to TypeToken.of(LegacyFlowerPotBlockEntity::class.java),
                SKULL to TypeToken.of(LegacySkullBlockEntity::class.java),
                NOTE_BLOCK to TypeToken.of(LegacyNoteBlockEntity::class.java),
            )

        override fun getBaseType(): TypeToken<MCABlockEntity> = baseType

        override fun resolve(base: MCABlockEntity): TypeToken<out BlockEntity> {
            legacyTypes[base.id]?.let { return it }
            val type = BlockEntityType.REGISTRY.get(base.id) ?: return baseType
            return TypeToken.of(type.blockEntityClass)
        }

        override fun getPossibleTypes(): Iterable<TypeToken<out BlockEntity>> =
            listOf(baseType) + legacyTypes.values + BlockEntityType.REGISTRY.values().map { TypeToken.of(it.blockEntityClass) }

        override fun onException(
            parseException: java.io.IOException,
            base: MCABlockEntity,
        ): BlockEntity = base
    }
}

/** 1.7 `FlowerPot`: `Item` is the numeric (FML) item id (newer saves: the item name), `Data` its damage value. */
class LegacyFlowerPotBlockEntity : MCABlockEntity() {
    @field:NBTName("Item")
    @field:NBTDeserializer(NumberOrStringDeserializer::class)
    var item: Any? = null
        private set

    @field:NBTName("Data")
    var data: Int = 0
        private set
}

/** 1.7 `Skull`: `SkullType` 0..5 (skeleton, wither, zombie, player, creeper, dragon) and the floor rotation `Rot`. */
class LegacySkullBlockEntity : SkullBlockEntity() {
    @field:NBTName("SkullType")
    var skullType: Int = 0
        private set

    @field:NBTName("Rot")
    var rotation: Int = 0
        private set
}

/** 1.7 `Music` (note block): the `note` 0..24. */
class LegacyNoteBlockEntity : MCABlockEntity() {
    @field:NBTName("note")
    var note: Int = 0
        private set
}

/** Reads a numeric (as [Int]) or string NBT value: the flower pot `Item` is an id in 1.7 and a name in later saves. */
class NumberOrStringDeserializer : TypeDeserializer<Any?> {
    override fun read(reader: NBTReader): Any? =
        when (reader.peek()) {
            TagType.BYTE -> {
                reader.nextByte().toInt()
            }

            TagType.SHORT -> {
                reader.nextShort().toInt()
            }

            TagType.INT -> {
                reader.nextInt()
            }

            TagType.LONG -> {
                reader.nextLong().toInt()
            }

            TagType.STRING -> {
                reader.nextString()
            }

            else -> {
                reader.skip()
                null
            }
        }
}
