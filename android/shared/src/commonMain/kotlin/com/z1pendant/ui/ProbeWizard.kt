package com.z1pendant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineStatus
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.probe.Corner
import com.z1pendant.probe.EdgeFit
import com.z1pendant.probe.Pick
import com.z1pendant.probe.Touch
import com.z1pendant.probe.missingPick
import com.z1pendant.probe.PROBE_TOOL
import com.z1pendant.probe.Side
import com.z1pendant.probe.ProbeKind
import com.z1pendant.probe.ProbeMode
import com.z1pendant.probe.ProbeResult
import com.z1pendant.probe.ProbeSetup
import com.z1pendant.probe.ProbeLength
import com.z1pendant.probe.ProbeTool
import com.z1pendant.probe.RunState
import com.z1pendant.probe.ToolAction
import com.z1pendant.util.Fmt
import kotlinx.coroutines.launch

private val TileBg = Color(0xFF1E293B)
private val TileOn = Color(0xFF132A3E)
private val Muted = Color(0xFF94A3B8)
private val Green = Color(0xFF22C55E)
private val Amber = Color(0xFFF59E0B)
private val ErrBg = Color(0xFF3B1D22)
private val ErrFg = Color(0xFFF87171)
private val WarnBg = Color(0xFF3A2A0B)
private val WarnFg = Color(0xFFFDE68A)

/**
 * Full-screen 3D probe wizard (mockups/probe_mock.html): 1 what, 2 settings, 3 probe in and
 * calibrated, 4 position + mode + Run, 5 result.
 */
@Composable
/**
 * [requireZero]: opened from Run's 3D probe origin. The finish button ([doneLabel], "Next") only
 * goes on once an X or Y work zero has been set. [onClose] gets the work zero axes the run set (and
 * that weren't put back), empty if none.
 */
fun ProbeWizard(vm: AppViewModel, doneLabel: String = "Done", requireZero: Boolean = false, offerAnchor: Boolean = true,
                onClose: (zeroSet: Set<Char>) -> Unit) {
    val p = vm.probe
    var step by remember { mutableStateOf(1) }
    val setup by p.setup.collectAsState()
    val run by p.run.collectAsState()
    var info by remember { mutableStateOf(false) }
    var askRun by remember { mutableStateOf(false) }
    val running = run is RunState.Running
    fun finish(zeroSet: Set<Char>) { if (!running) { p.closeWizard(); onClose(zeroSet) } }
    fun close() = finish(emptySet())
    // Each step's Next only once that step is satisfied; nothing moves on while the probe is being loaded or calibrated.
    val action by p.toolAction.collectAsState()
    val acting = action?.let { it.stage != ToolAction.Stage.DONE && it.error == null } == true
    val confirmed by p.confirmed.collectAsState(); val calibrated by p.calibrated.collectAsState()
    val len by p.lengthCheck.collectAsState()
    val status by vm.link.status.collectAsState(); val linkConn by vm.link.conn.collectAsState()
    val probeReady = linkConn == ConnState.Connected && status?.tool == PROBE_TOOL && confirmed && calibrated && !acting && len?.ok != false
    val picks by p.picks.collectAsState()
    val limits by vm.softLimits.collectAsState()
    val scroll = rememberScrollState()
    LaunchedEffect(run) { if (run !is RunState.Idle) step = 5 }
    LaunchedEffect(Unit) { p.openWizard() }
    // The bed map, from Move the probe into place: over the wizard, which keeps its place (step,
    // calibration) underneath. Its own back / close returns here.
    var showMap by remember { mutableStateOf(false) }
    if (showMap) { MapScreen(vm) { showMap = false }; return }
    PlatformBackHandler { when { running -> {}; step in 2..4 -> step--; else -> close() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(when (step) {
                1 -> "Probe"; 2 -> "Check the probe"; 3 -> "Move the probe into place"; 4 -> setup.title()
                else -> when (run) { is RunState.Running -> "Probing"; is RunState.Failed -> "Probing stopped"; else -> "Probe finished" }
            }, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = ::close, enabled = !running) { Icon(Icons.Filled.Close, "Close") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..5) Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (i <= step) MaterialTheme.colorScheme.primary else Color(0xFF2B3648)))
        }
        if (step == 2 || step == 4) Row(Modifier.clickable { info = true }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp)); Text("How these work", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                // The start offsets are measured from where the probe is parked, so you set them
                // after moving it into place.
                1 -> StepWhat(setup, picks, scroll, requireZero, { p.update(it) }, { p.pick(it) }, { p.unpick(it) })
                2 -> StepCheck(vm)
                3 -> StepPosition(vm, setup, probeReady) { vm.haptics.press(); showMap = true }
                4 -> { StepSettings(vm, setup) { p.update(it) }; ModeAndStatus(vm) }
                // Probe again keeps the calibration (the probe stayed in), unless the run lost it (no touch).
                else -> StepResult(vm, run, doneLabel, requireZero, offerAnchor, onAgain = { p.reset(); step = if (p.calibrated.value) 3 else 2 }, onDone = ::finish)
            }
        }
        // While probing: Stop, pinned at the bottom where your thumb is.
        if (running) Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { vm.haptics.press(); p.stop() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(58.dp)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("Halts the machine at once (reset). Unlock afterwards.", fontSize = 11.5.sp, color = Muted)
        }
        // Bottom buttons (step 5 has its own, inside the result).
        if (step in 1..4) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 1) PendantOutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Back", fontSize = 16.sp) }
            when (step) {
                // Next waits until every choice this operation needs is picked, and says which one.
                1 -> { val missing = setup.missingPick(picks)
                    Button(onClick = { step++ }, enabled = missing == null, modifier = Modifier.weight(1f).height(48.dp)) { Text(missing ?: "Next", fontSize = 16.sp) } }
                2, 3 -> Button(onClick = { step++ }, enabled = probeReady, modifier = Modifier.weight(1f).height(48.dp)) { Text("Next", fontSize = 16.sp) }
                4 -> {
                    val modeNow by p.mode.collectAsState()
                    val mode = if (setup.multi) ProbeMode.MEASURE else modeNow
                    val ready = probeReady && status?.isIdle == true && settingsValid(setup) && setup.startProblem(status?.wpos?.getOrNull(2)) == null &&
                        setup.travelCheck(status?.let(::startOf), limits).problem == null
                    Button(onClick = { askRun = true }, enabled = ready, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp))
                        Text(if (mode == ProbeMode.MEASURE) "Measure" else "Run probe", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (askRun) {
        val mode = p.effectiveMode(setup)
        AlertDialog(onDismissRequest = { askRun = false },
            title = { Text("Start probing?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (mode == ProbeMode.MEASURE) "The machine starts moving right away. Your work zero won't change."
                        else "The machine starts moving right away, and this sets your work zero (${zeroAxesText(setup)}).")
                    // Z off: the side depth counts from the work Z0, not from where the probe is parked.
                    if (setup.depthFromZero) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Amber.copy(alpha = 0.14f)).padding(10.dp),
                        verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Warning, null, tint = Amber, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text("Z is off: it goes down from your Z origin, not from where the probe is now. It touches the sides at " +
                            "Z −${mmText(setup.depth)} (${mmText(setup.depth)} mm below Z0). Make sure Z0 is on the top of the part.",
                            fontSize = 13.5.sp, lineHeight = 18.sp, color = Amber)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { askRun = false; vm.haptics.press(); p.start() }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { askRun = false }) { Text("Cancel") } })
    }
    if (info) InfoSheet(step, setup.tool) { info = false }
}

private fun zeroAxesText(s: ProbeSetup) = s.axesText()

/** Where the probe is parked, in machine coordinates (what the run starts from). */
private fun startOf(st: MachineStatus) = Touch(st.mpos[0], st.mpos[1], st.mpos[2])

private fun settingsValid(s: ProbeSetup) = s.x in 0.5..200.0 && s.y in 0.5..200.0 && s.depth in 0.0..30.0 &&
    s.tip in 0.2..12.0 && (!s.multi || lengthProblem(s) == null)

