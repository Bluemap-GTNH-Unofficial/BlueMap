plugins {
    bluemap.kotlin
}

dependencies {
    api ( project( ":core" ) )

    // resource-key normalization vectors (contract C1) are checked against the legacy mapper's implementation
    testImplementation ( project( ":legacy" ) )
}

/**
 * Native BlueMap addon (drop into `<config>/packs/`) that registers the GTNH renderers and resource-pack extensions
 * in the CLI and other platforms without a code change. Bundles the Kotlin stdlib, which BlueMap itself does not ship.
 */
tasks.register<Jar>("addonJar") {
    group = "build"
    archiveClassifier = "addon"
    from(sourceSets.main.map { it.output })
    from("src/addon/resources")
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.filter { it.name.startsWith("kotlin-stdlib") }.map { zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/versions/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
