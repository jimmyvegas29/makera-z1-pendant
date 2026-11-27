package com.z1pendant

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.z1pendant.platform.AndroidPlatform
import com.z1pendant.ui.App
import com.z1pendant.ui.PendantTheme

/**
 * The Android shell: everything else (UI, view model, link, jog engine, hotkeys) is in :shared.
 * Here: window flags, the Wi-Fi low-latency lock, hardware keys and the adb test hooks.
 */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(AndroidPlatform(application)) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones stay portrait (manifest); tablets may rotate and get the side-by-side layout
        // in landscape. (Android 16 already ignores the lock on large screens.)
        if (resources.configuration.smallestScreenWidthDp >= 600)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)      // not again on rotation
        setContent {
            val keepOn by vm.keepScreenOn.collectAsState()
            LaunchedEffect(keepOn) {
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            PendantTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * adb test hooks: `am start -n com.z1pendant/.MainActivity --es host 192.168.1.50 [--ei port 2222] [--es name Z1]`.
     *
     * Debug builds only. The launcher activity is exported, so any app on the phone can start it
     * with extras; in a release build these would let it jog the machine or point the pendant at
     * another host.
     */
    private fun handleIntent(i: android.content.Intent?) {
        i ?: return
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        // Test hook: `--ei testclicks 100 --ei testms 1000 [--ei teststep 3]` = burst of handwheel clicks.
        if (i.hasExtra("testclicks")) {
            if (i.hasExtra("teststep")) vm.selectStep(i.getIntExtra("teststep", 2))
            val n = i.getIntExtra("testclicks", 0); val ms = i.getIntExtra("testms", 1000).toLong()
            vm.viewModelScopeLaunchBurst(n, ms)
        }
        // Test hook: `--es updateurl http://<pc>:8000/latest.json` = update check against a stand-in release.
        i.getStringExtra("updateurl")?.let { vm.updates.testCheck(it) }
        val host = i.getStringExtra("host") ?: return
        vm.connect(com.z1pendant.link.MachineTarget(i.getStringExtra("name") ?: host, host, i.getIntExtra("port", 2222)))
    }

    /**
     * Wi-Fi power save made status round-trips swing 18–300 ms on the Pixel 10 (the radio dozes
     * between polls). The low-latency lock turns power save off while the app is visible; Android
     * only honours it for a foreground app with the screen on, so holding it here costs nothing
     * in the background.
     */
    private val wifiLock by lazy {
        (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "z1pendant-jog")
            .apply { setReferenceCounted(false) }
    }

    override fun onResume() { super.onResume(); wifiLock.acquire() }
    override fun onPause() { wifiLock.release(); super.onPause() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.onKey(event.toKeyInput())) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        vm.jog.cancelPending()
    }
}

/** Android key event → the shared, platform-neutral [KeyInput] (key codes stay Android's). */
private fun KeyEvent.toKeyInput(): KeyInput {
    val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase().replace('_', ' ')
    return KeyInput(
        keyCode = keyCode,
        action = when (action) {
            KeyEvent.ACTION_DOWN -> KeyAction.Down
            KeyEvent.ACTION_UP -> KeyAction.Up
            else -> KeyAction.Other
        },
        repeatCount = repeatCount,
        downTime = downTime,
        eventTime = eventTime,
        isLongPress = isLongPress,
        deviceName = device?.name ?: InputDevice.getDevice(deviceId)?.name,
        label = name.replaceFirstChar { it.uppercase() },
        isGamepadButton = KeyEvent.isGamepadButton(keyCode),
    )
}