/** Why the multi-point length won't do (null = fine). */
private fun lengthProblem(s: ProbeSetup): String? =
    if (s.edgeLength !in 0.5..300.0) "Total length: 0.5 to 300 mm." else null

// ---- step 1: what ------------------------------------------------------------------------------
@Composable
private fun Tile(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.alpha(if (enabled) 1f else 0.35f).clip(RoundedCornerShape(10.dp)).background(if (selected) TileOn else TileBg)
        .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Label(t: String) = Text(t, fontSize = 12.sp, color = Muted)

/**
 * Setup: the probe (remembered), then the operation and its choices. Nothing but the probe is picked
 * when probing opens; each answer reveals the next question and scrolls it into view, and Next
 * waits for all of them ([Pick], [missingPick]).
 */
@Composable
private fun StepWhat(s: ProbeSetup, picks: Set<Pick>, scroll: ScrollState, requireZero: Boolean, onChange: (ProbeSetup) -> Unit,
                     onPick: (Pick) -> Unit, onUnpick: (Pick) -> Unit) {
    val opPicked = Pick.OPERATION in picks
    // Any answer (a new pick, or a different answer to one already picked) can bring up a row below.
    LaunchedEffect(picks, s.kind, s.edge, s.edgeFromHere, s.edgeMulti, s.corner) {
        if (picks.isNotEmpty()) { kotlinx.coroutines.delay(90); scroll.animateScrollTo(scroll.maxValue) }
    }
    Label("Probe")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProbeTool.entries.forEach { t ->
            Tile(s.tool == t, { onChange(s.withTool(t)) }, Modifier.weight(1f)) {
                ProbeToolArt(t, 40.dp)
                Text(t.label, fontSize = 14.sp, color = if (s.tool == t) MaterialTheme.colorScheme.primary else Color.Unspecified)
            }
        }
    }
    when (s.tool) {
        ProbeTool.ROD -> Text("The probe rod only triggers on conductive (metal) parts.", fontSize = 12.sp, color = Muted)
        ProbeTool.LASER -> Text("The laser probe only senses straight down, so it can only do Top only.", fontSize = 12.sp, color = Muted)
        else -> {}
    }
    Label("Probing operation")
    ProbeKind.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { k ->
                val sel = opPicked && s.kind == k
                Tile(sel, { onChange(s.copy(kind = k).normalized()); onPick(Pick.OPERATION) }, Modifier.weight(1f), enabled = !s.tool.topOnly || k == ProbeKind.TOP) {
                    if (k == ProbeKind.HOLE || k == ProbeKind.BLOCK) Row { ProbeKindArt(k, 60.dp); ProbeKindArt(k, 60.dp, square = true) }
                    else ProbeKindArt(k, 64.dp)
                    Text(k.label, fontSize = 14.sp, color = if (sel) MaterialTheme.colorScheme.primary else Color.Unspecified)
                    if (k == ProbeKind.HOLE || k == ProbeKind.BLOCK) Text("round or square", fontSize = 11.sp, color = Muted)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (!opPicked) return
    if (s.kind.hasCorner) {
        Label("Which corner?")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Corner.BACK_LEFT, Corner.BACK_RIGHT, Corner.FRONT_LEFT, Corner.FRONT_RIGHT).forEach { c ->
                val sel = Pick.CORNER in picks && s.corner == c
                Tile(sel, { onChange(s.copy(corner = c)); onPick(Pick.CORNER) }, Modifier.weight(1f)) {
                    CornerArt(c, s.kind == ProbeKind.INSIDE, 44.dp)
                    Text(c.label, fontSize = 11.sp, color = if (sel) MaterialTheme.colorScheme.primary else Color.Unspecified)
                }
            }
        }
        if (Pick.CORNER !in picks) return
    }
    if (s.kind == ProbeKind.EDGE) { EdgeChoices(s, picks, requireZero, onChange, onPick, onUnpick); return }
    if (s.kind != ProbeKind.TOP) {
        Label("Axes to probe")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // A hole starts inside the hole, so there's no top to touch.
            val axes = listOfNotNull('X', 'Y', 'Z'.takeIf { s.kind.hasTop })
            axes.forEach { a ->
                val on = when (a) { 'X' -> s.doX; 'Y' -> s.doY; else -> s.doZ }
                Tile(on, { onChange(s.toggle(a)) }, Modifier.weight(1f)) {
                    Text((if (on) "✓ " else "") + a, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.primary else Muted)
                }
            }
        }
        if (s.depthFromZero) Text("Z off: it doesn't touch the top. It takes your current Z0 as the top, so set Z first (Top only does that).", fontSize = 12.sp, color = Muted)
    }
}

/**
 * Edge on Setup: which edge, where it starts, 1 or more points, and (more) which way along the edge.
 * Each row shows once the one before it is answered.
 */
@Composable
private fun EdgeChoices(s: ProbeSetup, picks: Set<Pick>, requireZero: Boolean, onChange: (ProbeSetup) -> Unit, onPick: (Pick) -> Unit,
                        onUnpick: (Pick) -> Unit) {
    // Opened for a zero (Run's origin, an anchor): multiple points only measure, so they aren't offered.
    LaunchedEffect(requireZero, s.edgeMulti) { if (requireZero && s.edgeMulti) onChange(s.copy(edgeMulti = false)) }
    val primary = MaterialTheme.colorScheme.primary
    @Composable fun name(t: String, sel: Boolean) = Text(t, fontSize = 13.5.sp, color = if (sel) primary else Color.Unspecified)
    @Composable fun sub(t: String) = Text(t, fontSize = 11.sp, color = Muted, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

    Label("Which edge?")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Side.BACK, Side.FRONT, Side.LEFT, Side.RIGHT).forEach { e ->
            val sel = Pick.SIDE in picks && s.edge == e
            // Another edge turns "which way" into a different pair (back / front ↔ left / right): ask again.
            Tile(sel, { if (e.along != s.edge.along) onUnpick(Pick.DIRECTION); onChange(s.copy(edge = e)); onPick(Pick.SIDE) }, Modifier.weight(1f)) {
                EdgeArt(e, 44.dp)
                Text(e.label, fontSize = 11.sp, color = if (sel) primary else Color.Unspecified)
            }
        }
    }
    if (Pick.SIDE !in picks) return

    Label("Where does it start?")
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(false to ("Touch the top first" to "over the part, touches the top"),
            true to ("Start from here" to "beside the part, at touch height")).forEach { (here, t) ->
            val sel = Pick.START in picks && s.edgeFromHere == here
            Tile(sel, { onChange(s.copy(edgeFromHere = here)); onPick(Pick.START) }, Modifier.weight(1f).fillMaxHeight()) {
                StartArt(here, s.tool, Modifier.size(width = 78.dp, height = 46.dp))
                name(t.first, sel); sub(t.second)
            }
        }
    }
    if (Pick.START !in picks) return

    Label("Touches")
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOfNotNull(false to ("1 point" to "sets ${s.edge.axis}0 on the edge"), (true to ("Multiple points" to "measure only, sets nothing")).takeIf { !requireZero }).forEach { (more, t) ->
            val sel = Pick.POINTS in picks && s.edgeMulti == more
            Tile(sel, { onChange(s.copy(edgeMulti = more)); onPick(Pick.POINTS) }, Modifier.weight(1f).fillMaxHeight()) { name(t.first, sel); sub(t.second) }
        }
    }
    if (requireZero) Text("Multiple points only measure, so they aren't offered here: this needs a zero.", fontSize = 12.sp, color = Muted)
    if (Pick.POINTS !in picks || !s.multi) return

    // The picture turns with the edge and shows the way the touches go once it's picked.
    Label("Which way along the edge?")
    val wayPicked = Pick.DIRECTION in picks
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TileBg).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111A2B)).padding(4.dp)) {
            EdgePointsArt(s.edge, s.alongSign, 4, Modifier.fillMaxSize(), showWay = wayPicked)
        }
        Column(Modifier.width(118.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Back first for a left / right edge (it's the top of the drawing), left first for back / front.
            val signs = if (s.edge.along == 'Y') listOf(1, -1) else listOf(-1, 1)
            signs.forEach { sign ->
                val sel = wayPicked && s.alongSign == sign
                val arrow = when (s.alongWord(sign)) { "right" -> "→"; "left" -> "←"; "back" -> "↑"; else -> "↓" }
                Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) TileOn else Color(0xFF243147))
                    .border(1.dp, if (sel) primary else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onChange(s.copy(alongSign = sign)); onPick(Pick.DIRECTION) }, contentAlignment = Alignment.Center) {
                    Text("$arrow ${s.alongWord(sign).replaceFirstChar { it.uppercase() }}", fontSize = 14.sp, color = if (sel) primary else Color.Unspecified)
                }
            }
        }
    }
}

