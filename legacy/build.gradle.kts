plugins {
    bluemap.kotlin
}

dependencies {
    api ( project( ":core" ) )
}

/**
 * Native BlueMap addon (drop into `<config>/packs/`) that registers the legacy world-loader in the CLI
 * and other platforms without a code change. Bundles the Kotlin stdlib, which BlueMap itself does not ship.
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

/** Diagnostic block-state statistics of a 1.7.10 world: `./gradlew :legacy:legacyStats --args="<world> [dimension] [top-n]"` */
tasks.register<JavaExec>("legacyStats") {
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.bluecolored.bluemap.gtnh.legacy.LegacyWorldStats"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
}

/** Ground-truth check against a server block dump: `./gradlew :legacy:verifyDump --args="<world> <dimension> <dump.ndjson> [--box x1 y1 z1 x2 y2 z2]"` */
tasks.register<JavaExec>("verifyDump") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.bluecolored.bluemap.gtnh.legacy.LegacyDumpVerifier"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
}
