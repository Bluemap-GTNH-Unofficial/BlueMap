# BlueMap for GT New Horizons (server mod)

Forge 1.7.10 server mod (modid `bluemap`, Kotlin) embedding BlueMap for GT New Horizons 2.9.0-RC-2. It runs only on
Java 25 through lwjgl3ify (the pack's Java 17-26 server); clients do not need it. Requires Forgelin (in the pack).

## Build

GTNHGradle, `enableModernJavaSyntax = modern` (Java 25 bytecode), Kotlin Gradle plugin forced to 2.3.21 in
`settings.gradle.kts`, Kotlin `apiVersion` 2.1 (runtime stdlib is Forgelin's 2.1.10). BlueMap `:common`, `:legacy` and
(if present) `:gtnh-render` come from the enclosing BlueMap build through a composite build (`includeBuild("../..")`).

```bash
cd BlueMap/implementations/gtnh
mise exec java@temurin-25.0.5+8.0.LTS -- ./gradlew \
  -Porg.gradle.java.installations.paths=$(mise where java@zulu-8.98.0.107)/Contents/Home,$(mise where java@temurin-25.0.5+8.0.LTS)/Contents/Home \
  build
```

Install `build/libs/bluemap-<version>.jar` (reobfuscated). The webapp must have been built in the BlueMap build
(`:common:buildWebapp`, run automatically unless excluded).

Version: `<bluemapUpstreamVersion>-gtnh.<gtnhRevision>` from BlueMap's root `gradle.properties` (e.g. `5.29-gtnh.1`),
shared by the mod jar, `mcmod.info` and BlueMap itself (`/bluemap version`, webapp). `bluemapUpstreamVersion` is the
latest upstream release tag reachable from the fork point; bump `gtnhRevision` for each fork release.

Packaging: BlueMap and its libraries are shaded (not minimized); the upstream Forge relocations plus gson go to
`de.bluecolored.shadow.*`; `module-info` and `META-INF/versions` are stripped; Kotlin is not shaded. The `@Mod` object
excludes the library packages from the Forge class transformers before any of them load.

## Behaviour

- Config in `config/bluemap/`, data in `bluemap/`. On every start, a map config is generated for each dimension with at
  least one region file that has none yet, with `loader: "gtnh:anvil_1_7"`; non-vanilla dimensions get
  `dimension-type: "gtnh:dim_<id>_type"`. Existing configs are never modified. Dimensions that got (or had) a config are
  recorded in `config/bluemap/gtnh-known-dimensions.conf` and are never offered again, so deleted configs stay deleted.
- `config/bluemap/packs/gtnh-runtime/` is regenerated from the running server: dimension types of loaded non-vanilla
  dimensions (`WorldProvider` sky, ceiling, light) and all modded biomes (`BiomeGenBase` temperature, rainfall, water
  colour). Dimensions loaded later update their file for the next `/bluemap reload`.
- `/bluemap` drives BlueMap's command tree directly; every subcommand needs op (permission level 1). Over RCON the reply
  contains the command output: the command waits up to 60 s on the RCON thread, but at most 1 s when Hodgepodge's
  `fixRconThreading` (GTNH default) runs it on the server thread, running BlueMap's server-thread work (such as saving)
  meanwhile. Commands still running after that (e.g. `reload`) reply "Still running ..." and finish in the background.
- `/bluemap update` saves the dimension on the server thread and waits for the chunk IO thread instead of flushing it
  from a second thread (Hodgepodge PR #1018). Live updates come from BlueMap's region-file watcher after saves.