// ---- step 2: settings ------------------------------------------------------------------------
@Composable
private fun StepSettings(vm: AppViewModel, s: ProbeSetup, onChange: (ProbeSetup) -> Unit) {
    // An edge has more rows (and its own pictures on each): no big picture, so they all fit.
    if (s.kind != ProbeKind.EDGE) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ProbeKindArt(s.kind, 96.dp, square = false)
    }
    if (!s.doSides) {
        Text("Only the top: it touches straight down, so there's nothing to set.", fontSize = 14.sp, color = Muted)
        return
    }
    Text("Distances are from where the probe is now.", fontSize = 12.5.sp, color = Muted)
    val edge = s.kind == ProbeKind.EDGE
    // Started beside the part it only goes in, so the distance is how far it searches for the edge.
    val what = if (s.fromHere) "search distance" else "start offset"
    if (s.doX) NumField(FieldArt.X, s.tool, "X $what", s.x, 0, s.kind, side = s.edge.takeIf { edge }, fromHere = s.fromHere) { onChange(s.copy(x = it)) }
    if (s.doY) NumField(FieldArt.Y, s.tool, "Y $what", s.y, 0, s.kind, side = s.edge.takeIf { edge }, fromHere = s.fromHere) { onChange(s.copy(y = it)) }
    if (s.multi) EdgePointsSettings(vm, s, onChange)
    if (s.kind != ProbeKind.HOLE) {
        if (s.fromHere) OffField(FieldArt.DEPTH, s.tool, "Side depth", "Off: touches at the probe's current height")
        else NumField(FieldArt.DEPTH, s.tool, if (s.doZ) "Side depth" else "Side depth below Z0", s.depth, 3) { onChange(s.copy(depth = it)) }
    }
    NumField(FieldArt.TIP, s.tool, "Tip diameter", s.tip, 3) { onChange(s.copy(tip = it)) }
}

/**
 * Edge, multiple points: how many and over how long, side by side with one picture of the touches;
 * under them the spacing that works out to, and whether every touch is inside the machine's travel
 * (the ones that aren't turn red in the picture).
 */
@Composable
private fun EdgePointsSettings(vm: AppViewModel, s: ProbeSetup, onChange: (ProbeSetup) -> Unit) {
    val st by vm.link.status.collectAsState()
    val lim by vm.softLimits.collectAsState()
    val check = s.travelCheck(st?.let(::startOf), lim)
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 98.dp, height = 100.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF111A2B))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).padding(5.dp)) {
                EdgePointsArt(s.edge, s.alongSign, s.touchCount, Modifier.fillMaxSize(), check.bad)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Points", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Stepper(s.touchCount, ProbeSetup.MIN_POINTS..ProbeSetup.MAX_POINTS) { onChange(s.copy(edgeCount = it)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Total length", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    MmBox(s.edgeLength, 0, Modifier.width(96.dp)) { onChange(s.copy(edgeLength = it)) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Probe spacing: ", fontSize = 13.sp, color = Muted)
            Text(mmText(kotlin.math.round(s.spacing * 1000) / 1000.0), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = if (check.problem == null) primary else ErrFg)
            Text(" mm", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
            if (check.problem == null && st != null) Text("✓ inside travel", fontSize = 12.5.sp, color = Green)
        }
        check.problem?.let { Text(it, fontSize = 12.5.sp, color = ErrFg, lineHeight = 16.sp) }
        lengthProblem(s)?.let { Text(it, fontSize = 12.5.sp, color = ErrFg) }
        // The angle is only as fine as the probe's repeat over the stretch the touches cover.
        val noise = com.z1pendant.probe.EdgeFit.noiseDegrees(s.alongOffsets().map { kotlin.math.abs(it) })
        if (lengthProblem(s) == null && noise >= 0.02) Text("Over ${mmText(s.edgeLength)} mm the angle is good to about ±${Fmt.fixed(noise, 2)}°: a longer length gives a finer angle.",
            fontSize = 11.5.sp, color = Amber, lineHeight = 15.sp)
    }
}

/** − n + for a small whole number in [range]. */
@Composable
private fun Stepper(value: Int, range: IntRange, onValue: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.height(42.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF5B6474), RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically) {
        @Composable fun btn(t: String, enabled: Boolean, v: Int) =
            Box(Modifier.width(36.dp).fillMaxHeight().clickable(enabled = enabled) { onValue(v) }, contentAlignment = Alignment.Center) {
                Text(t, fontSize = 20.sp, color = if (enabled) primary else Color(0xFF475569))
            }
        btn("−", value > range.first, value - 1)
        Text("$value", fontSize = 17.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        btn("+", value < range.last, value + 1)
    }
}

/** A setting that doesn't apply here: its picture greyed, the reason in place of the number (nothing shifts). */
@Composable
private fun OffField(art: FieldArt, tool: ProbeTool, label: String, why: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(width = 98.dp, height = 78.dp).alpha(0.4f).clip(RoundedCornerShape(10.dp)).background(TileBg)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).padding(5.dp)) {
            FieldArtImage(art, Modifier.fillMaxSize(), tool)
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = Muted)
            Text(why, fontSize = 12.5.sp, color = Amber, lineHeight = 16.sp)
        }
    }
}

/** A setting with its drawing in a tile; [decimals] = how it's shown once you leave the box (0 = as typed). */
@Composable
private fun NumField(art: FieldArt, tool: ProbeTool, label: String, value: Double, decimals: Int,
                     kind: ProbeKind = ProbeKind.OUTSIDE, side: Side? = null, fromHere: Boolean = false, onValue: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(width = 98.dp, height = 78.dp).clip(RoundedCornerShape(10.dp)).background(TileBg)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).padding(5.dp)) {
            FieldArtImage(art, Modifier.fillMaxSize(), tool, kind, side, fromHere)
        }
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        MmBox(value, decimals, Modifier.width(108.dp), onValue)
    }
}

/**
 * The number box: compact and rounded like the mock-up (Material's text field is tall and
 * square-cornered), the number right-aligned, "mm" small on its baseline, blue border while editing.
 */
@Composable
private fun MmBox(value: Double, decimals: Int, modifier: Modifier, onValue: (Double) -> Unit) {
    fun show(v: Double) = if (decimals > 0) Fmt.fixed(v, decimals) else mmText(v)
    var text by remember { mutableStateOf(show(value)) }
    var focused by remember { mutableStateOf(false) }
    val bad = text.toDoubleOrNull() == null
    val primary = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.text.BasicTextField(text, { t ->
        text = t.replace(',', '.')
        text.toDoubleOrNull()?.let(onValue)
    }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.End),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) text.toDoubleOrNull()?.let { v -> text = show(v) }
        },
        decorationBox = { inner ->
            Row(Modifier.height(42.dp).clip(RoundedCornerShape(10.dp))
                .border(if (focused || bad) 2.dp else 1.dp, if (bad) ErrFg else if (focused) primary else Color(0xFF5B6474), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f).alignByBaseline(), contentAlignment = Alignment.CenterEnd) { inner() }
                    Spacer(Modifier.width(4.dp))
                    Text("mm", fontSize = 12.sp, color = Muted, modifier = Modifier.alignByBaseline())
                }
            }
        })
}

