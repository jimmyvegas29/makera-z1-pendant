package com.z1pendant.ui

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
fun SettingsScreen(vm: AppViewModel, onClose: () -> Unit) {
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
                Text("Status: $conn" + (target?.let { " · ${it.name} ${it.host}:${it.port}" } ?: "") + (fw?.let { " · fw $it" } ?: ""), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PendantOutlinedButton(onClick = vm::scan, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Scan network") }
                    if (conn != ConnState.Disconnected) PendantOutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                }
                found.forEach { (t, busy) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.connect(t); onClose() }.padding(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontWeight = FontWeight.SemiBold)
                            Text("${t.host}:${t.port}" + if (busy) " · busy (another app connected?)" else "", fontSize = 12.sp)
                        }
                        Text("Connect", color = MaterialTheme.colorScheme.primary)
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

                Section("Handwheel & jog")
                LabeledSlider("Vibration strength", haptic, 0f..1f, "${(haptic * 100).toInt()}%") { vm.setHaptic(it) }
                if (!vm.haptics.strengthAdjustable)
                    Text("This phone vibrates at one strength only: the slider switches vibration on or off.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabeledSlider("Degrees per click", deg, 6f..30f, "${deg.toInt()}°", steps = 23) { vm.setDegPerClick(it.toInt().toFloat()) }
                LabeledSlider("Jog batch interval", tick.toFloat(), 20f..120f, "$tick ms", steps = 9) { vm.setTickMs(it.toLong()) }
                SwitchRow("Volume keys jog ±1 click", volumeJog) { vm.setVolumeJog(it) }
                SwitchRow("Clamp jogs to soft limits", clamp) { vm.setSoftClamp(it) }
                SwitchRow("Keep screen on", keepOn) { vm.setKeepScreenOn(it) }
                Text(vm.haptics.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section("Hotkeys")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PendantOutlinedButton(onClick = { files.export() }) { Text("Export…") }
                    PendantOutlinedButton(onClick = { files.import() }) { Text("Import…") }
                    TextButton(onClick = { confirmReset = true }) { Text("Reset to defaults") }
                }
                Text("Import replaces the whole set.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, modifier = Modifier.weight(1f)); Text(valueText, fontFamily = FontFamily.Monospace) }
        Slider(value, onChange, valueRange = range, steps = steps)
    }
}

@Composable
fun ConsoleScreen(vm: AppViewModel, onClose: () -> Unit) {
    val lines by vm.console.collectAsState()
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
                Text(st?.raw ?: "(no status)", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                    items(lines) { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
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
