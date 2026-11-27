package com.z1pendant.update

import com.z1pendant.BuildInfo
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.epochSeconds
import com.z1pendant.platform.wallClockHms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Checks GitHub for a newer release of the app and, on Android, installs it.
 *
 * - When the app starts (if [enabled]), at most every [MIN_GAP_S]: GitHub's public "latest release"
 *   API, no sign-in, nothing about the user or the machine sent. Between checks the last answer is
 *   kept in the prefs, so a found update is still offered.
 * - A newer release than [current] → [available], and a one-time [prompt] (not for a version the
 *   user skipped).
 * - [install]: Android downloads the APK, checks it and opens the system installer; iOS opens the
 *   release page (Sideloadly installs the new .ipa). Not while [busy] says why not (a job running:
 *   installing closes the app).
 */
class UpdateController(
    private val prefs: Prefs,
    private val updater: Updater,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** Why installing has to wait right now, or null. */
    private val busy: () -> String? = { null },
    val current: String = BuildInfo.VERSION,
    private var url: String = Release.LATEST_URL,
) {
    /** Debug builds' test hook: check against a stand-in release JSON (e.g. on this LAN) instead of GitHub. */
    fun testCheck(jsonUrl: String) {
        url = jsonUrl
        prefs.edit { putString(KEY_SKIP, "") }
        check(auto = true)
    }

    /** Check for updates when the app starts (Settings). */
    val enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    /** The newest release, when it's newer than this app. */
    val available = MutableStateFlow<Release?>(null)
    /** One line for Settings and the update dialog: "Up to date", "Downloading…", an error… */
    val status = MutableStateFlow<String?>(null)
    /** Download progress 0..1 while downloading, else null. */
    val progress = MutableStateFlow<Float?>(null)
    val checking = MutableStateFlow(false)
    /** The update dialog. [Prompt.auto] = opened by the startup check (shown on the main screen only). */
    val prompt = MutableStateFlow<Prompt?>(null)

    data class Prompt(val release: Release, val auto: Boolean)

    val canInstall get() = updater.canInstall
    private var checkJob: Job? = null
    private var installJob: Job? = null

    fun setEnabled(v: Boolean) {
        enabled.value = v
        prefs.edit { putBoolean(KEY_ENABLED, v) }
    }

    /** At app start: ask GitHub (if it's been a while) or reuse the last answer. */
    fun startupCheck(now: Long = epochSeconds()) {
        if (!enabled.value || updater === Updater.None) return
        val last = prefs.getLong(KEY_LAST, 0)
        val cached = prefs.getString(KEY_JSON, null)?.let(Release::parse)
        if (cached != null && now - last in 0 until MIN_GAP_S) {
            found(cached, auto = true)
            return
        }
        check(auto = true)
    }

    /** Settings › Check now. */
    fun checkNow() = check(auto = false)

    private fun check(auto: Boolean) {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            checking.value = true
            if (!auto) status.value = "Checking…"
            try {
                val text = updater.fetchText(url)
                val r = Release.parse(text) ?: error("GitHub's reply had no release in it")
                prefs.edit { putString(KEY_JSON, text); putLong(KEY_LAST, epochSeconds()) }
                found(r, auto)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A startup check fails quietly (no internet at the machine is normal); Settings says why.
                status.value = "Couldn't check for updates: ${e.message ?: e::class.simpleName}"
                log("· update check failed: ${e.message ?: e::class.simpleName}")
            } finally {
                checking.value = false
            }
        }
    }

    private fun found(r: Release, auto: Boolean) {
        if (Release.compareVersions(r.version, current) > 0) {
            available.value = r
            status.value = "Version ${r.version} is available (you have $current)"
            log("· update: ${r.version} available (this is $current)")
            if (auto && prefs.getString(KEY_SKIP, null) != r.tag && prompt.value == null) prompt.value = Prompt(r, auto = true)
        } else {
            available.value = null
            status.value = "Up to date ($current, checked ${wallClockHms().take(5)})"
            if (!auto) log("· update check: $current is the latest")
        }
    }

    /** Shows the dialog for the available update (Settings › What's new). */
    fun showPrompt() { available.value?.let { prompt.value = Prompt(it, auto = false) } }

    /** Dialog › Later: asks again next time the app starts. */
    fun dismiss() { if (installJob?.isActive != true) prompt.value = null }

    /** Dialog › Skip this version: no more dialogs for it (Settings still shows it). */
    fun skip() {
        prompt.value?.release?.let { r -> prefs.edit { putString(KEY_SKIP, r.tag) } }
        prompt.value = null
    }

    /** Update: Android downloads and installs; iOS opens the release page. */
    fun install() {
        val r = available.value ?: return
        if (!updater.canInstall) {
            updater.openPage(r.page)
            prompt.value = null
            return
        }
        if (installJob?.isActive == true) return
        busy()?.let { status.value = it; return }
        val apk = r.apk ?: run { status.value = "Release ${r.version} has no Android app (.apk) attached"; return }
        installJob = scope.launch {
            progress.value = 0f
            status.value = "Downloading ${r.version}…"
            try {
                updater.install(apk, onProgress = { progress.value = it }) { ev -> event(ev) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status.value = "Update failed: ${e.message ?: e::class.simpleName}"
                log("· update failed: ${e.message ?: e::class.simpleName}")
            } finally {
                progress.value = null
            }
        }
    }

    private fun event(ev: InstallEvent) {
        when (ev) {
            InstallEvent.NeedsPermission ->
                status.value = "Android needs your OK first: turn on \"Allow from this source\" for Z1 Pendant on the page " +
                    "that just opened, come back, and tap Update again."
            InstallEvent.Confirming -> {
                status.value = "Confirm the update in Android's dialog. The app closes while it installs; open it again after."
                prompt.value = null
            }
            InstallEvent.Cancelled -> status.value = "Update cancelled."
            is InstallEvent.Failed -> {
                status.value = "Update failed: ${ev.message}"
                log("· update failed: ${ev.message}")
            }
        }
    }

    companion object {
        /** Startup checks are at least this far apart (6 h); Check now always asks. */
        const val MIN_GAP_S = 6 * 3600L
        const val KEY_ENABLED = "updateCheck"
        const val KEY_LAST = "updateLastCheck"
        const val KEY_JSON = "updateLatestJson"
        const val KEY_SKIP = "updateSkipTag"
    }
}
