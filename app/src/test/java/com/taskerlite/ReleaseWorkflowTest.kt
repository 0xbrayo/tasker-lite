package com.taskerlite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural checks that the shipped release workflow matches the
 * signed-tag → APK → GitHub Release contract (without calling GitHub).
 */
class ReleaseWorkflowTest {

    private fun workflowFile(): File {
        // test cwd is usually the app/ module; walk up to repo root
        var dir = File(".").canonicalFile
        repeat(6) {
            val candidate = File(dir, ".github/workflows/release.yml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        // Fallback from app/src/test
        val fromTest = File("../../../../.github/workflows/release.yml").canonicalFile
        if (fromTest.isFile) return fromTest
        error("Could not locate .github/workflows/release.yml from ${File(".").canonicalPath}")
    }

    private val yaml: String by lazy { workflowFile().readText() }

    @Test
    fun workflow_triggersOnVersionTagPush() {
        assertTrue(
            "workflow must trigger on tag push (v*)",
            yaml.contains("tags:") && (yaml.contains("v*") || yaml.contains("'v*'") || yaml.contains("\"v*\"")),
        )
        assertTrue(yaml.contains("on:"))
        assertTrue(yaml.contains("push:"))
    }

    @Test
    fun workflow_verifiesSignedTagAndFailsUnsigned() {
        assertTrue(
            "must run git verify-tag (cryptographic check)",
            yaml.contains("git verify-tag"),
        )
        assertTrue(
            "must reject lightweight tags",
            yaml.contains("lightweight") || yaml.contains("annotated"),
        )
        // Fail-closed messaging present
        assertTrue(yaml.contains("not signed") || yaml.contains("Unsigned"))
    }

    @Test
    fun workflow_buildsReleaseApkWithGradle() {
        assertTrue(
            "must invoke the real Gradle release assemble entry point",
            yaml.contains("./gradlew :app:assembleRelease") ||
                yaml.contains("./gradlew :app:assembleRelease --stacktrace"),
        )
        assertTrue(yaml.contains("app/build/outputs/apk"))
    }

    @Test
    fun workflow_runsUnitTestsBeforeBuildingTheApk() {
        assertTrue(
            "release must be gated on the unit test suite",
            yaml.contains("testDebugUnitTest"),
        )
        assertTrue(
            "tests must run before assembleRelease",
            yaml.indexOf("testDebugUnitTest") < yaml.indexOf(":app:assembleRelease"),
        )
    }

    @Test
    fun workflow_stampsApkVersionFromTag() {
        assertTrue(
            "APK version must come from the tag, not a hardcoded versionCode",
            yaml.contains("RELEASE_VERSION_NAME"),
        )
    }

    @Test
    fun workflow_publishesGithubReleaseWithApkAsset() {
        assertTrue(
            "must use softprops/action-gh-release (or equivalent release upload)",
            yaml.contains("softprops/action-gh-release") || yaml.contains("gh release create"),
        )
        assertTrue(
            "must attach an .apk asset",
            yaml.contains(".apk"),
        )
        assertTrue(
            "needs contents: write for releases",
            yaml.contains("contents: write") || yaml.contains("contents:write"),
        )
    }

    @Test
    fun workflow_isNotOnlyBranchCi() {
        // Release job should not be the sole PR/push-to-main gate; tag-focused is fine.
        // Ensure we are not missing the tag trigger by only listening to branches.
        assertFalse(
            "must not restrict triggers to branches only",
            yaml.contains("branches:") && !yaml.contains("tags:"),
        )
    }
}
