package com.z1pendant

import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import com.z1pendant.update.InstallEvent
import com.z1pendant.update.Release
import com.z1pendant.update.ReleaseAsset
import com.z1pendant.update.UpdateController
import com.z1pendant.update.Updater
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The update check: GitHub's release JSON, version order, and the controller's decisions. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateTest {
    /** The shape of GitHub's `releases/latest` (v0.1.11, trimmed). */
    private fun releaseJson(tag: String = "v0.1.12", draft: Boolean = false, digest: Boolean = true) = """
        {"url":"https://api.github.com/repos/jimmyvegas29/makera-z1-pendant/releases/1","tag_name":"$tag",
         "name":"${tag.drop(1)}: soft limits from the machine (Carvera Air)","draft":$draft,"prerelease":false,
         "html_url":"https://github.com/jimmyvegas29/makera-z1-pendant/releases/tag/$tag",
         "body":"**Soft limits from the machine.** The app asks with `M211`.\r\n\r\n- one\r\n- [two](https://x.y)\r\n\r\n\r\n\r\nend",
         "assets":[
          {"name":"z1-pendant-${tag.drop(1)}.apk","size":3662719,"content_type":"application/vnd.android.package-archive",
           ${if (digest) "\"digest\":\"sha256:D4E93D9E578AD3F1A662CF4E59AEA2020A915131897C6E7DFEC974040DF3B0FF\"," else ""}
           "browser_download_url":"https://github.com/jimmyvegas29/makera-z1-pendant/releases/download/$tag/z1-pendant-${tag.drop(1)}.apk"},
          {"name":"z1-pendant-${tag.drop(1)}.ipa","size":14538332,"digest":null,
           "browser_download_url":"https://github.com/jimmyvegas29/makera-z1-pendant/releases/download/$tag/z1-pendant-${tag.drop(1)}.ipa"}
         ]}
    """.trimIndent()

    @Test fun parsesGitHubsRelease() {
        val r = Release.parse(releaseJson())!!
        assertEquals("v0.1.12", r.tag)
        assertEquals("0.1.12", r.version)
        assertEquals("https://github.com/jimmyvegas29/makera-z1-pendant/releases/tag/v0.1.12", r.page)
        assertEquals("z1-pendant-0.1.12.apk", r.apk!!.name)
        assertEquals(3662719L, r.apk!!.size)
        assertEquals("d4e93d9e578ad3f1a662cf4e59aea2020a915131897c6e7dfec974040df3b0ff", r.apk!!.sha256)
        assertEquals("z1-pendant-0.1.12.ipa", r.ipa!!.name)
        assertNull(r.ipa!!.sha256)
    }

    @Test fun noDigestMeansNoHash() = assertNull(Release.parse(releaseJson(digest = false))!!.apk!!.sha256)

    @Test fun draftsAndJunkAreNotReleases() {
        assertNull(Release.parse(releaseJson(draft = true)))
        assertNull(Release.parse("""{"message":"Not Found"}"""))
        assertNull(Release.parse("<html>rate limited</html>"))
        assertNull(Release.parse(""))
    }

    @Test fun versionOrder() {
        assertTrue(Release.compareVersions("0.1.10", "0.1.9") > 0)
        assertTrue(Release.compareVersions("0.1.12", "0.1.11") > 0)
        assertTrue(Release.compareVersions("0.2", "0.1.99") > 0)
        assertTrue(Release.compareVersions("1.0.0", "0.9.9") > 0)
        assertEquals(0, Release.compareVersions("v0.1.11", "0.1.11"))
        assertEquals(0, Release.compareVersions("0.1", "0.1.0"))
        assertTrue(Release.compareVersions("0.1.11", "0.1.12") < 0)
        assertTrue(Release.compareVersions("0.2.0-test", "0.2.0") < 0)
        assertTrue(Release.compareVersions("0.2.0-test", "0.1.11") > 0)
    }

    @Test fun plainNotes() {
        assertEquals("Soft limits from the machine. The app asks with M211.\n\n• one\n• two\n\nend",
            Release.plainNotes(Release.parse(releaseJson())!!.notes))
    }

    @Test fun buildInfoIsTheAppVersion() = assertTrue(BuildInfo.VERSION.matches(Regex("""\d+\.\d+\.\d+.*""")))

    // ---- the controller ----

    private class MapPrefs : Prefs {
        val m = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = m[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = m[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = m[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = m[key] as? Float ?: default
        override fun getString(key: String, default: String?) = m[key] as? String ?: default
        override fun edit(block: PrefsEditor.() -> Unit) {
            object : PrefsEditor {
                override fun putBoolean(key: String, value: Boolean) { m[key] = value }
                override fun putInt(key: String, value: Int) { m[key] = value }
                override fun putLong(key: String, value: Long) { m[key] = value }
                override fun putFloat(key: String, value: Float) { m[key] = value }
                override fun putString(key: String, value: String) { m[key] = value }
            }.block()
        }
    }

    private class FakeUpdater(var reply: () -> String, override val canInstall: Boolean = true) : Updater {
        var fetches = 0
        val opened = mutableListOf<String>()
        val installed = mutableListOf<ReleaseAsset>()
        var events: List<InstallEvent> = emptyList()
        override suspend fun fetchText(url: String): String { fetches++; return reply() }
        override fun openPage(url: String) { opened += url }
        override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit, onEvent: (InstallEvent) -> Unit) {
            installed += asset
            onProgress(0.5f)
            events.forEach(onEvent)
        }
    }

    private fun TestScope.controller(up: Updater, prefs: Prefs = MapPrefs(), current: String = "0.1.11",
                                     busy: () -> String? = { null }, log: MutableList<String> = mutableListOf()) =
        UpdateController(prefs, up, this, log = { log += it }, busy = busy, current = current)

    @Test fun startupFindsANewerRelease() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val up = FakeUpdater({ releaseJson("v0.1.12") })
        val c = controller(up)
        c.startupCheck(now = 1_000_000)
        advanceUntilIdle()
        assertEquals("0.1.12", c.available.value?.version)
        assertTrue(c.prompt.value!!.auto)
        assertEquals(1, up.fetches)
    }

    @Test fun sameVersionIsUpToDate() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val c = controller(FakeUpdater({ releaseJson("v0.1.11") }))
        c.startupCheck(now = 1_000_000)
        advanceUntilIdle()
        assertNull(c.available.value)
        assertNull(c.prompt.value)
        assertTrue(c.status.value!!.startsWith("Up to date"))
    }

    @Test fun switchedOffMeansNoRequest() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val up = FakeUpdater({ releaseJson() })
        val prefs = MapPrefs()
        controller(up, prefs).setEnabled(false)
        controller(up, prefs).startupCheck(now = 1_000_000)
        advanceUntilIdle()
        assertEquals(0, up.fetches)
    }

    @Test fun recentAnswerIsReusedNotFetched() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val up = FakeUpdater({ releaseJson("v0.1.12") })
        val prefs = MapPrefs()
        val now = com.z1pendant.platform.epochSeconds()
        controller(up, prefs).startupCheck(now); advanceUntilIdle()
        assertEquals(1, up.fetches)
        val c2 = controller(up, prefs)
        c2.startupCheck(now + 3600)                      // an hour later: the cached answer
        advanceUntilIdle()
        assertEquals(1, up.fetches)
        assertEquals("0.1.12", c2.available.value?.version)
        controller(up, prefs).startupCheck(now + UpdateController.MIN_GAP_S + 1); advanceUntilIdle()
        assertEquals(2, up.fetches)                      // later than that: asks again
    }

    @Test fun skippedVersionGetsNoDialogButStaysAvailable() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val prefs = MapPrefs()
        val c = controller(FakeUpdater({ releaseJson("v0.1.12") }), prefs)
        c.startupCheck(1_000_000); advanceUntilIdle()
        c.skip()
        val c2 = controller(FakeUpdater({ releaseJson("v0.1.12") }), prefs)
        c2.startupCheck(1_000_000 + UpdateController.MIN_GAP_S + 1); advanceUntilIdle()
        assertNull(c2.prompt.value)
        assertNotNull(c2.available.value)
        // A newer one than the skipped version asks again.
        val c3 = controller(FakeUpdater({ releaseJson("v0.1.13") }), prefs)
        c3.startupCheck(1_000_000 + 2 * UpdateController.MIN_GAP_S + 2); advanceUntilIdle()
        assertNotNull(c3.prompt.value)
    }

    @Test fun failedCheckIsQuietAndSaysWhy() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val log = mutableListOf<String>()
        val c = controller(FakeUpdater({ throw java.io.IOException("Unable to resolve host") }), log = log)
        c.startupCheck(1_000_000); advanceUntilIdle()
        assertNull(c.prompt.value)
        assertTrue(c.status.value!!.contains("Unable to resolve host"))
        assertTrue(log.single().contains("update check failed"))
    }

    @Test fun installHandsTheApkOver() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val up = FakeUpdater({ releaseJson("v0.1.12") }).apply { events = listOf(InstallEvent.Confirming) }
        val c = controller(up)
        c.checkNow(); advanceUntilIdle()
        c.install(); advanceUntilIdle()
        assertEquals("z1-pendant-0.1.12.apk", up.installed.single().name)
        assertNull(c.prompt.value)
        assertNull(c.progress.value)
        assertTrue(c.status.value!!.startsWith("Confirm the update"))
    }

    @Test fun installWaitsForTheJob() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val up = FakeUpdater({ releaseJson("v0.1.12") })
        val c = controller(up, busy = { "A job is running." })
        c.checkNow(); advanceUntilIdle()
        c.install(); advanceUntilIdle()
        assertTrue(up.installed.isEmpty())
        assertEquals("A job is running.", c.status.value)
    }

    @Test fun iPhoneOpensTheReleasePage() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val up = FakeUpdater({ releaseJson("v0.1.12") }, canInstall = false)
        val c = controller(up)
        c.startupCheck(1_000_000); advanceUntilIdle()
        c.install(); advanceUntilIdle()
        assertEquals(listOf("https://github.com/jimmyvegas29/makera-z1-pendant/releases/tag/v0.1.12"), up.opened)
        assertTrue(up.installed.isEmpty())
        assertNull(c.prompt.value)
    }

    @Test fun permissionAndCancelAreReported() = kotlinx.coroutines.test.runTest(StandardTestDispatcher()) {
        val up = FakeUpdater({ releaseJson("v0.1.12") }).apply { events = listOf(InstallEvent.NeedsPermission) }
        val c = controller(up)
        c.checkNow(); advanceUntilIdle()
        c.install(); advanceUntilIdle()
        assertTrue(c.status.value!!.contains("Allow from this source"))
        up.events = listOf(InstallEvent.Confirming, InstallEvent.Cancelled)
        c.install(); advanceUntilIdle()
        assertEquals("Update cancelled.", c.status.value)
        assertFalse(c.progress.value != null)
    }

    /** The real GitHub API (`LIVE_GITHUB=1 gradlew …`): the latest release parses and has a hashed APK. */
    @Test fun liveLatestRelease() {
        assumeTrue(System.getenv("LIVE_GITHUB") == "1")
        val c = java.net.URL(Release.LATEST_URL).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("User-Agent", "Z1-Pendant/test")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val r = Release.parse(c.inputStream.use { it.readBytes().decodeToString() })!!
        println("latest: ${r.tag} apk=${r.apk} ipa=${r.ipa}")
        assertNotNull(r.apk?.sha256)
        assertTrue(Release.compareVersions(r.version, "0.1.10") > 0)
    }
}
