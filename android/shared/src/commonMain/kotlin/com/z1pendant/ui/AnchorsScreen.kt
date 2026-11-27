package com.z1pendant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.link.ConnState
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.probe.ProbeKind
import com.z1pendant.run.Anchor
import com.z1pendant.run.AnchorsController
import com.z1pendant.util.Fmt
import com.z1pendant.util.Serial

private val CardBg = Color(0xFF172033)
private val Line = Color(0xFF334155)
private val Green = Color(0xFF4ADE80)
private val Amber = Color(0xFFFDE68A)
private val AmberBg = Color(0xFF3A2A0B)

private fun mm(v: Double) = Fmt.fixed(v, 3).replace('-', '−')

/**
 * Settings › Anchors (mockup screens 2–4): the saved anchors for this Z1, a New / Edit page that fills
 * the point in by probing, from the spindle, or by typing, and the Re-probe comparison. [onProbe]
 * opens the 3D probe wizard; when it closes, [AnchorsController.probeReturned] fills in what it set.
 */
@Composable
fun AnchorsScreen(vm: AppViewModel, onProbe: () -> Unit, onClose: () -> Unit) {
    val a = vm.anchors
    val draft by a.draft.collectAsState()
    val compare by a.compare.collectAsState()
    val target by vm.target.collectAsState()
    val hideSerial by vm.hideSerial.collectAsState()
    PlatformBackHandler {
        when {
            compare != null -> a.keepSaved()
            draft != null -> a.closeEditor()
            else -> onClose()
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { when { compare != null -> a.keepSaved(); draft != null -> a.closeEditor(); else -> onClose() } }) {
                Icon(Icons.Filled.ArrowBack, "Back")
            }
            Text(when {
                compare != null -> compare!!.anchor.name
                draft != null -> if (draft!!.id == null) "New anchor" else "Edit anchor"
                else -> "Anchors" + (target?.name?.let { " · ${Serial.shown(it, hideSerial)}" } ?: "")
            }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                compare != null -> CompareView(vm, compare!!)
                draft != null -> Editor(vm, draft!!, onProbe)
                else -> AnchorList(vm, onProbe)
            }
            val reading by a.reading.collectAsState()
            val note by a.note.collectAsState()
            if (reading) Text("Reading the work zero from the machine…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            note?.let { Text(it, fontSize = 13.sp, color = Amber) }
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ---- list ----------------------------------------------------------------------------------------------
@Composable
private fun AnchorList(vm: AppViewModel, onProbe: () -> Unit) {
    val a = vm.anchors
    val list by a.list.collectAsState()
    val anchor1 by vm.run.anchor.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(conn) { if (conn == ConnState.Connected) vm.run.loadAnchor1() }
    Text("Saved on this phone for this Z1, in machine coordinates, so they hold after every homing. " +
        "Pick one in Run as the job's origin (Custom anchor).", fontSize = 13.sp, color = muted)
    for (an in list) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(an.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (an.z != null) {
                        Spacer(Modifier.width(6.dp))
                        Text("has Z", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0E2A3D)).padding(horizontal = 6.dp))
                    }
                }
                Text(anchorCoords(an), fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = muted)
                if (an.how.isNotBlank()) Text(an.how, fontSize = 11.5.sp, color = muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { a.probeAgain(an); onProbe() }, enabled = conn == ConnState.Connected) { Text("Re-probe") }
                TextButton(onClick = { a.startEdit(an) }) { Text("Edit") }
            }
        }
    }
    // The factory Anchor 1, read from the machine. Shown for reference only: the tool setter hangs off it.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("Anchor 1 (factory)", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(anchor1?.let { (x, y) -> "X ${mm(x)} · Y ${mm(y)}" } ?: if (conn == ConnState.Connected) "Reading…" else "Not connected",
            fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = muted)
        Text("Read from the Z1's config. The tool setter and 4th axis are placed from it, so the pendant only reads it.",
            fontSize = 11.5.sp, color = muted)
    }
    Button(onClick = a::startNew, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("+ New anchor", fontSize = 16.sp) }
}

