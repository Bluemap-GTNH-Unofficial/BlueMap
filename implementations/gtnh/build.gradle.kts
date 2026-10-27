import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.gtnewhorizons.gtnhconvention")
    // Version comes from the settings classpath (Kotlin Gradle plugin forced to 2.3.21 in settings.gradle.kts).
    id("org.jetbrains.kotlin.jvm")
}

// Git-based versioning is disabled (this project lives in a subdirectory of the BlueMap repo).
val modVersion = "0.1.0"
extra["modVersion"] = modVersion
version = modVersion

// GTNHGradle forces kotlin.jvmToolchain(8); `enableModernJavaSyntax = modern` compiles Java with toolchain 25, so
// Kotlin uses toolchain 25 and emits Java 25 class files too. The runtime Kotlin stdlib is Forgelin 2.0.3-GTNH's
// 2.1.10, so the compiler must not emit calls to newer stdlib APIs.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

// Transitive dependencies of the substituted BlueMap projects resolve against this build's repositories.
repositories {
    maven("https://repo.bluecolored.de/releases") {
        content { includeGroupByRegex("de\\.bluecolored.*") }
    }
}

configurations.named("shadowImplementation") {
    // optional annotations, not needed at runtime
    exclude(group = "org.jspecify")

    // GTNHGradle creates this configuration without attributes; select the runtime jars of the BlueMap projects
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

/** Must match the transformer exclusions in [de.bluecolored.bluemap.gtnh.BlueMapMod]. */
val shadowPrefix = "de.bluecolored.shadow"

tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        // provided by Forgelin at runtime (stdlib 2.1.10, the version the BlueMap Kotlin modules are built against)
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }

    // Upstream BlueMap Forge relocations (implementations/forge/build.gradle.kts) ...
    relocate("net.kyori", "$shadowPrefix.adventure")
    relocate("io.airlift", "$shadowPrefix.airlift")
    relocate("com.github.benmanes.caffeine", "$shadowPrefix.caffeine")
    relocate("org.checkerframework", "$shadowPrefix.checkerframework")
    relocate("com.google.errorprone", "$shadowPrefix.errorprone")
    relocate("org.apache.commons", "$shadowPrefix.apache.commons")
    relocate("org.spongepowered.configurate", "$shadowPrefix.configurate")
    relocate("com.typesafe.config", "$shadowPrefix.typesafe.config")
    relocate("io.leangen.geantyref", "$shadowPrefix.geantyref")
    relocate("net.jpountz", "$shadowPrefix.jpountz")
    relocate("org.bstats", "$shadowPrefix.bstats")
    // ... plus gson: 1.7.10 ships gson 2.2.4 (lwjgl3ify replaces it with 2.13.2); BlueMap must use its own.
    relocate("com.google.gson", "$shadowPrefix.gson")

    exclude(
        // lwjgl3ify ignores these, they are noise in a 1.7.10 mod jar
        "module-info.class",
        "META-INF/versions/**",
        // not correctly relocated and not needed (as upstream Forge)
        "META-INF/services/net.kyori.adventure*",
        "META-INF/services/org.spongepowered.configurate*",
    )
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}
