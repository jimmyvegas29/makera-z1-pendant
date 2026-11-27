package com.z1pendant.ui

import com.z1pendant.util.Serial
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.platform.rememberHotkeyFileActions

@Composable
fun SettingsScreen(vm: AppViewModel, onAnchors: () -> Unit = {}, onClose: () -> Unit) {
    val conn by vm.link.conn.collectAsState()
    val target by vm.target.collectAsState()
    val found by vm.discovered.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val fw by vm.link.firmware.collectAsState()
    val haptic by vm.hapticStrength.collectAsState()
    val volumeJog by vm.volumeJog.collectAsState()
    val keepOn by vm.keepScreenOn.collectAsState()
    val tick by vm.tickMs.collectAsState()
    val deg by vm.degPerClick.collectAsState()
    val clamp by vm.softClamp.collectAsState()
    val zeroHold by vm.zeroHold.collectAsState()
    val hideSerial by vm.hideSerial.collectAsState()
    val levelBelow by vm.run.levelBelow.collectAsState()
    var host by remember { mutableStateOf(target?.host ?: "") }
    var port by remember { mutableStateOf((target?.port ?: 2222).toString()) }

    // Android: system file picker (save / open). iOS: not there yet (says so).
    val files = rememberHotkeyFileActions(vm)
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (conn != ConnState.Connected) vm.scan() }

    PlatformBackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }

                Section("Machine")
                Text("Status: $conn" + (target?.let { " · ${Serial.shown(it.name, hideSerial)} ${it.host}:${it.port}" } ?: "") + (fw?.let { " · fw $it" } ?: ""), fontSize = 13.sp)
                // The machine we're on (or trying to reach), matched by address: the name differs when
                // you connected by IP.
                fun isCurrent(t: MachineTarget) = conn != ConnState.Disconnected && target?.let { it.host == t.host && it.port == t.port } == true
                val currentListed = found.any { (t, _) -> isCurrent(t) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PendantOutlinedButton(onClick = vm::scan, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Scan network") }
                    // Its row has Disconnect; this one is for a machine the scan didn't find.
                    if (conn != ConnState.Disconnected && !currentListed) PendantOutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                }
                found.forEach { (t, busy) ->
                    val current = isCurrent(t)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { if (current) vm.disconnect() else { vm.connect(t); onClose() } }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Serial.shown(t.name, hideSerial), fontWeight = FontWeight.SemiBold)
                            Text("${t.host}:${t.port}" + when {
                                current && conn == ConnState.Connected -> " · connected"
                                current && conn == ConnState.Stalled -> " · no reply"
                                current -> " · connecting…"
                                busy -> " · busy (another app connected?)"
                                else -> ""
                            }, fontSize = 12.sp)
                        }
                        Text(if (current) "Disconnect" else "Connect",
                            color = if (current) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
                if (!scanning && found.isEmpty()) Text("Nothing found by broadcast. Enter the IP:", fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(90.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Button(onClick = { if (host.isNotBlank()) { vm.connect(MachineTarget(target?.takeIf { it.host == host }?.name ?: host, host, port.toIntOrNull() ?: 2222)); onClose() } }) { Text("Connect") }

                SwitchRow("Hide the serial number (for recordings)", hideSerial) { vm.setHideSerial(it) }

                Section("Handwheel & jog")
                // Feel it while you slide: one dial click at the new strength on every 5 % step (no
                // closer than 40 ms apart), and one more at the value you let go on. A phone with one
                // strength only buzzes when the slider turns vibration on.
                var lastStep by remember { mutableStateOf(-1) }
                var lastBuzz by remember { mutableStateOf(0L) }
                LabeledSlider("Vibration strength", haptic, 0f..1f, "${(haptic * 100).toInt()}%",
                    onChangeFinished = { if (vm.haptics.strengthAdjustable) vm.haptics.tick(); lastStep = -1 }) { v ->
                    val wasOn = vm.hapticStrength.value > 0f
                    vm.setHaptic(v)
                    val step = kotlin.math.round(v * 20).toInt()
                    if (lastStep == -1) lastStep = step
                    val now = com.z1pendant.platform.nowMs()
                    val buzz = if (vm.haptics.strengthAdjustable) step != lastStep && now - lastBuzz >= 40 else !wasOn && v > 0f
                    if (buzz) { vm.haptics.tick(); lastStep = step; lastBuzz = now }
                }
                if (!vm.haptics.strengthAdjustable)
                    Text("This phone vibrates at one strength only: the slider switches vibration on or off.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabeledSlider("Degrees per click", deg, 6f..30f, "${deg.toInt()}°", steps = 23) { vm.setDegPerClick(it.toInt().toFloat()) }
                LabeledSlider("Jog batch interval", tick.toFloat(), 20f..120f, "$tick ms", steps = 9) { vm.setTickMs(it.toLong()) }
                SwitchRow("Volume keys jog ±1 click", volumeJog) { vm.setVolumeJog(it) }
                SwitchRow("Clamp jogs to soft limits", clamp) { vm.setSoftClamp(it) }
                SwitchRow("Hold the readout's zero buttons 1.5 s (instead of a tap)", zeroHold) { vm.setZeroHold(it) }
                SwitchRow("Keep screen on", keepOn) { vm.setKeepScreenOn(it) }
                Text(vm.haptics.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section("Run")
                val anchorList by vm.anchors.list.collectAsState()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onAnchors).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Anchors", fontWeight = FontWeight.SemiBold)
                        Text(if (anchorList.isEmpty()) "Save points like a vise jaw to use as a job's origin" else "${anchorList.size} saved for this Z1",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                }
                // 0.5 to 10 mm in 0.5 mm steps.
                LabeledSlider("Leveling: deepest touch below the stock top", levelBelow.toFloat(), 0.5f..10f,
                    "${com.z1pendant.util.Fmt.trimmed4(levelBelow)} mm", steps = 18) { vm.run.setLevelBelow(kotlin.math.round(it * 2) / 2.0) }
                Text("A leveling touch that finds nothing this far below the stock top stops the job, instead of carrying on down " +
                    "(past the stock's edge it would reach the vise or the bed).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section("Hotkeys")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PendantOutlinedButton(onClick = { files.export() }) { Text("Export…") }
                    PendantOutlinedButton(onClick = { files.import() }) { Text("Import…") }
                    TextButton(onClick = { confirmReset = true }) { Text("Reset to defaults") }
                }
                Text("Import replaces the whole set.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section("App")
                UpdatesSection(vm.updates)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Replace all hotkeys with the defaults?") },
        confirmButton = { TextButton(onClick = { vm.resetHotkeys(); confirmReset = false }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
}

@Composable
private fun Section(t: String) = Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, steps: Int = 0,
                  onChangeFinished: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, modifier = Modifier.weight(1f)); Text(valueText, fontFamily = FontFamily.Monospace) }
        Slider(value, onChange, valueRange = range, steps = steps, onValueChangeFinished = onChangeFinished)
    }
}

@Composable
fun ConsoleScreen(vm: AppViewModel, onClose: () -> Unit) {
    val lines by vm.console.collectAsState()
    val hideSerial by vm.hideSerial.collectAsState()
    val st by vm.link.status.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    PlatformBackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text("Console", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(st?.raw?.let { Serial.shown(it, hideSerial) } ?: "(no status)", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                    items(lines) { Text(Serial.shown(it, hideSerial), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Command") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { vm.sendConsole(input); input = "" }))
                    TextButton(onClick = { vm.sendConsole(input); input = "" }) { Text("Send") }
                }
            }
        }
    }
}
