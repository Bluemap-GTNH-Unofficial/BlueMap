# Third-party notices (BlueMap GTNH fork)

This fork of [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap) is unofficial and not affiliated with the BlueMap
project, the GT New Horizons team, Mojang or Microsoft, or the authors of any mod named here. This file covers the
code in this repository. The complete list for the whole project is the umbrella repository's
[NOTICE.md](https://github.com/Bluemap-GTNH-Unofficial/BlueMap-GTNH/blob/main/NOTICE.md).

## Licence summary

Everything here is MIT ([LICENSE](LICENSE)), keeping BlueMap's upstream copyright (Copyright (c) Blue (Lukas Rieger)
and contributors), with these exceptions and credits:

- **LGPL-3.0:** `MultiEssentiaJar.layers()` and the beam pitch/yaw lines of `BeamFormer.beam()` in
  `gtnh-render/.../mods/addons/GtnlTesrs.kt` are adapted from GT: Not Leisure and remain available under the LGPL-3.0.
- **MIT, other copyright holder:** `MyCtm.indices()` is adapted from MyCTMLib; its notice is reproduced below.
- **Mojang:** a few functions re-implement Minecraft behaviour in Kotlin and are credited below.

## Code adapted from other projects

`gtnh-render/...` is `gtnh-render/src/main/kotlin/de/bluecolored/bluemap/gtnh/render/`, `legacy/...` is
`legacy/src/main/kotlin/de/bluecolored/bluemap/gtnh/legacy/`.

