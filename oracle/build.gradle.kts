plugins {
    bluemap.kotlin
}

dependencies {
    implementation ( project( ":core" ) )
    implementation ( project( ":legacy" ) )
    implementation ( project( ":gtnh-render" ) )
}

/**
 * Per-block-state rendering oracle, BlueMap side: renders every state of a client index through the real hires
 * pipeline and rasterises it like the webapp.
 * `./gradlew :oracle:renderStates --args="--index <client/index.json> --out <bluemap-dir> --pack <file>... --mc-cache <dir>"`
 */
tasks.register<JavaExec>("renderStates") {
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.bluecolored.bluemap.gtnh.oracle.RenderStates"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    maxHeapSize = "8G"
}
