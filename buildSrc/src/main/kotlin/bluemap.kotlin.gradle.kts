import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id ( "bluemap.java" )
    id ( "org.jetbrains.kotlin.jvm" )
}

var libs = project.extensions.getByType(VersionCatalogsExtension::class).named("libs")

kotlin {
    jvmToolchain(25)

    // The GTNH runtime provides the Kotlin stdlib through Forgelin (2.1.10),
    // so compile against that API level and depend on exactly that stdlib.
    coreLibrariesVersion = libs.findLibrary("kotlin-stdlib").get().get().version!!
    explicitApi = ExplicitApiMode.Disabled

    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        apiVersion = KotlinVersion.KOTLIN_2_1
        languageVersion = KotlinVersion.KOTLIN_2_1
    }
}

spotless {
    kotlin {
        target ("src/*/kotlin/**/*.kt")

        ktlint()
        licenseHeaderFile(rootProject.file("LICENSE_HEADER"), "(package |@file:)")
    }
}
