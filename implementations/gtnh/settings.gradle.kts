pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle / GTNHGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

// GTNHGradle puts Kotlin Gradle plugin 2.2.21 on the settings classpath; its highest JvmTarget is 24.
// Kotlin 2.3 can emit Java 25 class files, matching `enableModernJavaSyntax = modern` (Java toolchain 25).
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.33")
}

rootProject.name = "bluemap-gtnh"

// BlueMap itself (core, common, the 1.7.10 world loader and the GTNH renderers) comes from the enclosing BlueMap build.
val blueMapRoot = file("../..")
includeBuild(blueMapRoot) {
    dependencySubstitution {
        substitute(module("de.bluecolored:bluemap-common")).using(project(":common"))
        substitute(module("de.bluecolored:bluemap-legacy")).using(project(":legacy"))
        if (blueMapRoot.resolve("gtnh-render/build.gradle.kts").isFile) {
            substitute(module("de.bluecolored:bluemap-gtnh-render")).using(project(":gtnh-render"))
        }
    }
}
