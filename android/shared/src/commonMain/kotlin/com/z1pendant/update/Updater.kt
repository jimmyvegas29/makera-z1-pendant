package com.z1pendant.update

/**
 * The phone's side of updating the app. Android ([canInstall]): downloads the release's APK, checks
 * it and hands it to the system installer. iOS can't install apps itself (a sideloaded app is signed
 * with each user's own Apple ID), so it only opens the release page.
 */
interface Updater {
    /** This phone can download and install the update itself. */
    val canInstall: Boolean get() = false

    /** HTTPS GET, the body as text. Throws on a network error or a non-200 reply. */
    suspend fun fetchText(url: String): String

    /**
     * Downloads [asset], checks its size and SHA-256 against GitHub's, and starts the system install.
     * Returns once the system has it (or it failed, see [InstallEvent]); what happens after the
     * phone's own confirm dialog arrives later through [onEvent]. Throws on a download or check error.
     */
    suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit, onEvent: (InstallEvent) -> Unit) {
        throw UnsupportedOperationException("This phone can't install updates itself")
    }

    /** Opens a web page in the phone's browser. */
    fun openPage(url: String)

    /** No network (tests, previews). */
    object None : Updater {
        override suspend fun fetchText(url: String): String = throw UnsupportedOperationException("no network")
        override fun openPage(url: String) {}
    }
}

sealed interface InstallEvent {
    /** Android needs "Install unknown apps" allowed for Z1 Pendant first; its settings page was opened. */
    data object NeedsPermission : InstallEvent
    /** The system's install confirmation is showing. */
    data object Confirming : InstallEvent
    /** The user said no in the system's dialog. */
    data object Cancelled : InstallEvent
    data class Failed(val message: String) : InstallEvent
}