// ---- new / edit ----------------------------------------------------------------------------------------------
@Composable
private fun Editor(vm: AppViewModel, d: AnchorsController.Draft, onProbe: () -> Unit) {
    val a = vm.anchors
    val conn by vm.link.conn.collectAsState()
    val st by vm.link.status.collectAsState()
    val connected = conn == ConnState.Connected
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    OutlinedTextField(d.name, { a.updateDraft(d.copy(name = it)); error = null }, label = { Text("Name") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())

    Text("HOW TO SET IT", fontSize = 11.sp, letterSpacing = 0.8.sp, color = muted)
    fun probe(kind: ProbeKind?) {
        val p = vm.probe
        // The tile you tapped here is the operation: Setup opens on it (its own choices still to pick).
        if (kind == ProbeKind.EDGE) { p.update(p.setup.value.copy(kind = ProbeKind.EDGE)); p.pick(com.z1pendant.probe.Pick.OPERATION) }
        else if (!p.setup.value.kind.hasCorner) p.update(p.setup.value.copy(kind = ProbeKind.OUTSIDE))
        a.probeForDraft(); onProbe()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HowTile("Probe a corner", "Inside or outside, with the 3D probe", connected, Modifier.weight(1f)) { probe(null) }
        HowTile("Probe an edge", "One edge, X or Y only", connected, Modifier.weight(1f)) { probe(ProbeKind.EDGE) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HowTile("Where the spindle is", "Jog to it, then tap here (X/Y)", connected && st != null, Modifier.weight(1f)) { a.fromSpindle() }
        HowTile("Type it in", "Machine X, Y (and Z) below", true, Modifier.weight(1f)) { a.updateDraft(d.copy(how = "Typed in")) }
    }

    Text("MACHINE COORDINATES", fontSize = 11.sp, letterSpacing = 0.8.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OptNum("X", d.x, Modifier.weight(1f)) { a.updateDraft(d.copy(x = it, how = if (it != d.x) "Typed in" else d.how)) }
        OptNum("Y", d.y, Modifier.weight(1f)) { a.updateDraft(d.copy(y = it, how = if (it != d.y) "Typed in" else d.how)) }
        OptNum("Z", d.z, Modifier.weight(1f)) { a.updateDraft(d.copy(z = it, how = if (it != d.z) "Typed in" else d.how)) }
    }
    Text("Leave an axis blank if the anchor doesn't set it (an edge gives only X or Y). With a Z saved, a job zeroed " +
        "here can skip the Z probe; leave Z blank for anything whose height changes. Z is the work offset itself, so it " +
        "holds with any tool once its length is measured.", fontSize = 12.sp, color = muted)

    error?.let { Text(it, fontSize = 13.sp, color = Amber) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        PendantOutlinedButton(onClick = a::closeEditor, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel", fontSize = 16.sp) }
        Button(onClick = { error = a.saveDraft() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Save", fontSize = 16.sp) }
    }
    if (d.id != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete this anchor", color = MaterialTheme.colorScheme.error) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${d.name}”?") },
        text = { Text("It's only removed from this phone; nothing on the Z1 changes.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; d.id?.let(a::delete) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun HowTile(title: String, sub: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E293B)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 9.dp)) {
        Text(title, fontSize = 14.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(sub, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
    }
}

/** A number box that may be blank (= not set). Keeps what you type until it parses. */
@Composable
private fun OptNum(label: String, value: Double?, modifier: Modifier, onChange: (Double?) -> Unit) {
    fun show(v: Double?) = v?.let { Fmt.fixed(it, 3) } ?: ""
    var text by remember { mutableStateOf(show(value)) }
    LaunchedEffect(value) { if (text.trim().toDoubleOrNull() != value) text = show(value) }
    OutlinedTextField(text, { t -> text = t; val v = t.trim(); if (v.isEmpty()) onChange(null) else v.toDoubleOrNull()?.let(onChange) },
        label = { Text(label, fontSize = 11.sp) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp))
}

// ---- re-probe ------------------------------------------------------------------------------------------------
@Composable
private fun CompareView(vm: AppViewModel, c: AnchorsController.Compare) {
    val a = vm.anchors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Measured just now. Nothing is changed until you save.", fontSize = 13.sp, color = muted)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Spacer(Modifier.width(28.dp))
            for (h in listOf("Saved", "Measured", "Change")) Text(h.uppercase(), fontSize = 11.sp, color = muted, modifier = Modifier.weight(1f))
        }
        for ((ax, old, new) in listOf(Triple("X", c.anchor.x, c.x), Triple("Y", c.anchor.y, c.y), Triple("Z", c.anchor.z, c.z))) {
            if (old == null && new == null) continue
            Row {
                Text(ax, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.width(28.dp))
                Text(old?.let(::mm) ?: "—", fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(new?.let(::mm) ?: "—", fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                val d = if (old != null && new != null) new - old else null
                Text(d?.let { (if (it >= 0) "+" else "") + mm(it) } ?: "", fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    color = Color(0xFFF59E0B), modifier = Modifier.weight(1f))
            }
        }
    }
    if (c.maxChange > 1.0) Text("That's more than 1 mm from the saved point. Check it's the same setup before saving.",
        fontSize = 13.sp, color = Amber, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AmberBg).padding(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PendantOutlinedButton(onClick = a::keepSaved, modifier = Modifier.weight(1f).height(48.dp)) { Text("Keep saved", fontSize = 15.sp) }
        Button(onClick = a::saveMeasured, modifier = Modifier.weight(1f).height(48.dp)) { Text("Save measured", fontSize = 15.sp) }
    }
    OutlinedButton(onClick = a::saveAsNew, modifier = Modifier.fillMaxWidth().height(46.dp), border = BorderStroke(1.dp, muted)) {
        Text("Save as a new anchor…")
    }
}