// ---- step 3: probe in, calibrated ------------------------------------------------------------
@Composable
private fun CheckRow(state: Int, title: String, sub: String?, dim: Boolean = false, body: @Composable ColumnScope.() -> Unit = {}) {
    // state: 0 waiting, 1 now, 2 done, 3 warning
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            val (ic, tint) = when (state) {
                2 -> Icons.Filled.CheckCircle to Green
                1 -> Icons.Filled.RadioButtonChecked to MaterialTheme.colorScheme.primary
                3 -> Icons.Filled.Warning to Amber
                else -> Icons.Filled.RadioButtonUnchecked to Color(0xFF475569)
            }
            Icon(ic, null, tint = tint, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (dim) Color(0xFF64748B) else Color.Unspecified)
                sub?.let { Text(it, fontSize = 12.5.sp, color = Muted, lineHeight = 17.sp) }
            }
        }
        body()
    }
}

@Composable
private fun SmallButton(text: String, icon: (@Composable () -> Unit)?, outlined: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        icon?.let { it(); Spacer(Modifier.width(6.dp)) }; Text(text, fontSize = 15.sp)
    }
    if (outlined) PendantOutlinedButton(onClick, Modifier.fillMaxWidth().height(42.dp), enabled = enabled, content = content)
    else Button(onClick, Modifier.fillMaxWidth().height(42.dp), enabled = enabled, content = content)
}

@Composable
private fun StepCheck(vm: AppViewModel) {
    val p = vm.probe
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val confirmed by p.confirmed.collectAsState()
    val calibrated by p.calibrated.collectAsState()
    val action by p.toolAction.collectAsState()
    val len by p.lengthCheck.collectAsState()
    val setup by p.setup.collectAsState()
    val name = setup.tool.label
    val tool = st?.tool
    val connected = conn == ConnState.Connected
    val idle = st?.isIdle == true
    val acting = action?.let { it.stage != ToolAction.Stage.DONE && it.error == null } == true
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    action?.error?.let { e ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ErrBg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Error, null, tint = ErrFg); Spacer(Modifier.width(8.dp))
            Text(e, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { p.dismissToolError() }) { Text("OK") }
        }
        if (st?.isAlarm == true) SmallButton("Unlock", { Icon(Icons.Filled.LockOpen, null) }) { vm.link.dialect.unlock().forEach { vm.link.sendLine(it) } }
    }

    // Why the buttons below are off (2026-11-18: the Pixel had left the machine's Wi-Fi, and the panel
    // only said "The spindle is empty" next to a greyed Load button).
    if (!acting && action?.error == null && (!connected || !idle)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WarnBg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Error, null, tint = WarnFg); Spacer(Modifier.width(8.dp))
            Text(when {
                !connected -> "Not connected to the machine (${conn.name.lowercase()}). Loading and calibrating need the link; check the phone is on the machine's Wi-Fi."
                else -> "The machine is ${st?.state ?: "busy"}, not Idle. Loading and calibrating wait until it's Idle."
            }, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
    }

    // Row 1: the probe is in the spindle.
    when {
        action?.load == true && acting -> CheckRow(1, "$name in the spindle", "Loading…") {
            Progress(action!!.stage, load = true, setup.tool.noun)
            if (action!!.stage == ToolAction.Stage.WAITING) SmallButton("I've swapped it · continue", null, outlined = true) { p.continueToolChange() }
        }
        tool == PROBE_TOOL && confirmed -> CheckRow(2, "$name in the spindle", "T$PROBE_TOOL")
        tool == PROBE_TOOL -> CheckRow(1, "$name in the spindle", "The machine says a probe (T$PROBE_TOOL) is in. The number can be left over from last time: is the ${setup.tool.noun} in?") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { p.confirmed.value = true }, modifier = Modifier.weight(1f).height(42.dp)) { Text("Yes, it's in") }
                PendantOutlinedButton(onClick = {
                    // M6 T9999 does nothing while the machine already says T9999: mark the spindle empty first.
                    vm.link.sendLine("M493.2 T-1"); p.loadProbe()
                }, enabled = connected && idle, modifier = Modifier.weight(1f).height(42.dp)) { Text("No, load it") }
            }
        }
        else -> CheckRow(3, "$name in the spindle", if (tool == null) "The machine hasn't reported a tool." else if (tool < 0) "The spindle is empty." else "The machine has T$tool in.") {
            SmallButton("Load the ${setup.tool.noun}", { Icon(Icons.Filled.SwapHoriz, null) }, enabled = connected && idle) { p.loadProbe() }
            Text("Moves to the tool change spot and waits while you swap. Then it calibrates the probe on the tool setter and checks it triggers.",
                fontSize = 11.5.sp, color = Muted, lineHeight = 15.sp)
        }
    }

    // Row 2: calibrated on the tool setter.
    val probeIn = tool == PROBE_TOOL && confirmed
    when {
        action?.load == false && acting -> CheckRow(1, "Calibrated on the tool setter", "Calibrating…") { Progress(action!!.stage, load = false) }
        !probeIn -> CheckRow(0, "Calibrated on the tool setter", null, dim = true)
        calibrated -> CheckRow(2, "Calibrated on the tool setter", "Probe triggered on the setter") {
            SmallButton("Calibrate again", { CalibrateIcon(primary) }, outlined = true, enabled = connected && idle) { p.calibrate() }
        }
        else -> CheckRow(1, "Calibrated on the tool setter", "Every time you probe: it checks the probe triggers and which probe is in.") {
            SmallButton("Calibrate now", { CalibrateIcon(onPrimary) }, enabled = connected && idle) { p.calibrate() }
            Text("The machine moves over the tool setter and lowers the probe onto it.", fontSize = 11.5.sp, color = Muted)
        }
    }
    // Row 3: it's the probe you picked, going by where it trips the setter.
    if (calibrated && !acting) len?.let { LengthRow(it, p, setup.kind, connected && idle) }
}

private fun lenName(t: ProbeTool) = t.noun
/** "3D Probe", "Probe Rod", "Laser Probe": for status lines like "3D Probe Confirmed". */
private fun titleName(t: ProbeTool) = t.label.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/** Which probe the setter says is in: each probe trips it at its own Z (learned on its first calibration). */
@Composable
private fun LengthRow(c: ProbeLength.Check, p: com.z1pendant.probe.ProbeController, kind: ProbeKind, canMove: Boolean) {
    val z = Fmt.fixed(c.z, 3)
    val exp = c.expected?.let { Fmt.fixed(it, 3) }
    val load: @Composable () -> Unit = {
        SmallButton("Load the ${lenName(c.tool)}", { Icon(Icons.Filled.SwapHoriz, null) }, enabled = canMove) { p.reloadProbe() }
    }
    when (c.result) {
        ProbeLength.Result.MATCH -> CheckRow(2, "${titleName(c.tool)} Confirmed", "Setter Z $z, learned $exp")
        ProbeLength.Result.LEARNED -> CheckRow(2, "${c.tool.label} length learned",
            "It tripped the setter at Z $z. From now on the app checks the ${lenName(c.tool)} reads the same, so a wrong probe gets caught.")
        // The reading is another probe's: swap in the one you picked, or use the one that's in if it
        // can do this operation (all three can do Top only; the laser probe nothing else).
        ProbeLength.Result.LOOKS_LIKE -> CheckRow(3, "${titleName(c.other!!)} Detected",
            "It tripped the setter at Z $z, like the ${lenName(c.other)} (${Fmt.fixed(p.learned()[c.other] ?: c.z, 3)}). " +
                "You picked the ${lenName(c.tool)}.") {
            load()
            if (c.other.canDo(kind)) SmallButton("Use the ${lenName(c.other)} instead", null, outlined = true) { p.switchToMatch() }
            else Text("The ${lenName(c.other)} can't do ${kind.label.lowercase()}: swap in the ${lenName(c.tool)}.", fontSize = 11.5.sp, color = Muted)
        }
        ProbeLength.Result.UNKNOWN -> CheckRow(3, "Doesn't match the ${lenName(c.tool)}",
            "Setter Z $z, learned $exp (${Fmt.fixed(kotlin.math.abs(c.z - (c.expected ?: c.z)), 2)} mm off). " +
                "A different probe, or it's not seated the same way in the collet.") {
            load()
            SmallButton("Confirm ${titleName(c.tool)}", null, outlined = true) { p.rememberLength() }
        }
        ProbeLength.Result.NEW -> CheckRow(3, "${c.tool.label} not learned yet", "It tripped the setter at Z $z.") {
            load()
            SmallButton("Remember this length for the ${lenName(c.tool)}", null, outlined = true) { p.rememberLength() }
        }
    }
}

