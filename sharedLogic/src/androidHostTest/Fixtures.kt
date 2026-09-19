package com.kfilesync.mobile.conformance

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Conformance runner (1.3) shared plumbing: reads the JSON fixtures that
 * kfilesync-core's `kfilesync-conformance/rust-runner` also runs, from the
 * sibling KFileSyncCore checkout (path supplied by
 * `sharedLogic/build.gradle.kts` via the `conformance.fixtures.dir`
 * system property - see that file for why this isn't copied into this repo).
 */
val conformanceJson = Json { ignoreUnknownKeys = true }

fun fixtureFile(relativePath: String): File {
    val dir = System.getProperty("conformance.fixtures.dir")
        ?: error(
            "conformance.fixtures.dir system property not set - " +
                "run via `./gradlew :sharedLogic:testAndroidHostTest`, not directly from the IDE " +
                "without the Gradle task's systemProperty wiring."
        )
    val file = File(dir, relativePath)
    check(file.exists()) {
        "fixture not found: ${file.absolutePath} - has ../KFileSyncCore/kfilesync-conformance/fixtures/$relativePath moved?"
    }
    return file
}

fun fixtureText(relativePath: String): String = fixtureFile(relativePath).readText()

inline fun <reified T> fixture(relativePath: String): T =
    conformanceJson.decodeFromString(fixtureText(relativePath))