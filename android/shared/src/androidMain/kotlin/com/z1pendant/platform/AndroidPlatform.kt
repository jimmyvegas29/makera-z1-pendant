package com.z1pendant.platform

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import com.z1pendant.AndroidHaptics
import com.z1pendant.Haptics
import java.io.File

/**
 * Android side of [Platform]. Same storage as before the multiplatform split, so an update keeps
 * the user's settings and hotkeys: SharedPreferences "z1pendant" and filesDir/hotkeys.json.
 */
class AndroidPlatform(ctx: Context) : Platform {
    private val app = ctx.applicationContext
    override val prefs: Prefs = SharedPrefs(app.getSharedPreferences("z1pendant", Context.MODE_PRIVATE))
    override val hotkeyFile: TextFile = FileText(File(app.filesDir, "hotkeys.json"))
    override val haptics: Haptics = AndroidHaptics(app)
    override val updater: com.z1pendant.update.Updater = com.z1pendant.update.AndroidUpdater(app)

    /** Some phones drop broadcast packets unless a multicast lock is held. */
    override fun holdDiscoveryLock(): () -> Unit {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("z1pendant-discovery").apply { setReferenceCounted(false); acquire() }
        return { lock.release() }
    }
}

private class SharedPrefs(private val sp: SharedPreferences) : Prefs {
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun getLong(key: String, default: Long) = sp.getLong(key, default)
    override fun getFloat(key: String, default: Float) = sp.getFloat(key, default)
    override fun getString(key: String, default: String?): String? = sp.getString(key, default)
    override fun edit(block: PrefsEditor.() -> Unit) {
        val e = sp.edit()
        object : PrefsEditor {
            override fun putBoolean(key: String, value: Boolean) { e.putBoolean(key, value) }
            override fun putInt(key: String, value: Int) { e.putInt(key, value) }
            override fun putLong(key: String, value: Long) { e.putLong(key, value) }
            override fun putFloat(key: String, value: Float) { e.putFloat(key, value) }
            override fun putString(key: String, value: String) { e.putString(key, value) }
        }.block()
        e.apply()
    }
}

private class FileText(private val f: File) : TextFile {
    override fun exists() = f.exists()
    override fun readText() = f.readText()
    override fun writeText(text: String) = f.writeText(text)
}
