package com.z1pendant.platform

import com.z1pendant.Haptics
import com.z1pendant.IosHaptics
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/** iOS side of [Platform]: NSUserDefaults, Documents/hotkeys.json, UIKit feedback generators. */
class IosPlatform : Platform {
    override val prefs: Prefs = UserDefaultsPrefs(NSUserDefaults.standardUserDefaults)
    override val hotkeyFile: TextFile = DocumentsFile("hotkeys.json")
    override val haptics: Haptics = IosHaptics()
    override val updater: com.z1pendant.update.Updater = com.z1pendant.update.IosUpdater()
    // Receiving the UDP 3333 broadcasts needs no lock on iOS, only the Local Network permission
    // (NSLocalNetworkUsageDescription). No multicast entitlement needed: confirmed on an iPhone 16.
}

private class UserDefaultsPrefs(private val d: NSUserDefaults) : Prefs {
    private fun has(key: String) = d.objectForKey(key) != null
    override fun getBoolean(key: String, default: Boolean) = if (has(key)) d.boolForKey(key) else default
    override fun getInt(key: String, default: Int) = if (has(key)) d.integerForKey(key).toInt() else default
    override fun getLong(key: String, default: Long) = if (has(key)) d.integerForKey(key) else default
    override fun getFloat(key: String, default: Float) = if (has(key)) d.floatForKey(key) else default
    override fun getString(key: String, default: String?): String? = d.stringForKey(key) ?: default
    override fun edit(block: PrefsEditor.() -> Unit) {
        object : PrefsEditor {
            override fun putBoolean(key: String, value: Boolean) = d.setBool(value, key)
            override fun putInt(key: String, value: Int) = d.setInteger(value.toLong(), key)
            override fun putLong(key: String, value: Long) = d.setInteger(value, key)
            override fun putFloat(key: String, value: Float) = d.setFloat(value, key)
            override fun putString(key: String, value: String) = d.setObject(value, key)
        }.block()
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class DocumentsFile(name: String) : TextFile {
    private val path: String = run {
        val dir = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
        dir.URLByAppendingPathComponent(name)!!.path!!
    }

    override fun exists() = NSFileManager.defaultManager.fileExistsAtPath(path)

    override fun readText(): String =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: error("can't read $path")

    override fun writeText(text: String) {
        val ok = NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!ok) error("can't write $path")
    }
}
