# BlueMap (GTNH port fork)

> [!WARNING]
> **Unofficial fork. Use at your own risk.**
>
> - This is **not** affiliated with, endorsed by or supported by [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap)
>   or the [GT New Horizons](https://github.com/GTNewHorizons) team. Do not report problems with this fork to them.
> - It is **vibe coded**: most of the port was written by an AI coding agent under human direction. It is tested but
>   has not been audited.
> - There is **no warranty and no quality guarantee**. Back up your world before installing it.

This is a fork of [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap) 5.29 that ports it to **Minecraft 1.7.10**
and the **GT New Horizons 2.9.0-RC-2** modpack. It is the main code repository of
[Bluemap-GTNH-Unofficial/BlueMap-GTNH](https://github.com/Bluemap-GTNH-Unofficial/BlueMap-GTNH). **Go there for
installation instructions, screenshots, the current status and releases.**

![Formed community addon multiblocks rendered by the server mod](https://raw.githubusercontent.com/Bluemap-GTNH-Unofficial/BlueMap-GTNH/main/docs/images/addons-multiblocks.jpg)

## What this fork adds

| Module | What it does |
|---|---|
| `legacy/` | World loader `gtnh:anvil_1_7` for 1.7.10 Anvil worlds, including EndlessIDs / ChunkAPI extended block IDs and 16-bit metadata, with block names from the FML registry in `level.dat`. Flattens vanilla 1.7.10 blocks to 1.13 block states, including neighbour-dependent shapes. |
| `gtnh-render/` | Renderers and resource-pack extensions for modded blocks: models captured by the client exporter, GregTech machines, pipes, cables, ores and frames, Chisel connected textures, Iron Chests, BiblioCraft, Applied Energistics 2, EnderIO and community addons. |
| `implementations/gtnh/` | The Forge 1.7.10 server mod `bluemap-<version>-gtnh.<n>.jar` (Kotlin, Java 25 bytecode, runs on the pack's Java 17-26 server through lwjgl3ify). It shades BlueMap core and common. See [its README](implementations/gtnh/README.md). |
| `testing/fixtures/` | Shared test vectors used by this repository and the sibling tool repositories. |

Changes to upstream code are kept small: a configurable world loader per map, a few hooks in the Anvil region and
data-pack code, and build changes for Kotlin modules. Upstream sources and headers are otherwise unchanged.

The client exporter, the test harness, the orchestrator, the e2e suite and the Modernity tool live in their own
repositories, checked out next to this one by the umbrella repository.

## Build

The fork builds and tests on its own (clone with `--recursive` for upstream's `api` submodule). The sibling tool
repositories depend on it, not the other way round; a few optional tests and scripts use the workspace when the
umbrella is checked out. Java comes from [mise](https://mise.jdx.dev/); `mise.toml` pins Temurin 25.

```bash
# BlueMap modules, including legacy and gtnh-render
mise exec -- ./gradlew :legacy:test :gtnh-render:test

# Server mod (separate GTNHGradle build that includes this one); needs a Java 8 toolchain too
cd implementations/gtnh
mise exec java@temurin-25.0.5+8.0.LTS -- ./gradlew \
  -Porg.gradle.java.installations.paths=$(mise where java@zulu-8.98.0.107)/Contents/Home,$(mise where java@temurin-25.0.5+8.0.LTS)/Contents/Home \
  build
```

The server mod jar is `implementations/gtnh/build/libs/bluemap-5.29-gtnh.1.jar`. The upstream platforms (Fabric,
Forge, Paper and so on) still build as before but are not maintained or tested in this fork.

## Upstream

All credit for BlueMap goes to Blue (Lukas Rieger) and the
[BlueMap contributors](https://github.com/BlueMap-Minecraft/BlueMap/graphs/contributors). For BlueMap itself (the
supported platforms, the wiki, Discord and the original README) see
[BlueMap-Minecraft/BlueMap](https://github.com/BlueMap-Minecraft/BlueMap) and
[bluemap.bluecolored.de](https://bluemap.bluecolored.de/). Please do not ask the upstream project for help with this
fork.

## Licence

MIT, as upstream, except where [NOTICE.md](NOTICE.md) says otherwise: one function adapted from GT: Not Leisure (and
two beam angle lines) stays LGPL-3.0, code adapted from MyCTMLib keeps its MIT notice, and Kotlin re-implementations
of Minecraft behaviour are credited to Mojang. [LICENSE](LICENSE) keeps the upstream copyright and adds the copyright
of the GTNH port additions. The full list, including textures in screenshots, is the umbrella repository's
[NOTICE.md](https://github.com/Bluemap-GTNH-Unofficial/BlueMap-GTNH/blob/main/NOTICE.md).