| Source | Copyright / author | Licence | What we used | Where |
|---|---|---|---|---|
| [GT: Not Leisure](https://github.com/ABKQPO/GT-Not-Leisure) (GTNL) 0.2.7-rc2 | HFstudio, ABKQPO and contributors | LGPL-3.0 (its `LICENSE.txt` at tag 0.2.7-rc2) | Code adapted: the liquid layer split of `MultiEssentiaJarRenderer` and the beam pitch/yaw formulas of `BeamFormerRenderHelper`. | `gtnh-render/.../mods/addons/GtnlTesrs.kt` |
| [MyCTMLib](https://github.com/ABKQPO/MyCTMLib) | Copyright (c) 2024 初夏同学 | MIT | Code adapted: `MyCtm.indices()` from `Textures.buildConnect`. | `gtnh-render/.../mods/ctm/MyCtm.kt` |
| Minecraft 1.7.10 and 1.13 | Minecraft (c) Mojang AB / Microsoft | All rights reserved | Behaviour re-implemented in Kotlin, closely following the vanilla logic: the `ModelBox` box builder, the 1.13 stairs shape and redstone wire side rules, 1.13 block face shapes. Credited to Mojang. | `gtnh-render/.../mods/EntityModel.kt` `box()`; `legacy/.../VanillaFixers.kt` `stairsShape()`, `redstoneWire()`; `legacy/.../VanillaBlockShapes.kt` |
| [Applied Energistics 2 Unofficial](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial) API (`AEColor`) | Copyright (c) 2013 AlgorithmX2 | MIT | Data: the 17 cable colours. | `gtnh-render/.../mods/ae2/Ae2CableBusRenderer.kt`, `GtnlTesrs.kt` |
| [GTNewHorizons/ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) | Copyright (c) 2021 Johann Bernhardt | MIT | Template: build layout of `implementations/gtnh`. Its notice is in [LICENSE](LICENSE). | `implementations/gtnh` |
| [Gradle wrapper](https://github.com/gradle/gradle) | Copyright the original authors (Gradle) | Apache-2.0 | Unchanged wrapper scripts and jar. | `gradlew`, `gradlew.bat`, `gradle/wrapper/` |
| [PrismarineJS/minecraft-data](https://github.com/PrismarineJS/minecraft-data) `legacy.json` | PrismarineJS contributors | MIT | Data: input of the vanilla flattening table. | `legacy/src/main/resources/.../flattening_1_7_10.tsv`, `legacy/src/test/python/gen_flattening_table.py` |

### GT: Not Leisure (LGPL-3.0 parts)

`MultiEssentiaJar.layers()` in `GtnlTesrs.kt` is adapted from GTNL's `MultiEssentiaJarRenderer`, and the two
pitch/yaw lines of `BeamFormer.beam()` from GTNL's `BeamFormerRenderHelper` (GTNL 0.2.7-rc2, licensed LGPL-3.0 at that
tag). These parts remain available under the
[GNU Lesser General Public License v3.0](https://www.gnu.org/licenses/lgpl-3.0.html): you may use, change and
redistribute them under its terms. Their source is the file itself. The rest of `GtnlTesrs.kt` is MIT.

### MyCTMLib licence

```text
MIT License

Copyright (c) 2024 初夏同学

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Mods used as references

The renderers in `gtnh-render` draw these mods' blocks from the mods' own textures and models, read at run time from
the user's installed jars. We studied each mod and reused the facts its blocks need (texture-sheet layouts, model and
texture names, NBT keys, metadata bit layouts, box coordinates, placement numbers). Apart from the code listed above,
the implementations are our own, and no textures, models or language files of these mods are committed.

| Mod | Licence | What we used | Where |
|---|---|---|---|
| [Chisel](https://github.com/GTNewHorizons/Chisel) (GTNH; tterrag, Drullkus, minecreatr) | GPL-2.0 | Texture layouts followed; implementation written independently from a description of the behaviour. | `gtnh-render/.../mods/chisel/` |
| [Applied Energistics 2 Unofficial](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial) (AlgorithmX2 and GTNH) | LGPL-3.0 | Geometry facts: cable-bus part boxes, cable connections. | `gtnh-render/.../mods/ae2/` |
| [BiblioCraft](http://www.bibliocraftmod.com) (Joseph "Nuchaz" Sinclair, closed source) | All rights reserved | Placement data for BiblioCraft's own OBJ models, NBT keys, tile-entity ids. | `gtnh-render/.../mods/bibliocraft/` |
| [Iron Chests](https://github.com/GTNewHorizons/ironchest) (cpw; GTNH fork Alkalus) | GPL-3.0 | Behaviour reference: chest placement, chest types. | `gtnh-render/.../mods/ironchest/` |
| [EnderIO](https://github.com/GTNewHorizons/EnderIO) (CrazyPants, tterrag) | Unlicense; GTNH changes LGPL-3.0 | Geometry facts: conduit offsets and sizes. | `gtnh-render/.../mods/enderio/` |
| [GT5-Unofficial](https://github.com/GTNewHorizons/GT5-Unofficial) (GregoriusT, GTNH) | LGPL-3.0 | Behaviour reference: texture flipping, pipe and machine metadata, ores. | `gtnh-render/.../gregtech/`, `GtLaserBeacon.kt` |
| [Twist Space Technology](https://github.com/Nxer/Twist-Space-Technology-Mod) (Nxer) | GPL-3.0 | Behaviour reference: TESR placement and sizes. | `TstTesrs.kt`, `GtLaserBeacon.kt` |
| [Box++](https://github.com/luomolhx/BoxPlusPlus) (SilverMoon, luomolhx) | LGPL-3.0 | Behaviour reference: ring renderer. | `BoxTesrs.kt` |
| [123Technology](https://github.com/CallmeSHaobe/123Technology) (NewMaa, CallmeSHaobe) | GPL-3.0 | Behaviour reference: rocket, assembler, figure renderers. | `OthTesrs.kt` |
| [GTNH-Web-Map](https://github.com/GTNewHorizons/GTNH-Web-Map) (GTNH, Dynmap contributors) | Apache-2.0 | Interoperability reference: 1.7.10 chunk and biome NBT layout. | `legacy/.../LegacyChunkLoader.kt`, `Chunk_1_7.kt`, `LegacyBiomeMapper.kt` |
| [Hodgepodge](https://github.com/GTNewHorizons/Hodgepodge) (mitchej123, GTNH) | LGPL-3.0 | Behaviour reference: RCON threading, chunk saving. | `implementations/gtnh/.../CommandWait.kt`, `GtnhWorld.kt` |
| [StructureLib](https://github.com/GTNewHorizons/StructureLib) (glee8e, TecTech authors) | LGPL-3.0 | Data: `Rotation` and `Flip` ordinals. | `gtnh-render/.../gregtech/` |

Bare `*Tesrs.kt` and `GtLaserBeacon.kt` are in `gtnh-render/.../mods/addons/`. Supported addons rendered only from the
exported pack: [NH Utilities](https://github.com/Keriils/NH-Utilities) (GPL-3.0) and
[Programmable Hatches](https://github.com/reobf/Programmable-Hatches-Mod) (MIT).

## Libraries in the server mod jar

`implementations/gtnh` shades BlueMap's libraries under `de.bluecolored.shadow`, each under its own licence:
Caffeine 3.3.1, Configurate 4.2.0 (with embedded Typesafe Config), GeAnTyRef 1.3.16, Gson 2.8.9, Apache Commons
DBCP 2 2.14.0 / Pool 2 2.13.0 / Logging 1.3.5, aircompressor 2.0.3, lz4-java 1.10.1 and Error Prone annotations
2.50.0 (Apache-2.0); Adventure 4.26.1 with examination and option, flow-math 1.0.3, BlueNBT 3.5.1 and BlueCommands
1.3.4 (MIT). The web app bundles three.js, Vue, vue-i18n and hammer.js (MIT), hocon-parser (ISC) and the Quicksand
font (SIL OFL 1.1, `common/webapp/src/fonts/OFL.txt`). The mod is compiled against Minecraft 1.7.10, Forge and
Forgelin, which the pack provides and which are not redistributed.

## Minecraft and textures

Minecraft is a trademark of Mojang Synergies AB / Microsoft. BlueMap downloads Mojang's client jar only after the
operator sets `accept-download: true` (accepting the [Minecraft EULA](https://www.minecraft.net/eula)). Mod names
and textures belong to their authors; no mod textures are committed here.
