package com.z1pendant.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.z1pendant.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Android updates: download the release APK into the cache, check it (size and SHA-256 against
 * GitHub's, same package, same signing key, newer version code), then install it through
 * [PackageInstaller]. Android shows its own "update this app?" confirmation; on yes it replaces the
 * app (settings and hotkeys stay) and closes it.
 *
 * Needs `REQUEST_INSTALL_PACKAGES` in the manifest and, once, the user's "Install unknown apps" OK
 * for Z1 Pendant (Android's settings page for it is opened when it's missing).
 */
class AndroidUpdater(ctx: Context) : Updater {
    private val app = ctx.applicationContext
    private val dir = File(app.cacheDir, "update")
    private val statusAction = "${app.packageName}.UPDATE_STATUS"
    @Volatile private var onEvent: (InstallEvent) -> Unit = {}
    private var receiverRegistered = false

    init {
        // A downloaded APK isn't needed once it's installed (or abandoned).
        runCatching { dir.deleteRecursively() }
    }

    override val canInstall = true

    override suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url, api = true)
        try {
            if (c.responseCode != 200) throw IOException("GitHub answered HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    override fun openPage(url: String) {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit, onEvent: (InstallEvent) -> Unit) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onEvent(InstallEvent.NeedsPermission)
            return
        }
        val sha = asset.sha256 ?: throw IOException("GitHub listed no SHA-256 for ${asset.name}, so the download can't be checked")
        val apk = withContext(Dispatchers.IO) { download(asset, sha, onProgress) }
        withContext(Dispatchers.IO) { checkApk(apk) }
        this.onEvent = onEvent
        withContext(Dispatchers.IO) { commit(apk) }
    }

    private fun open(url: String, api: Boolean) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true      // release files redirect to GitHub's download host (https → https)
        setRequestProperty("User-Agent", "Z1-Pendant/${BuildInfo.VERSION}")
        if (api) setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** Streams the APK to the cache, hashing as it goes; throws unless size and SHA-256 match GitHub's. */
    private suspend fun download(asset: ReleaseAsset, sha256: String, onProgress: (Float) -> Unit): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val out = File(dir, "z1-pendant-update.apk")
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(asset.url, api = false)
        try {
            if (c.responseCode != 200) throw IOException("Download failed: HTTP ${c.responseCode}")
            val total = if (asset.size > 0) asset.size else c.contentLengthLong
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                out.outputStream().use { fo ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        fo.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) { shown = pct; onProgress((done.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
            if (asset.size > 0 && done != asset.size) throw IOException("Download incomplete ($done of ${asset.size} bytes)")
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != sha256) throw IOException("The download doesn't match GitHub's SHA-256; not installing it")
            return out
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Same app, same signing key (Android would refuse otherwise, less clearly), and actually newer. */
    private fun checkApk(apk: File) {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("The download isn't an Android app")
        if (theirs.packageName != app.packageName) throw IOException("The download is a different app (${theirs.packageName})")
        val mine = pm.getPackageInfo(app.packageName, flags)
        if (theirs.longVersionCode <= mine.longVersionCode)
            throw IOException("The download (${theirs.versionName}) isn't newer than this app (${mine.versionName})")
        val a = mine.signingInfo?.certHashes().orEmpty()
        val b = theirs.signingInfo?.certHashes().orEmpty()
        if (a.isEmpty() || b.isEmpty() || a.intersect(b).isEmpty())
            throw IOException("This copy of Z1 Pendant was signed with a different key than the release (built by someone " +
                "else?), so Android won't update it in place. Uninstall it, then install the APK from the release page.")
    }

    private fun SigningInfo.certHashes(): Set<String> {
        val sigs = if (hasMultipleSigners()) apkContentsSigners else signingCertificateHistory
        return sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun commit(apk: File) {
        registerReceiver()
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("z1-pendant.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                // Mutable: the installer fills in the status extras. Explicit (setPackage), as Android 14 requires.
                val pi = PendingIntent.getBroadcast(app, id, Intent(statusAction).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pi.intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                        if (confirm == null) { onEvent(InstallEvent.Failed("Android didn't show its install dialog")); return }
                        runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onSuccess { onEvent(InstallEvent.Confirming) }
                            .onFailure { onEvent(InstallEvent.Failed("Couldn't open Android's install dialog: ${it.message}")) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {}      // the app is being replaced (and closed)
                    PackageInstaller.STATUS_FAILURE_ABORTED -> onEvent(InstallEvent.Cancelled)
                    else -> onEvent(InstallEvent.Failed(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "installer status $st"))
                }
            }
        }
        val filter = IntentFilter(statusAction)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(r, filter)
    }
}