@Composable
private fun Progress(stage: ToolAction.Stage, load: Boolean, name: String = "probe") {
    val steps = buildList {
        if (load) { add(ToolAction.Stage.MOVING to "Moving to the tool change spot"); add(ToolAction.Stage.WAITING to "Swap in the $name and plug in its cable, then press the button on the machine") }
        else add(ToolAction.Stage.MOVING to "Moving over the tool setter")
        add(ToolAction.Stage.CALIBRATING to "Calibrate on the tool setter")
        add(ToolAction.Stage.CHECKING to "Check the probe triggers")
    }
    Column(Modifier.padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { (s, t) ->
            val done = s.ordinal < stage.ordinal; val now = s == stage
            Row(verticalAlignment = Alignment.Top) {
                when {
                    done -> Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
                    now -> Box(Modifier.padding(3.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
                    else -> Box(Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(t, fontSize = 12.5.sp, lineHeight = 16.sp, color = when { now -> Color.Unspecified; done -> Muted; else -> Color(0xFF64748B) },
                    fontWeight = if (now) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

// ---- step 4: position ------------------------------------------------------------------------
@Composable
private fun MiniReadout(st: MachineStatus?, connected: Boolean, selected: Char?, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (Char) -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 2.dp)) {
        listOf('X', 'Y', 'Z').forEachIndexed { i, a ->
            val sel = a == selected
            Row(Modifier.fillMaxWidth().clickable { onSelect(a) }
                .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (sel) MaterialTheme.colorScheme.primary else Muted, modifier = Modifier.width(26.dp))
                Text(fmtPos(st?.wpos?.getOrNull(i), connected), fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

private fun hint(s: ProbeSetup): String {
    // With Z off the work Z0 stands in for the top: any height above it is fine.
    val above = if (s.depthFromZero) "anywhere" else "about 5 mm"
    val noZ = if (s.depthFromZero) " It touches the sides at Z −${mmText(s.depth)} (${mmText(s.depth)} mm below your Z0)." else ""
    val near = if (s.doX && s.doY) "corner" else "${sideName(s)} side"
    return when (s.kind) {
        ProbeKind.OUTSIDE -> "Jog the ball $above above the part, about 5 mm in from the ${s.corner.label} $near."
        ProbeKind.INSIDE -> "Jog the ball $above above the top, over the part just outside the pocket's ${s.corner.label} corner."
        ProbeKind.HOLE -> "Jog the ball into the hole, roughly centered, at least 2 mm below its edge."
        ProbeKind.BLOCK -> "Jog the ball $above above the block, roughly over its center."
        ProbeKind.TOP -> "Jog the ball about 5 mm above where you want Z0."
        // Only what Setup decided: the numbers (how long a stretch) come on the next screen.
        ProbeKind.EDGE -> (if (s.fromHere) "Jog the ball beside the ${s.edge.label} edge, a few mm out from it and at the height you want it to touch."
            else "Jog the ball about 5 mm above the part, about 5 mm in from the ${s.edge.label} edge.") +
            (if (s.multi) " It touches toward the ${s.alongWord()} from there, so start near the ${s.alongWord(-s.alongSign)} end of the edge." else "")
    } + noZ
}

/** The part's side an X-only / Y-only corner probe touches, seen from the front. */
private fun sideName(s: ProbeSetup): String = when {
    s.doX -> if (s.corner.left) "left" else "right"
    else -> if (s.corner.back) "back" else "front"
}

@Composable
private fun StepPosition(vm: AppViewModel, s: ProbeSetup, probeReady: Boolean, onMap: () -> Unit) {
    val p = vm.probe
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val axis by vm.axis.collectAsState()
    val padMode by vm.jogPad.collectAsState()
    val stepIdx by vm.stepIndex.collectAsState()
    val degPerClick by vm.degPerClick.collectAsState()
    val mode by p.mode.collectAsState()
    val connected = conn == ConnState.Connected
    val stepList = vm.steps
    val si = stepIdx.coerceIn(0, stepList.lastIndex)

    MiniReadout(st, connected, if (padMode) null else axis) { vm.selectAxis(it); vm.haptics.press() }
    Segmented(stepList.map { stepLabel(it) }, si) { vm.selectStep(it); vm.haptics.press() }
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        if (!padMode) Handwheel(degPerClick = degPerClick, axis = axis,
            stepText = "${stepLabel(stepList[si])}${if (axis == 'A') "°" else "mm"}/click",
            enabled = connected, onClick = { vm.wheelClicks(it) }, modifier = Modifier.size(210.dp).align(Alignment.Center))
        else JogPad(vm, step = stepList[si], enabled = connected, modifier = Modifier.align(Alignment.Center))
        ModeButton(padMode, Modifier.align(Alignment.TopStart)) { vm.setJogPad(!padMode) }
        // The bed map, to get there quicker. Dial: bottom-right, as on the main screen. The pad here
        // starts at the top (no free row beside the switch), so its empty bottom-left corner.
        MapButton(if (padMode) Modifier.align(Alignment.BottomStart) else Modifier.align(Alignment.BottomEnd), onMap)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TileBg)) {
        Box(Modifier.width(4.dp).height(56.dp).background(MaterialTheme.colorScheme.primary))
        Text(hint(s), fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(10.dp))
    }
    // What you picked on Setup, nothing more: the numbers are set on the next screen (Jimmy, 2026-11-27),
    // so no settings summary and no edit link here (Back goes to Setup).
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TileBg).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ProbeKindArt(s.kind, 44.dp); Spacer(Modifier.width(10.dp))
        Text("${s.tool.label} · " + (if (s.multi) "Edge · ${s.edge.label} · multiple points" else s.title()),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
    if (!connected) Text("Not connected.", fontSize = 12.sp, color = Amber)
    else if (!probeReady) Text("The probe isn't loaded and calibrated: go back to Check the probe.", fontSize = 12.sp, color = Amber)
}

/** Under the settings: Makera's own routine (sets the zero) or the pendant's touches (measure only), and why Run is off. */
@Composable
private fun ModeAndStatus(vm: AppViewModel) {
    val p = vm.probe
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val mode by p.mode.collectAsState()
    val connected = conn == ConnState.Connected
    Spacer(Modifier.height(4.dp))
    // Several touches along an edge only measure: there's no one point that would be the zero.
    if (p.setup.value.multi) Text("Multiple points only measures: your work zero won't change.", fontSize = 12.5.sp, color = Muted,
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    else Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(ProbeMode.SET_ZERO to ("Set work zero" to Icons.Filled.MyLocation), ProbeMode.MEASURE to ("Measure only" to Icons.Filled.SquareFoot)).forEach { (m, li) ->
            val sel = mode == m
            Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(if (sel) Color(0xFF173A55) else Color.Transparent)
                .clickable { p.mode.value = m; vm.haptics.press() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(li.second, null, tint = if (sel) MaterialTheme.colorScheme.primary else Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(li.first, fontSize = 13.5.sp, color = if (sel) MaterialTheme.colorScheme.primary else Muted, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
    val ok by p.confirmed.collectAsState(); val cal by p.calibrated.collectAsState()
    when {
        !connected -> Text("Not connected.", fontSize = 12.sp, color = Amber)
        p.toolAction.value?.let { it.stage != ToolAction.Stage.DONE && it.error == null } == true ->
            Text("The probe is being loaded or calibrated: wait for it to finish.", fontSize = 12.sp, color = Muted)
        st?.tool != PROBE_TOOL || !ok || !cal -> Text("The probe isn't loaded and calibrated: go back to Check the probe.", fontSize = 12.sp, color = Amber)
        p.lengthCheck.value?.ok == false -> Text("The probe in the spindle doesn't look like the ${p.setup.value.tool.noun}: go back to Check the probe.", fontSize = 12.sp, color = Amber)
        st?.isIdle != true -> Text("Waiting for the machine to be Idle (${st?.state}).", fontSize = 12.sp, color = Muted)
        p.setup.value.startProblem(st?.wpos?.getOrNull(2)) != null ->
            Text(p.setup.value.startProblem(st?.wpos?.getOrNull(2))!!, fontSize = 12.sp, color = Amber)
    }
}

/** A distance as typed: 20 rather than 20.0, 12.5 stays 12.5. */
private fun mmText(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else Fmt.trimmed4(v)

// ---- step 5: result --------------------------------------------------------------------------
@Composable
private fun StepResult(vm: AppViewModel, run: RunState, doneLabel: String, requireZero: Boolean, offerAnchor: Boolean, onAgain: () -> Unit,
                       onDone: (zeroSet: Set<Char>) -> Unit) {
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val connected = conn == ConnState.Connected
    when (run) {
        is RunState.Running -> {
            // Option E: what you're probing, the run from above, the step list, the position as
            // chips. Stop is pinned at the bottom of the screen (see ProbeWizard).
            val setup by vm.probe.setup.collectAsState()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(setup.title(), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(if (run.setMode) "Set work zero" else "Measure only", fontSize = 13.sp, color = Muted)
            }
            ProbeRunArt(setup, run.labels, run.index, Modifier.fillMaxWidth().height(240.dp), stepTouch = run.touch)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TileBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                run.labels.forEachIndexed { i, label ->
                    val done = i < run.index; val now = i == run.index
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            done -> Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(22.dp))
                            now -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
                            }
                            else -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).border(2.dp, Color(0xFF334155), RoundedCornerShape(11.dp)))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(label, fontSize = 15.sp, fontWeight = if (now) FontWeight.Medium else FontWeight.Normal,
                            color = when { now -> Color.Unspecified; done -> Muted; else -> Color(0xFF64748B) }, modifier = Modifier.weight(1f))
                        if (done) doneNote(label, run.setMode)?.let { Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                listOf('X', 'Y', 'Z').forEachIndexed { i, a ->
                    Row(Modifier.clip(RoundedCornerShape(50)).background(TileBg).padding(horizontal = 11.dp, vertical = 5.dp)) {
                        Text("$a ", fontSize = 13.sp, color = Muted)
                        Text(fmtPos(st?.mpos?.getOrNull(i), connected).trim(), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCBD5E1))
                    }
                }
            }
        }
        is RunState.Done -> ResultView(vm, run.result, st, connected, doneLabel, requireZero, offerAnchor, onAgain, onDone)
        is RunState.Failed -> {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ErrBg).padding(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Error, null, tint = ErrFg, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp))
                Text(run.reason, fontSize = 15.sp, lineHeight = 20.sp)
            }
            CheckRowIcon(Icons.Filled.Info, "What changed", run.changed)
            run.hint?.let { CheckRowIcon(Icons.Filled.Help, "Likely cause", it) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PendantOutlinedButton(onClick = { onDone(emptySet()) }, modifier = Modifier.weight(1f).height(48.dp)) { Text(if (requireZero) "Back" else doneLabel, fontSize = 16.sp) }
                if (st?.isAlarm == true) Button(onClick = { vm.link.dialect.unlock().forEach { vm.link.sendLine(it) }; onAgain() }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Filled.LockOpen, null); Spacer(Modifier.width(4.dp)); Text("Unlock, retry", fontSize = 16.sp)
                } else Button(onClick = onAgain, modifier = Modifier.weight(1f).height(48.dp)) { Text("Try again", fontSize = 16.sp) }
            }
        }
        RunState.Idle -> Text("Nothing running.", color = Muted)
    }
}

@Composable
private fun CheckRowIcon(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Column { Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium); Text(text, fontSize = 13.sp, color = Muted, lineHeight = 17.sp) }
    }
}

@Composable
private fun Cells(cells: List<Pair<String, Double?>>) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TileBg).padding(10.dp)) {
        cells.forEach { (l, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l, fontSize = 11.sp, color = Muted)
                Text(v?.let { Fmt.fixed(it, 3) } ?: "—", fontSize = 17.sp, fontFamily = FontFamily.Monospace, color = if (v == null) Color(0xFF475569) else Color.Unspecified)
            }
        }
    }
}

@Composable
private fun ResultView(vm: AppViewModel, r: ProbeResult, st: MachineStatus?, connected: Boolean, doneLabel: String, requireZero: Boolean,
                       offerAnchor: Boolean, onAgain: () -> Unit, onDone: (zeroSet: Set<Char>) -> Unit) {
    val s = r.setup
    var undone by remember { mutableStateOf(false) }
    // Picked to start with: every axis the run measured, the same set Makera's own routine writes in
    // Set work zero (corners and block center set X, Y and Z; a hole has no top, so X and Y). Untick
    // Z when Z0 belongs on another surface (Jimmy, 2026-11-18).
    var setAxes by remember { mutableStateOf(listOfNotNull('X'.takeIf { r.zeroX != null }, 'Y'.takeIf { r.zeroY != null },
        'Z'.takeIf { r.top != null }).toSet()) }
    var didSet by remember { mutableStateOf(false) }
    val center = s.kind == ProbeKind.HOLE || s.kind == ProbeKind.BLOCK
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(8.dp))
        Text(if (r.mode == ProbeMode.MEASURE) "Measured. Your work zero hasn't changed." else "Work zero is set. ${whereText(r)}",
            fontSize = 15.sp, lineHeight = 20.sp)
    }
    if (r.mode == ProbeMode.SET_ZERO) {
        Label("Where the probe is now (work position)")
        // Narrower here (nothing to tap), so the letters sit close to their values.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MiniReadout(st, connected, null, Modifier.fillMaxWidth(0.62f)) {} }
    } else {
        Label("From your current zero")
        val at1 = if (s.multi) " (touch 1)" else ""
        Cells(listOfNotNull(
            ((if (center) "Center X" else if (s.doY) "Corner X" else "Edge X$at1") to r.zeroX).takeIf { s.doX },
            ((if (center) "Center Y" else if (s.doX) "Corner Y" else "Edge Y$at1") to r.zeroY).takeIf { s.doY },
            ("Top Z" to r.top).takeIf { s.doZ }))
    }
    if (center) {
        Label("Measured from the touches")
        Cells(listOf("Width in X" to r.widthX, "Width in Y" to r.widthY))
    }
    r.fit?.let { EdgeFitView(r, it) }
    if (r.mode == ProbeMode.SET_ZERO && ('X' in r.zeroSet || 'Y' in r.zeroSet) && !undone) {
        Row(Modifier.clickable { vm.probe.undoXY(r); undone = true; vm.haptics.press() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Undo, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp))
            Text("Put the old ${r.zeroSet.filter { it != 'Z' }.joinToString(" ")} zero back", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        }
    }
    if (undone) Text("The old X Y zero is back.", fontSize = 13.sp, color = Muted)
    if (r.mode == ProbeMode.MEASURE && !didSet && !s.multi) {
        val avail = listOfNotNull('X'.takeIf { r.zeroX != null }, 'Y'.takeIf { r.zeroY != null }, 'Z'.takeIf { r.top != null })
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Column { Text("Set as work zero?", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Pick the axes, then Set. Leave it if you only wanted to measure.", fontSize = 12.5.sp, color = Muted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 30.dp)) {
                avail.forEach { a ->
                    val on = a in setAxes
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) TileOn else Color.Transparent)
                        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                        .clickable { setAxes = if (on) setAxes - a else setAxes + a }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text((if (on) "✓ " else "") + a, color = if (on) MaterialTheme.colorScheme.primary else Muted, fontSize = 15.sp)
                    }
                }
            }
            Button(onClick = { vm.probe.setZero(r, setAxes); didSet = true; vm.haptics.press() }, enabled = setAxes.isNotEmpty() && connected && st?.isIdle == true,
                modifier = Modifier.fillMaxWidth().height(42.dp)) {
                Icon(Icons.Filled.MyLocation, null); Spacer(Modifier.width(6.dp))
                Text("Set ${listOf('X', 'Y', 'Z').filter { it in setAxes }.joinToString(" ")} zero here")
            }
        }
    }
    if (didSet) Text("Work zero set on ${listOf('X', 'Y', 'Z').filter { it in setAxes }.joinToString(" ")}.", fontSize = 13.sp, color = Green)
    // An X or Y work zero came out of this run (and wasn't put back).
    val xySet = (r.mode == ProbeMode.SET_ZERO && ('X' in r.zeroSet || 'Y' in r.zeroSet) && !undone) ||
        (didSet && ('X' in setAxes || 'Y' in setAxes))
    if (requireZero && !xySet) Text("Set the X/Y work zero to go on.", fontSize = 13.sp, color = Muted)
    // The axes whose work zero this run left set: Set mode's (minus X/Y if put back), plus any set after Measure.
    val axes = (if (r.mode == ProbeMode.SET_ZERO) (if (undone) r.zeroSet - 'X' - 'Y' else r.zeroSet) else emptySet()) +
        (if (didSet) setAxes else emptySet())
    // Save this spot as a custom anchor right here, instead of going out to Settings › Anchors.
    // Not from the Anchors screen (it's already making one) and not for multiple points (a measurement).
    if (offerAnchor && !s.multi && (r.zeroX != null || r.zeroY != null || axes.any { it in "XY" })) SaveAnchorRow(vm, r, axes)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PendantOutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f).height(48.dp)) { Text("Probe again", fontSize = 16.sp) }
        Button(onClick = { onDone(axes) }, enabled = !requireZero || xySet, modifier = Modifier.weight(1f).height(48.dp)) {
            Text(if (!requireZero && r.mode == ProbeMode.MEASURE && !didSet && !s.multi) "$doneLabel, don't set" else doneLabel, fontSize = 16.sp)
        }
    }
}

