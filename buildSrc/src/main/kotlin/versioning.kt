import org.gradle.api.Project
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.Properties

/**
 * Version of the GTNH fork: `<bluemapUpstreamVersion>-gtnh.<gtnhRevision>` from the root `gradle.properties`
 * (read from the file, so it is the same when this build is included by another one).
 */
fun Project.gtnhVersion(): String {
    val properties = Properties()
    rootDir.resolve("gradle.properties").reader().use(properties::load)
    val upstream = properties.getProperty("bluemapUpstreamVersion") ?: error("bluemapUpstreamVersion missing in gradle.properties")
    val revision = properties.getProperty("gtnhRevision") ?: error("gtnhRevision missing in gradle.properties")
    return "$upstream-gtnh.$revision"
}

fun Project.gitHash(): String {
    return runCommand("git rev-parse --verify HEAD", "-")
}

fun Project.gitClean(): Boolean {
    if (runCommand("git update-index --refresh", "NOT-CLEAN").equals("NOT-CLEAN")) return false;
    return runCommand("git diff-index HEAD --", "NOT-CLEAN").isEmpty();
}

fun Project.gitIsRelease(): Boolean {
    val lastTag = if (runCommand("git tag", "").isEmpty()) "" else runCommand("git describe --tags --abbrev=0", "")
    val commits = runCommand("git rev-list --count $lastTag..HEAD", "0")
    return commits == "0" && gitClean()
}

fun Project.releaseNotes(): String {
    val file = rootProject.projectDir.resolve("release.md")
    if (!file.exists()) return ""

    return file
        .readText()
        .replace("{version}", project.version.toString())
}

private fun Project.runCommand(cmd: String, fallback: String? = null): String {
    ProcessBuilder(cmd.split("\\s(?=(?:[^'\"`]*(['\"`])[^'\"`]*\\1)*[^'\"`]*$)".toRegex()))
        .directory(projectDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
        .apply {
            if (!waitFor(10, TimeUnit.SECONDS))
                throw TimeoutException("Failed to execute command: '$cmd'")
        }
        .run {
            val exitCode = waitFor()
            if (exitCode == 0) return inputStream.bufferedReader().readText().trim()

            val error = errorStream.bufferedReader().readText().trim()
            logger.warn("Failed to execute command '$cmd': $error")
            if (fallback != null) return fallback
            throw IOException(error)
        }
}
