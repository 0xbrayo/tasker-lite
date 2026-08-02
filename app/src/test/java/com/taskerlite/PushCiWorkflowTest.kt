package com.taskerlite

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural checks for the every-push CI workflow that runs unit tests.
 */
class PushCiWorkflowTest {

    private fun workflowFile(): File {
        var dir = File(".").canonicalFile
        repeat(6) {
            val candidate = File(dir, ".github/workflows/ci.yml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        val fromTest = File("../../../../.github/workflows/ci.yml").canonicalFile
        if (fromTest.isFile) return fromTest
        error("Could not locate .github/workflows/ci.yml from ${File(".").canonicalPath}")
    }

    private val yaml: String by lazy { workflowFile().readText() }

    @Test
    fun workflow_triggersOnPush() {
        assertTrue("must declare on: push", yaml.contains("on:"))
        assertTrue("must trigger on push", yaml.contains("push:"))
        // Not tag-only release workflow
        assertTrue(
            "must run on branch pushes (not tags-only)",
            yaml.contains("branches:") || !yaml.contains("tags:"),
        )
    }

    @Test
    fun workflow_runsRealGradleUnitTestEntryPoint() {
        assertTrue(
            "must invoke ./gradlew :app:testDebugUnitTest",
            yaml.contains("./gradlew :app:testDebugUnitTest") ||
                yaml.contains("./gradlew :app:testDebugUnitTest --stacktrace"),
        )
    }

    @Test
    fun workflow_setsUpJdkForAndroidTests() {
        assertTrue(yaml.contains("setup-java") || yaml.contains("java-version"))
        assertTrue(yaml.contains("17") || yaml.contains("\"17\""))
    }
}