/**
 * "Save as anchor" on the result: a name, and which of X, Y, Z to keep (all the probe found are on
 * to start; Jimmy, 2026-11-27), with the values that will be saved. If the run set the work zero it's
 * that zero, read back from the machine when the box opens; otherwise the measured point.
 */
@Composable
private fun SaveAnchorRow(vm: AppViewModel, r: ProbeResult, zeroAxes: Set<Char>) {
    var asking by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var point by remember { mutableStateOf<com.z1pendant.run.AnchorsController.Point?>(null) }
    var keep by remember { mutableStateOf(setOf('X', 'Y', 'Z')) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    saved?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("Saved as anchor \"$it\".", fontSize = 13.sp, color = Green)
        }
        return
    }
    PendantOutlinedButton(onClick = {
        name = ""; problem = null; point = null; keep = setOf('X', 'Y', 'Z'); asking = true
        scope.launch {
            val m = r.machine
            point = vm.anchors.probedPoint(zeroAxes, m.zeroX.takeIf { r.zeroX != null }, m.zeroY.takeIf { r.zeroY != null })
            if (point == null) problem = "Couldn't read the work zero from the machine."
        }
    }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
        Icon(Icons.Filled.AddLocationAlt, null); Spacer(Modifier.width(6.dp)); Text("Save as anchor", fontSize = 15.sp)
    }
    if (asking) {
        val p = point
        val have = listOf('X' to p?.x, 'Y' to p?.y, 'Z' to p?.z)
        val kept = have.filter { (a, v) -> v != null && a in keep }.map { it.first }.toSet()
        AlertDialog(onDismissRequest = { asking = false },
            title = { Text("Save as anchor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it; problem = null }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Save these axes", fontSize = 13.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        have.forEach { (a, v) ->
                            val found = v != null; val on = found && a in keep
                            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(50)).background(if (on) TileOn else Color.Transparent)
                                .border(1.5.dp, when { on -> MaterialTheme.colorScheme.primary; found -> Color(0xFF6B7280); else -> Color(0xFF3B4252) }, RoundedCornerShape(50))
                                .clickable(enabled = found) { keep = if (a in keep) keep - a else keep + a; vm.haptics.press() },
                                contentAlignment = Alignment.Center) {
                                Text((if (on) "✓ " else "") + a, fontSize = 15.5.sp, fontWeight = FontWeight.Medium,
                                    color = when { on -> MaterialTheme.colorScheme.primary; found -> Muted; else -> Color(0xFF4B5160) })
                            }
                        }
                    }
                    // The values that will be saved (machine coordinates); left-out ones struck through.
                    if (p != null) Text(androidx.compose.ui.text.buildAnnotatedString {
                        have.filter { it.second != null }.forEachIndexed { i, (a, v) ->
                            if (i > 0) append(" · ")
                            val off = a !in keep
                            if (off) pushStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough, color = Color(0xFF5B6170)))
                            append("$a ${Fmt.fixed(v!!, 3)}")
                            if (off) pop()
                        }
                    }, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    else if (problem == null) Text("Reading the work zero…", fontSize = 12.5.sp, color = Muted)
                    val missing = have.filter { it.second == null }.map { it.first }
                    when {
                        p != null && p.z != null && 'Z' !in keep ->
                            Text("Without Z, a job zeroed here still probes its own Z (stock that changes height).", fontSize = 12.5.sp, color = Muted, lineHeight = 16.sp)
                        p != null && missing.isNotEmpty() ->
                            Text("${missing.joinToString(" and ")} ${if (missing.size == 1) "wasn't" else "weren't"} probed, so ${if (missing.size == 1) "it" else "they"} can't be saved.",
                                fontSize = 12.5.sp, color = Muted, lineHeight = 16.sp)
                    }
                    problem?.let { Text(it, fontSize = 12.5.sp, color = ErrFg) }
                }
            },
            confirmButton = { TextButton(enabled = p != null && ('X' in kept || 'Y' in kept), onClick = {
                problem = vm.anchors.saveProbed(name, p!!, keep)
                if (problem == null) { saved = name.trim(); asking = false; vm.haptics.press() }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } })
    }
}

