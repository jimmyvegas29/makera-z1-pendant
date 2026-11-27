package com.z1pendant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.hotkeys.Hotkey

/** The app's dark theme (slate background, sky-blue accent). */
@Composable
fun PendantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF38BDF8), onPrimary = Color(0xFF00222F),
        secondaryContainer = Color(0xFF1E293B), onSecondaryContainer = Color(0xFFE2E8F0),
        surfaceVariant = Color(0xFF172033), onSurfaceVariant = Color(0xFF94A3B8),
        background = Color(0xFF0B1120), surface = Color(0xFF0F172A),
    ), content = content)
}

@Composable
fun App(vm: AppViewModel) {
    var settings by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    /** The bed map ("Go to") is open. */
    var mapOpen by remember { mutableStateOf(false) }
    /** The probe wizard was opened from the Run flow's Custom origin: it goes back there. */
    var probeFromRun by remember { mutableStateOf(false) }
    /** Settings › Anchors is open (over Settings). */
    var anchorsOpen by remember { mutableStateOf(false) }
    /** The probe wizard was opened from Settings › Anchors: it goes back there. */
    var probeFromAnchors by remember { mutableStateOf(false) }
    val runActive by vm.run.active.collectAsState()
    val runPick by vm.runPick.collectAsState()
    var meshOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Hotkey?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    val confirm by vm.confirmFor.collectAsState()
    val spindleAsk by vm.spindleAsk.collectAsState()
    val spindleStopAsk by vm.spindleStopAsk.collectAsState()
    val resetAsk by vm.resetAsk.collectAsState()
    val snackHost = remember { SnackbarHostState() }
    // Kept here so returning from the editor/settings doesn't jump the main screen back to the top.
    val mainScroll = rememberScrollState()
    LaunchedEffect(Unit) { vm.snack.collect { snackHost.currentSnackbarData?.dismiss(); snackHost.showSnackbar(it) } }
    // A file shared from another app: open Files, which asks "Upload to the Z1?". A probe, the Run flow or
    // the hotkey editor isn't interrupted; the question waits until Files is opened.
    val shared by com.z1pendant.files.ShareInbox.file.collectAsState()
    LaunchedEffect(shared, probing, runActive, editorOpen) {
        if (shared != null && !probing && !runActive && !editorOpen) { settings = false; console = false; files = true }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Full-screen pages live in the activity window (not Dialogs) so dispatchKeyEvent sees
        // every button press, which the "press any button to bind" flow relies on.
        vm.onMainScreen = !(editorOpen || settings || anchorsOpen || console || probing || mapOpen || files || runActive || runPick)
        // Windows 600 dp or wider (iPad, Android tablet) get the wide layout; see LocalWide.
        androidx.compose.runtime.CompositionLocalProvider(LocalWide provides (maxWidth >= 600.dp),
            LocalLandscape provides (maxWidth >= 840.dp && maxWidth > maxHeight)) {
            when {
                editorOpen -> HotkeyEditor(vm, editing) { editorOpen = false; editing = null; vm.capture.value = null }
                // Before Settings: Anchors opens the wizard from over Settings.
                probing -> ProbeWizard(vm, doneLabel = if (probeFromRun || probeFromAnchors) "Next" else "Done",
                    requireZero = probeFromRun || probeFromAnchors, offerAnchor = !probeFromAnchors) { zeroSet ->
                    probing = false
                    if (probeFromRun) { probeFromRun = false; vm.run.probeReturned('X' in zeroSet || 'Y' in zeroSet) }
                    if (probeFromAnchors) { probeFromAnchors = false; vm.anchors.probeReturned(zeroSet) }
                }
                mapOpen -> MapScreen(vm) { mapOpen = false }
                anchorsOpen -> AnchorsScreen(vm, onProbe = { probeFromAnchors = true; probing = true }) { anchorsOpen = false }
                settings -> SettingsScreen(vm, onAnchors = { anchorsOpen = true }) { settings = false }
                console -> ConsoleScreen(vm) { console = false }
                runActive -> RunScreen(vm, onProbe = { probeFromRun = true; probing = true })
                files || runPick -> FilesScreen(vm, pickForRun = runPick && !files,
                    onRun = { p -> files = false; vm.runPick.value = false; vm.run.open(p) }) { files = false; vm.runPick.value = false }
                else -> MainScreen(vm, onSettings = { settings = true }, onConsole = { console = true }, onFiles = { files = true },
                    onEdit = { editing = it; editorOpen = true }, scroll = mainScroll, onProbe = { probing = true }, onMap = { mapOpen = true })
            }
        }
        if (vm.onMainScreen) ToolChangePopup(vm)
        SnackbarHost(snackHost, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    } }
    if (spindleAsk) SpindleDialog(vm) { vm.spindleAsk.value = false }
    if (spindleStopAsk) SpindleStopDialog(vm) { vm.spindleStopAsk.value = false }
    if (meshOpen) MeshSheet(vm) { meshOpen = false }
    LaunchedEffect(Unit) { vm.run.meshShow.collect { if (it) { meshOpen = true; vm.run.meshShow.value = false } } }
    if (resetAsk) ResetDialog(onReset = vm::confirmReset, onDismiss = { vm.resetAsk.value = false },
        jobActive = vm.link.status.collectAsState().value?.job != null)
    val alarmSheet by vm.alarmSheet.collectAsState()
    if (alarmSheet) AlarmSheet(vm)
    // A newer release: the startup check's dialog waits for the main screen; What's new (Settings) shows at once.
    vm.updates.prompt.collectAsState().value?.let { p ->
        if (!p.auto || vm.onMainScreen) UpdateDialog(vm.updates, p)
    }
    // A leveling grid found on connect (Idle, no job): it shifts Z everywhere until cleared.
    vm.meshPrompt.collectAsState().value?.let { max ->
        AlertDialog(onDismissRequest = {},
            title = { Text("Leveling mesh still active") },
            text = {
                Text("The Z1 still has a leveling mesh loaded (largest correction ${com.z1pendant.util.Fmt.fixed(max, 3)} mm), " +
                    "usually left over from an earlier job. Until it's cleared, the machine adds it to Z on every move, " +
                    "jogging and setting zero included, so Z positions can be off by that much.")
            },
            confirmButton = { TextButton(onClick = vm::clearMesh) { Text("Clear mesh") } },
            dismissButton = { TextButton(onClick = vm::keepMesh) { Text("Keep") } })
    }
    confirm?.let { h ->
        AlertDialog(onDismissRequest = { vm.confirmFor.value = null },
            title = { Text("Run “${h.label}”?") },
            // G-code steps list every line as it will be sent; other steps get a one-line description.
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    h.steps.forEach { s ->
                        if (s is com.z1pendant.hotkeys.Step.GCode) {
                            s.text.lines().filter { it.isNotBlank() }.forEach {
                                Text(it.trim(), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                            }
                        } else {
                            Text(s.describe { id -> vm.hotkeys.value.firstOrNull { it.id == id }?.label })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmRun) { Text("Run") } },
            dismissButton = { TextButton(onClick = { vm.confirmFor.value = null }) { Text("Cancel") } })
    }
}
