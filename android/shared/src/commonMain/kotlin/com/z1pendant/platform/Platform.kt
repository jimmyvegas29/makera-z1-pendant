package com.z1pendant.platform

import androidx.compose.runtime.Composable
import com.z1pendant.AppViewModel
import com.z1pendant.Haptics

/**
 * What the shared code needs from the phone. Android: `AndroidPlatform` (SharedPreferences
 * "z1pendant", filesDir/hotkeys.json, the vibrator, a Wi-Fi multicast lock). iOS: `IosPlatform`
 * (NSUserDefaults, Documents/hotkeys.json, UIKit feedback generators).
 */
interface Platform {
    val prefs: Prefs
    /** The saved hotkey set (hotkeys.json). */
    val hotkeyFile: TextFile
    val haptics: Haptics
    /** Held while listening for discovery broadcasts (Android: Wi-Fi multicast lock). Returns the release. */
    fun holdDiscoveryLock(): () -> Unit = {}
    /** App updates: the GitHub check, and (Android) download and install. */
    val updater: com.z1pendant.update.Updater get() = com.z1pendant.update.Updater.None
}

/** Key/value settings with SharedPreferences semantics (missing key = the default). */
interface Prefs {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float
    fun getString(key: String, default: String?): String?
    /** Several writes applied together (Android: one `edit()…apply()`). */
    fun edit(block: PrefsEditor.() -> Unit)
}

interface PrefsEditor {
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putFloat(key: String, value: Float)
    fun putString(key: String, value: String)
}

interface TextFile {
    fun exists(): Boolean
    fun readText(): String
    fun writeText(text: String)
}

/** Monotonic milliseconds (Android: `SystemClock.elapsedRealtime()`). For timing only. */
expect fun nowMs(): Long

/** Local wall-clock time as HH:mm:ss, for console time stamps. */
expect fun wallClockHms(): String

/** Unix time, seconds (UTC). Sent to the Z1 before an upload so files get the right date. */
expect fun epochSeconds(): Long

/** This phone's offset from UTC at [epochSec], seconds (daylight saving included). */
expect fun utcOffsetSeconds(epochSec: Long): Int

/**
 * The phone's own "open a file" picker (Android: the system document picker; iOS: the Files app picker).
 * Call the returned function to show it; [onPicked] gets the file's name and bytes.
 */
@Composable
expect fun rememberFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit

/**
 * Whether that picker has its own Cancel button. iOS's does. Android's (DocumentsUI) has no Cancel, X or
 * back arrow, and with gesture navigation there's no back button either: only the back swipe gets out
 * (checked on a Pixel 10, Android 16). The Upload sheet tells Android users how to back out.
 */
expect val systemPickerHasCancel: Boolean

/** Platform log (Android: logcat). [level] is 'D', 'I' or 'W'. */
expect fun platformLog(level: Char, tag: String, msg: String)

object PLog {
    fun d(tag: String, msg: String) = platformLog('D', tag, msg)
    fun i(tag: String, msg: String) = platformLog('I', tag, msg)
    fun w(tag: String, msg: String) = platformLog('W', tag, msg)
}

/** System back (Android back gesture / button). No-op on iOS, which has no back button. */
@Composable
expect fun PlatformBackHandler(onBack: () -> Unit)

/** Hotkey set export / import as a file (Settings → Hotkeys). */
interface HotkeyFileActions {
    fun export()
    fun import()
}

@Composable
expect fun rememberHotkeyFileActions(vm: AppViewModel): HotkeyFileActions