/**
 * Edge, multiple points: how far the edge is turned from the machine's axis (a best-fit line through
 * every touch), in words and numbers; with 3 or more touches, how straight it is, as a number, in
 * words, as a magnified plot of each touch against the line, and touch by touch.
 */
@Composable
private fun EdgeFitView(r: ProbeResult, f: EdgeFit) {
    val s = r.setup; val e = s.edge
    val len = mmText(kotlin.math.round(f.length * 1000) / 1000.0)
    val mm = Fmt.fixed(kotlin.math.abs(f.delta), 3)
    val square = kotlin.math.abs(f.delta) < 0.0005
    val way = if (e.axis == 'Y') (if (f.delta > 0) "further back" else "further forward") else (if (f.delta > 0) "further right" else "further left")
    val text = if (square) "The ${e.label} edge is square to the machine: over $len mm it doesn't move."
        else "The ${e.label} edge is ${Fmt.fixed(kotlin.math.abs(f.degrees), 2)}° out. Over $len mm toward the ${s.alongWord()} (${f.n} touches), it runs $mm mm $way."
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.SquareFoot, null, tint = Amber, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 14.5.sp, lineHeight = 19.sp)
        }
    }
    Cells(listOf("Difference" to f.delta, "Angle (°)" to f.degrees, "Per 100 mm" to f.per100))
    Text((if (f.n > 2) "Angle from a best-fit line through all ${f.n} touches; " else "") +
        "good to about ±${Fmt.fixed(f.noiseDegrees, if (f.noiseDegrees < 0.01) 3 else 2)}° from the probe's ±${Fmt.fixed(EdgeFit.REPEAT, 3)} mm repeat. " +
        "Difference is last touch − first along the line, on ${e.axis}.", fontSize = 11.5.sp, color = Muted, lineHeight = 15.sp)
    f.straightness?.let { StraightnessView(r, f, it) }
}

