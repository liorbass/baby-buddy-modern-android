package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AppText
import com.example.update.AppUpdater
import com.example.update.GitHubAsset
import com.example.update.GitHubRelease
import com.example.update.UpdateState
import com.example.update.toAppRelease
import com.example.update.versionCodeOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → About → Updates: reading GitHub's latest release and deciding whether it is an update. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateCheckTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        AppText.resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        AppUpdater.attach(ApplicationProvider.getApplicationContext())
        server.start()
        AppUpdater.latestReleaseUrl = server.url("/repos/x/y/releases/latest").toString()
    }

    @After
    fun tearDown() = server.close()

    private fun release(tag: String) = """
        {"tag_name": "$tag", "html_url": "https://github.com/x/y/releases/tag/$tag", "draft": false, "prerelease": false,
         "body": "What changed", "assets": [
           {"name": "notes.txt", "browser_download_url": "https://example.invalid/notes.txt", "size": 5},
           {"name": "baby-buddy-1.14.0.apk", "browser_download_url": "https://example.invalid/app.apk", "size": 1234,
            "digest": "sha256:ABCDEF"}
         ]}
    """.trimIndent()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun tagsMapToVersionCodesLikeTheBuild() {
        assertEquals(11300, versionCodeOf("v1.13.0"))
        assertEquals(20001, versionCodeOf("2.0.1"))
        assertNull(versionCodeOf("v1.13"))
        assertNull(versionCodeOf("nightly"))
        assertNull(versionCodeOf("v1.100.0"))
    }

    @Test
    fun aReleaseIsAnUpdateOnlyWithAVersionTagAndAnApk() {
        val apk = GitHubAsset("baby-buddy.apk", "https://example.invalid/a.apk", 10, "sha256:AB12")
        val r = GitHubRelease("v1.14.0", "notes", "https://example.invalid/page", assets = listOf(GitHubAsset("x.zip", "https://example.invalid/x.zip"), apk)).toAppRelease()!!
        assertEquals("1.14.0", r.versionName)
        assertEquals(11400, r.versionCode)
        assertEquals("https://example.invalid/a.apk", r.apkUrl)
        assertEquals("ab12", r.sha256)
        assertNull(GitHubRelease("v1.14.0", prerelease = true, assets = listOf(apk)).toAppRelease())
        assertNull(GitHubRelease("v1.14.0", draft = true, assets = listOf(apk)).toAppRelease())
        assertNull(GitHubRelease("v1.14.0").toAppRelease())
        assertNull(GitHubRelease("latest", assets = listOf(apk)).toAppRelease())
    }

    @Test
    fun aNewerReleaseIsOfferedAndTheSameOneIsNot() {
        server.enqueue(json(release("v1.14.0")))
        runBlocking { AppUpdater.check(currentVersionCode = 11300) }
        val available = AppUpdater.state.value as UpdateState.Available
        assertEquals(11400, available.release.versionCode)
        assertEquals("abcdef", available.release.sha256)
        val request = server.takeRequest()
        assertEquals("application/vnd.github+json", request.headers["Accept"])

        server.enqueue(json(release("v1.14.0")))
        runBlocking { AppUpdater.check(currentVersionCode = 11400) }
        assertEquals(UpdateState.UpToDate, AppUpdater.state.value)
    }

    @Test
    fun aServerErrorIsReported() {
        server.enqueue(json("{}", code = 503))
        runBlocking { AppUpdater.check(currentVersionCode = 11300) }
        val failed = AppUpdater.state.value as UpdateState.Failed
        assertTrue(failed.message, failed.message.contains("503"))
        assertNull(failed.release)
    }
}