@Composable
private fun StraightnessView(r: ProbeResult, f: EdgeFit, straightness: Double) {
    val e = r.setup.edge
    val primary = MaterialTheme.colorScheme.primary
    val worst = f.offLine.maxOf { kotlin.math.abs(it) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Straightness", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(Fmt.fixed(straightness, 3), fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            Text(" mm", fontSize = 12.sp, color = Muted)
        }
        f.shape()?.let { Text(it, fontSize = 13.5.sp, lineHeight = 18.sp) }
        // Each touch against the line, magnified so a few microns show: the scale is the larger of
        // the worst touch and 0.01 mm (the probe's noise), so a straight edge looks straight.
        val scale = maxOf(worst, 0.01)
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(84.dp)) {
            val w = size.width; val h = size.height; val pad = 14f
            val mid = h / 2; val amp = h / 2 - pad
            fun x(i: Int) = if (f.n == 1) w / 2 else pad + (w - 2 * pad) * i / (f.n - 1)
            fun y(v: Double) = (mid - v / scale * amp).toFloat()
            drawLine(Color(0xFF24324A), androidx.compose.ui.geometry.Offset(0f, pad), androidx.compose.ui.geometry.Offset(w, pad), 1f)
            drawLine(Color(0xFF24324A), androidx.compose.ui.geometry.Offset(0f, h - pad), androidx.compose.ui.geometry.Offset(w, h - pad), 1f)
            drawLine(primary, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(w, mid), 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
            for (i in 0 until f.n - 1) drawLine(Amber, androidx.compose.ui.geometry.Offset(x(i), y(f.offLine[i])),
                androidx.compose.ui.geometry.Offset(x(i + 1), y(f.offLine[i + 1])), 3f)
            for (i in 0 until f.n) drawCircle(Amber, 7f, androidx.compose.ui.geometry.Offset(x(i), y(f.offLine[i])))
        }
        Text("Each touch's distance from the best-fit line (blue), magnified: top and bottom are ±${Fmt.fixed(scale, 3)} mm. + is further out of the part.",
            fontSize = 11.5.sp, color = Muted, lineHeight = 15.sp)
        // Touch by touch: how far along, where the edge was (work, from your current zero), how far off the line.
        val ai = if (e.axis == 'X') 0 else 1
        Row { listOf("Touch", "Along", "Edge ${e.axis}", "Off line").forEachIndexed { i, h ->
            Text(h, fontSize = 11.sp, color = Muted, modifier = Modifier.weight(if (i == 0) 0.7f else 1f),
                textAlign = if (i == 0) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End) } }
        for (i in 0 until f.n) {
            val off = f.offLine[i]
            val edgeW = r.machine.edgeTouches[i] - (r.before.getOrNull(ai) ?: 0.0)
            Row {
                Text("${i + 1}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.7f))
                Text(mmText(kotlin.math.round(f.t[i] * 1000) / 1000.0), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text(Fmt.fixed(edgeW, 3), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text(Fmt.fixed(off, 3, plus = true), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                    color = if (kotlin.math.abs(off) >= worst - 1e-9 && worst > 2 * EdgeFit.REPEAT) Amber else Color.Unspecified,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        Text("The probe repeats to about ±${Fmt.fixed(EdgeFit.REPEAT, 3)} mm, so anything under about 0.01 mm is noise.", fontSize = 11.5.sp, color = Muted, lineHeight = 15.sp)
    }
}

/** Beside a finished step: what it set (Makera / Top only) or that it was found (Measure only). */
private fun doneNote(label: String, setMode: Boolean): String? {
    val s = com.z1pendant.probe.Steps
    if (label == s.CORNER || label == s.EDGE || label == s.CENTER || label == s.BACK_OFF) return null
    if (!setMode || Regex("""Touch \d+ of \d+""").matches(label)) return "found"
    return when (label) {
        s.TOP -> "Z0 set"
        s.X_SIDE, s.X_WALL, s.X_WALLS, s.X_SIDES -> "X0 set"
        else -> "Y0 set"
    }
}

private fun whereText(r: ProbeResult): String {
    val s = r.setup; val z = r.zeroSet
    val xy = listOf('X', 'Y').filter { it in z }.joinToString(" ") { "${it}0" }
    val place = when (s.kind) {
        ProbeKind.OUTSIDE, ProbeKind.INSIDE -> if (s.doX && s.doY) "at the ${s.corner.label} corner" else "on the ${sideName(s)} side"
        ProbeKind.HOLE -> "at the center of the hole"
        ProbeKind.BLOCK -> "at the center of the block"
        ProbeKind.TOP -> ""
        ProbeKind.EDGE -> "on the ${s.edge.label} edge"
    }
    return listOfNotNull(xy.takeIf { it.isNotEmpty() }?.let { "$it $place" }, "Z0 on the top".takeIf { 'Z' in z })
        .joinToString(", ").ifEmpty { "Nothing changed." } + "."
}

// ---- info sheet ------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(step: Int, tool: ProbeTool, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color(0xFF172033)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("How these work", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            when (step) {
                4 -> {
                    InfoItem(FieldArt.X, tool, "X and Y start offset", "You park the probe over the part. It moves out this far, goes down, then comes back in until it touches the edge. Make it more than the distance from where you parked to the edge, plus a few mm. For a hole it's how far it searches each way; for an inside corner, how far it moves into the pocket.")
                    InfoText("Edge started from here: search distance", "The ball is already beside the part at the height it touches. It moves straight in, at most this far, until it touches the edge, then backs off to where it started. Make it more than the gap to the edge.")
                    InfoItem(FieldArt.DEPTH, tool, "Side depth", "How far below the top surface the ball goes before it touches the sides. Decimals are fine (1.5). Go deeper if the edge is rounded or chamfered. An edge started from here touches at the height you parked it, so side depth is off.")
                    InfoItem(FieldArt.DEPTH, tool, "Side depth with Z off", "A corner or block with Z off doesn't touch the top, so it measures the side depth from your current Z0 instead. Set Z on the top first (Top only); park the ball anywhere above it.")
                    InfoItem(FieldArt.TIP, tool, "Tip diameter", "The ball's size. It touches with the side of the ball, so half of this is added to find the real edge. Measure it with calipers if you can.")
                    InfoText("Edge: multiple points", "It touches the edge where you parked, then again along it, the number of points spread evenly over the total length. A best-fit line through them says how far the edge is turned from the machine's X or Y (use it to square a vise or a part); with 3 or more it also says how straight the edge is. It only measures: your work zero doesn't change. The longer the length, the finer the angle.")
                    InfoText("Axes to probe", "Corners, hole and block: turn on only the axes you want. Each one that's on is touched and set; the others are left alone.")
                    InfoText("Set work zero", "When the axes match one of Makera's routines (a corner with X, Y and Z, a hole, a block center with Z), it runs Makera's own routine (M480), exactly as Makera Studio does. Anything else, the pendant makes the touches itself and writes only the axes that are on.")
                    InfoText("Measure only", "The pendant makes the same touches itself and doesn't change anything. The next screen shows what it found, and you can set any of the axes as work zero from there.")
                }
                else -> {
                    InfoText("${tool.label} in the spindle", "The machine keeps a tool number, and it can be left over from last time. Load the ${tool.noun} runs Makera's tool change (M6 T9999): it waits while you swap, then calibrates the probe on the tool setter and checks it triggers. T9999 also keeps the probe powered and turns on the machine's stop for an unexpected probe touch.")
                    InfoText("Calibrated on the tool setter", "Every time you open probing, the probe is calibrated on the tool setter first. That does three things: the probe has to trigger on the setter (a dead probe or unplugged cable stops here), it measures the probe's length so the Z0 it sets carries over to your cutters, and where it trips the setter says which probe is in.")
                    InfoText("Which probe is in", "The 3D probe, the probe rod and the laser probe each trip the setter at their own height. The first calibration of each one learns it. If the reading belongs to a different probe than the one you picked, swap in the right one; or use the one that's in, if it can do what you picked (all three can do Top only).")
                }
            }
        }
    }
}

@Composable
private fun InfoItem(art: FieldArt, tool: ProbeTool, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(width = 66.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)).background(TileBg).border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).padding(4.dp)) {
            FieldArtImage(art, Modifier.fillMaxSize(), tool)
        }
        Spacer(Modifier.width(12.dp))
        Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(text, fontSize = 13.sp, color = Color(0xFFCBD5E1), lineHeight = 18.sp) }
    }
}

@Composable
private fun InfoText(title: String, text: String) {
    Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(text, fontSize = 13.sp, color = Color(0xFFCBD5E1), lineHeight = 18.sp) }
}

/** The probe button on the main screen (top-right of the dial, mirroring the dial / pad switch). */
@Composable
fun ProbeButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MyLocation, "3D probe", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
    }
}
