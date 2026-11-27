package com.z1pendant.ui

import com.z1pendant.util.Serial
import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.link.ConnState
import com.z1pendant.platform.nowMs
import com.z1pendant.util.Fmt
import kotlin.math.roundToInt

fun stateColor(state: String?, conn: ConnState): Color = when {
    conn != ConnState.Connected -> Color(0xFF6B7280)
    state == null -> Color(0xFF6B7280)
    state.equals("Idle", true) -> Color(0xFF16A34A)
    state.equals("Run", true) -> Color(0xFF2563EB)
    state.equals("Hold", true) || state.equals("Pause", true) -> Color(0xFFD97706)
    state.equals("Alarm", true) -> Color(0xFFDC2626)
    state.equals("Home", true) -> Color(0xFF7C3AED)
    else -> Color(0xFF0891B2)
}

fun stepLabel(s: Double) = when {
    s >= 1 -> Fmt.fixed(s, 0)
    else -> Fmt.plain(s)
}

/** True when the window is at least 600 dp wide (iPad, Android tablet): bigger readout, smaller dial, 5×3 hotkeys. */
val LocalWide = androidx.compose.runtime.staticCompositionLocalOf { false }
/** True on a landscape tablet (at least 840 dp wide and wider than tall): side-by-side layout. */
val LocalLandscape = androidx.compose.runtime.staticCompositionLocalOf { false }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: AppViewModel, onSettings: () -> Unit, onConsole: () -> Unit, onFiles: () -> Unit = {}, onEdit: (Hotkey?) -> Unit,
               scroll: androidx.compose.foundation.ScrollState = rememberScrollState(), onProbe: () -> Unit = {}, onMap: () -> Unit = {}) {
    val conn by vm.link.conn.collectAsState()
    val st by vm.link.status.collectAsState()
    val job by vm.job.collectAsState()
    val jobTools by vm.jobTools.collectAsState()
    val lat by vm.link.latencyMs.collectAsState()
    val target by vm.target.collectAsState()
    val hideSerial by vm.hideSerial.collectAsState()
    val axis by vm.axis.collectAsState()
    val stepIdx by vm.stepIndex.collectAsState()
    val hotkeys by vm.hotkeys.collectAsState()
    val running by vm.runner.running.collectAsState()
    val degPerClick by vm.degPerClick.collectAsState()
    val outputs by vm.link.outputs.collectAsState()
    val editMode by vm.editMode.collectAsState()
    val expanded by vm.hotkeysExpanded.collectAsState()
    val arrangeOrder by vm.arrangeOrder.collectAsState()
    val connected = conn == ConnState.Connected
    var moveFor by remember { mutableStateOf<Char?>(null) }
    val wide = LocalWide.current
    val padMode by vm.jogPad.collectAsState()
    val zeroUndo by vm.zeroUndo.collectAsState()
    val zeroHold by vm.zeroHold.collectAsState()
    moveFor?.let { a -> MoveDialog(vm, a) { moveFor = null } }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- header ----
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onSettings)) {
                Text(target?.name?.let { Serial.shown(it, hideSerial) } ?: "No machine", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (conn) {
                        ConnState.Connected -> "${target?.host} · ${lat?.let { "$it ms" } ?: "—"}"
                        ConnState.Connecting -> "connecting to ${target?.host}…"
                        ConnState.Stalled -> "not answering · stop at the machine if needed"
                        ConnState.Lost -> "link lost · reconnecting…"
                        ConnState.Disconnected -> "tap to connect"
                    }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // While a job plays: "Run 42%" (also in the job's Idle moments), "Hold 42%", "Tool change".
            val s0 = st?.state
            val jp = job?.percent
            val toolWait = connected && s0.equals("Tool", true)
            val pill = when {
                !connected -> when (conn) { ConnState.Lost -> "Lost"; ConnState.Stalled -> "No reply"; else -> "Offline" }
                toolWait -> "Tool change"
                jp != null && (s0.equals("Hold", true) || s0.equals("Pause", true)) -> "$s0 $jp%"
                jp != null && !s0.equals("Alarm", true) -> "Run $jp%"
                else -> s0 ?: "…"
            }
            val pillColor = when {
                toolWait -> MaterialTheme.colorScheme.primary
                jp != null && pill.startsWith("Run") -> stateColor("Run", conn)
                else -> stateColor(s0, conn)
            }
            Text(pill, color = if (toolWait) MaterialTheme.colorScheme.onPrimary else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(50)).background(pillColor)
                    // Alarm: tap for what happened and Clear alarm.
                    .then(if (connected && s0.equals("Alarm", true)) Modifier.clickable { vm.alarmSheet.value = true } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 6.dp))
            IconButton(onClick = onFiles) { Icon(Icons.Filled.Folder, "Files") }
            IconButton(onClick = onConsole) { Icon(Icons.Filled.Terminal, "Console") }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
        }

        // Long-running hotkeys (homing, multi-step sequences with waits) get a banner with the
        // current step and Cancel. Quick ones finish before it would appear, so nothing flashes.
        var showRunning by remember { mutableStateOf(false) }
        LaunchedEffect(running?.label) {
            showRunning = false
            if (running != null) { kotlinx.coroutines.delay(1500); showRunning = true }
        }
        running?.takeIf { showRunning }?.let { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Running: ${r.label}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(r.stepText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { vm.runner.cancel() }) { Text("Cancel") }
            }
        }

        // ---- the pieces (arranged below for portrait, or side by side on a landscape tablet) ----
        val landscape = LocalLandscape.current
        // Dial and jog pad have their own step lists; indices are clamped for the frame where the
        // mode has switched but the step index hasn't caught up yet.
        val stepList = vm.steps
        val si = stepIdx.coerceIn(0, stepList.lastIndex)

        // Readout: tap a row = select the axis; "move" = Move box; zero / undo.
        val dro: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp)) {
            vm.axes.forEachIndexed { i, a ->
                val sel = a == axis && !padMode           // the pad's buttons name their own axis
                val zeroProgress = remember { androidx.compose.animation.core.Animatable(0f) }
                val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                Row(
                    // Tap = select the axis (the move / zero buttons keep their own taps).
                    Modifier.fillMaxWidth()
                        .pointerInput(a) { detectTapGestures { vm.selectAxis(a); vm.haptics.press() } }
                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                        .drawBehind {
                            // Hold-to-zero: the row fills while the zero button is held, shown after a
                            // short delay so ordinary taps don't flash it.
                            val p = ((zeroProgress.value - 0.1f) / 0.9f).coerceIn(0f, 1f)
                            if (p > 0f) drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * p, size.height))
                        }
                        .padding(horizontal = 10.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.toString(), fontSize = if (landscape) 36.sp else if (wide) 40.sp else 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(if (wide) 50.dp else 26.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(fmtPos(st?.wpos?.getOrNull(i), connected), fontSize = if (landscape) 44.sp else if (wide) 52.sp else 26.sp, lineHeight = if (landscape) 46.sp else if (wide) 54.sp else 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Text("M " + fmtPos(st?.mpos?.getOrNull(i), connected), fontSize = if (landscape) 16.sp else if (wide) 18.sp else 12.sp, lineHeight = if (landscape) 17.sp else if (wide) 20.sp else 13.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    PendantOutlinedButton(onClick = { vm.selectAxis(a); vm.haptics.press(); moveFor = a }, enabled = connected,
                        contentPadding = PaddingValues(horizontal = if (wide) 16.dp else 10.dp, vertical = 0.dp), modifier = Modifier.height(if (wide) 46.dp else 34.dp)) { Text("move", fontSize = if (wide) 17.sp else 13.sp) }
                    Spacer(Modifier.width(6.dp))
                    // "zero" sets this axis to 0; pressed again while it still reads 0 (nothing moved)
                    // it puts the previous value back, like a DRO's zero/recall. Label shows which.
                    val undo = zeroUndo.containsKey(a) && vm.canRestoreZero(a, st)
                    val zeroOk = connected && st?.isIdle == true
                    val zeroSize = Modifier.height(if (wide) 46.dp else 34.dp).widthIn(min = if (wide) 88.dp else 64.dp)
                    if (zeroHold) {
                        // Settings → "Hold zero buttons 1.5 s": a tap can't zero by accident.
                        HoldZeroButton(if (undo) "undo" else "zero", zeroOk, wide, zeroProgress,
                            onHold = { vm.haptics.majorTick(); vm.zero(a.toString()) }, onShort = { vm.haptics.press() }, modifier = zeroSize)
                    } else PendantOutlinedButton(onClick = { vm.haptics.press(); vm.zero(a.toString()) }, enabled = zeroOk,
                        contentPadding = PaddingValues(horizontal = if (wide) 16.dp else 10.dp, vertical = 0.dp), modifier = zeroSize) {
                        Text(if (undo) "undo" else "zero", fontSize = if (wide) 17.sp else 13.sp)
                    }
                }
            }
        }
        }
        val stepSelector: @Composable () -> Unit = {
            Segmented(stepList.map { stepLabel(it) }, si) { vm.selectStep(it); vm.haptics.press() }
        }
        // Dial or jog pad in a box of the given size; top-left switches between them.
        val stage: @Composable androidx.compose.foundation.layout.BoxScope.(androidx.compose.ui.unit.Dp) -> Unit = { size ->
            if (!padMode) Handwheel(
                degPerClick = degPerClick, axis = axis,
                stepText = "${stepLabel(stepList[si])}${if (axis == 'A') "°" else "mm"}/click",
                enabled = connected && running == null,
                onClick = { vm.wheelClicks(it) },
                modifier = Modifier.size(size).align(Alignment.Center),
            ) else JogPad(vm, step = stepList[si], enabled = connected && running == null,
                // Below the mode and probe buttons in the top corners: the pad is as wide as the
                // stage, so its +A tile ran under the probe button when the stage got short (2026-11-19).
                modifier = Modifier.align(Alignment.Center).padding(top = 52.dp))
            ModeButton(padMode, Modifier.align(Alignment.TopStart)) { vm.setJogPad(!padMode) }
            ProbeButton(Modifier.align(Alignment.TopEnd)) { vm.haptics.press(); onProbe() }
            // Bed map: the dial is round, so its bottom-right corner is free; the jog pad fills the
            // bottom corners, so there it sits beside the dial/pad switch in the top row.
            MapButton(if (padMode) Modifier.align(Alignment.TopStart).padding(start = 58.dp) else Modifier.align(Alignment.BottomEnd)) {
                vm.haptics.press(); onMap()
            }
        }
        // Hotkeys: Edit (drag to rearrange) turns into Save; + adds one. Hold a tile 2 s to edit it.
        val hotkeyActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            IconButton(onClick = { onEdit(null) }) {
                Icon(Icons.Filled.Add, "New hotkey", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            if (editMode) Button(onClick = { vm.saveArrangement(); vm.haptics.press() }) { Text("Save") }
            else TextButton(onClick = { vm.startArranging(); vm.haptics.press() }) { Text("Edit") }
        }
        val visible = hotkeys.filter { it.showOnMain }
        val arranged = if (!editMode) emptyList() else {
            val byId = hotkeys.associateBy { it.id }
            val order = arrangeOrder ?: hotkeys.map { it.id }
            (order.mapNotNull { byId[it] } + hotkeys.filter { it.id !in order })   // new ones go last
                .let { l -> l.filter { it.showOnMain } + l.filterNot { it.showOnMain } }   // shown first
        }
        val hotkeyGrid: @Composable (List<Hotkey>, Int) -> Unit = { shown, cols ->
            if (editMode) Text("Drag tiles to rearrange · tap one to edit · Save when done", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HotkeyGrid(shown, cols = cols, arranging = editMode,
                toggleOf = { if (isSpindle(it)) (if (connected) vm.spindleRunning(st) else null) else toggleState(it, outputs, connected) },
                liveOf = { if (isSpindle(it) && connected && vm.spindleRunning(st)) Fmt.thousands((st?.spindleRpm ?: 0.0).roundToInt()) else null },
                onTap = { h -> if (editMode) onEdit(h) else vm.requestRun(h) },
                onLong = { h -> vm.haptics.majorTick(); onEdit(h) },
                onMove = { id, to -> vm.moveInArrangement(id, to) },
                onLift = { vm.haptics.majorTick() })
            if (shown.isEmpty()) Text("No hotkeys yet. Tap + to add one.", fontSize = 13.sp)
        }

        if (landscape) {
            // ---- landscape tablet: readout, hotkeys and the stop controls on the left; steps
            // and a big dial / jog pad on the right. Everything always visible: no drawer, no
            // expand chevron.
            Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.48f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dro()
                        Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Hotkeys", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f)); hotkeyActions()
                        }
                        hotkeyGrid(if (editMode) arranged else visible, 4)
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FeedOverride(st?.feedOverride, connected) { vm.setFeedOverride(it) }
                        SpindleOverride(vm.spindleSlide(st), onSetPct = vm::setSpindlePercent, onReset = vm::resetSpindleOverride)
                        if (connected && st?.isAlarm == true) AlarmStrip(vm, st)
                        StopButtons(st?.state, connected, onHold = vm::hold, onResume = vm::resume, onReset = { vm.resetAsk.value = true }, height = 56.dp,
                            jobActive = job != null)
                    }
                }
                Column(Modifier.weight(0.52f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    stepSelector()
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) { stage(minOf(maxWidth, maxHeight)) }
                }
            }
        } else {
        // ---- portrait (phones, and tablets held upright) ----
        // Header + running banner pinned on top, the drawer pinned at the bottom; between scrolls.
        // The dial takes whatever height the rest leaves (on wide screens it grows when there are
        // only 1-2 hotkey rows, shrinks for 3). viewH = visible height, otherH = content minus dial.
        var viewH by remember { mutableIntStateOf(0) }
        var otherH by remember { mutableIntStateOf(-1) }
        val placedDial = remember { IntArray(1) }               // dial height used in the last layout, px
        if (expanded) placedDial[0] = 0
        var jobDetails by remember { mutableStateOf(false) }
        if (job == null) jobDetails = false
        // A job just started: the drawer opens fully expanded (the job is what matters now); the
        // user can drag it down (Jimmy, 2026-11-18).
        val jobStarted by vm.jobStarted.collectAsState()
        LaunchedEffect(jobStarted, job != null) {
            if (jobStarted && job != null) { jobDetails = true; vm.controlsOpen.value = true; vm.jobStarted.value = false }
        }
        if (!jobDetails) Column(Modifier.weight(1f).onSizeChanged { viewH = it.height }.verticalScroll(scroll)) {
        Column(Modifier.onSizeChanged { otherH = it.height - placedDial[0] }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        dro()
        stepSelector()

        // Dial / jog pad, hidden while the hotkey grid is expanded. Bottom-left: up-chevron opens
        // the hotkey grid (it slides up); expanded, a down-chevron brings the dial back.
        if (!expanded) BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Same height in both modes, so nothing below jumps. Both fill the leftover height.
            // Phones: up to 75% of the width, down to 62% (the jog pad's full height), so the screen
            // doesn't scroll on shorter phones or with a larger display size (e.g. a Galaxy S21 at
            // 360 dp wide was 32 dp too tall). Wide: between 260 dp and 80% of the width.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val left = if (viewH > 0 && otherH >= 0) with(density) { (viewH - otherH).toDp() } else null
            val box = if (!wide) (left ?: maxWidth).coerceIn(maxWidth * 0.62f, maxWidth * 0.75f)
                else (left ?: maxWidth * 0.5f).coerceIn(260.dp, maxWidth * 0.8f)
            placedDial[0] = with(density) { box.roundToPx() }
            Box(Modifier.fillMaxWidth().height(box)) {
                stage(box)
                IconButton(onClick = { vm.hotkeysExpanded.value = true; vm.haptics.press() },
                    modifier = Modifier.align(Alignment.BottomStart)) {
                    Icon(Icons.Filled.ExpandLess, "Show all hotkeys", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Collapsed: the first 8 hotkeys (15 on a wide screen). Expanded: dial hidden, all hotkeys,
        // + and Edit.
        if (expanded) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.hotkeysExpanded.value = false; vm.haptics.press() }, enabled = !editMode) {
                Icon(Icons.Filled.ExpandMore, "Back to the dial", Modifier.size(32.dp),
                    tint = if (editMode) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f))
            hotkeyActions()
        }
        val shown = when {
            editMode -> arranged
            expanded -> visible
            else -> visible.take(if (wide) 15 else 8)
        }
        hotkeyGrid(shown, if (wide) 5 else 4)

        Spacer(Modifier.height(4.dp))
        }
        }

        // ---- bottom drawer: feed override + hold/resume/reset ----
        // Closed it's a slim handle. Swipe up (or tap) to open, swipe down to close. It opens by
        // itself only when something other than the pendant is driving the machine or it stops:
        // Hold, Alarm, or motion the pendant didn't start (e.g. a job started from Studio). Jogs,
        // typed moves, console lines and hotkeys (Home, Z up, ...) don't open it (OwnMotion). If it opened itself,
        // it closes again once the machine is back to Idle.
        val open by vm.controlsOpen.collectAsState()
        val s = st?.state
        val own by vm.ownMotionActive.collectAsState()
        val machineMoving = (s.equals("Run", true) || s.equals("Home", true)) && !own
        // A job (|P:) keeps it open, including its Idle moments around tool changes.
        val needsControls = connected && (machineMoving || job != null || s.equals("Tool", true) ||
            s.equals("Hold", true) || s.equals("Pause", true) || s.equals("Alarm", true))
        var autoOpened by remember { mutableStateOf(false) }
        // A job ran: once it's over (and nothing else needs the controls) the drawer closes all the
        // way, however it got open (the job start opens it itself, and you may have dragged it).
        var hadJob by remember { mutableStateOf(false) }
        LaunchedEffect(job != null) { if (job != null) hadJob = true }
        LaunchedEffect(needsControls) {
            if (needsControls && !vm.controlsOpen.value) { vm.controlsOpen.value = true; autoOpened = true }
            if (!needsControls && (autoOpened || hadJob)) { vm.controlsOpen.value = false; autoOpened = false; hadJob = false }
        }
        ControlsDrawer(open, feed = st?.feedOverride, connected = connected,
            onOpen = { o -> if (o != open) { vm.controlsOpen.value = o; autoOpened = false; vm.haptics.press() } },
            onFeed = { vm.setFeedOverride(it) }, onHold = vm::hold, onResume = vm::resume, onReset = { vm.resetAsk.value = true }, state = st?.state,
            spindle = { SpindleOverride(vm.spindleSlide(st), onSetPct = vm::setSpindlePercent, onReset = vm::resetSpindleOverride) },
            jobActive = job != null, details = jobDetails,
            onDetails = { d -> if (d != jobDetails) { jobDetails = d; if (d) vm.controlsOpen.value = true; vm.haptics.press() } },
            jobLine = { job?.let { j -> JobLine(j) { jobDetails = true; vm.haptics.press() } } },
            jobPanel = { job?.let { j -> JobDetails(j, st, jobTools, onMesh = { vm.run.meshShow.value = true },
                banner = { TempBanner(vm) }, temps = { TempCard(vm) }) { jobDetails = false; vm.haptics.press() } } },
            alarm = { if (connected && st?.isAlarm == true) AlarmStrip(vm, st) },
            modifier = if (jobDetails) Modifier.weight(1f) else Modifier)
        }
    }
}

@Composable
private fun ControlsDrawer(
    open: Boolean, feed: Double?, connected: Boolean, onOpen: (Boolean) -> Unit,
    onFeed: (Int) -> Unit, onHold: () -> Unit, onResume: () -> Unit, onReset: () -> Unit,
    state: String? = null,
    spindle: @Composable () -> Unit = {},
    jobActive: Boolean = false, details: Boolean = false, onDetails: (Boolean) -> Unit = {},
    jobLine: @Composable () -> Unit = {}, jobPanel: @Composable () -> Unit = {},
    /** Above the stop buttons: the alarm strip while in Alarm. */
    alarm: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val swipeDp = 24.dp
    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { swipeDp.toPx() }
    // Vertical swipe anywhere on the drawer; horizontal drags still reach the feed slider.
    // Up: open, then (while a job runs) the job details. Down: back one step.
    val swipe = Modifier.pointerInput(open, jobActive, details) {
        var dy = 0f
        detectVerticalDragGestures(
            onDragStart = { dy = 0f },
            onVerticalDrag = { c, d -> dy += d; c.consume() },
            onDragEnd = {
                if (dy < -swipePx) { if (!open) onOpen(true) else if (jobActive) onDetails(true) }
                else if (dy > swipePx) { if (details) onDetails(false) else onOpen(false) }
            },
        )
    }
    Column(
        modifier.fillMaxWidth().padding(bottom = 4.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
            .background(if (open) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .then(swipe),
    ) {
        // Handle: grab bar, and the current feed so you can see it without opening.
        Box(Modifier.fillMaxWidth().height(30.dp).clickable { if (details) onDetails(false) else onOpen(!open) }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)))
            if (!open) {
                Text("Feed ${feed?.toInt() ?: 100}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
                Text(if (jobActive) "Hold · Resume · Cancel" else "Hold · Resume · Stop", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
            }
        }
        androidx.compose.animation.AnimatedVisibility(open,
            enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top),
            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp).then(if (details) Modifier.fillMaxHeight() else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (details) {
                    // No scroll here: a vertical drag on the details closes them (swipe down), like the drawer.
                    Column(Modifier.weight(1f)) { jobPanel() }
                    Spacer(Modifier.height(4.dp))
                } else if (jobActive) jobLine()
                FeedOverride(feed, connected, onFeed)
                spindle()
                alarm()
                // Muted while idle; the relevant ones light up (see StopButtons).
                StopButtons(state, connected, onHold, onResume, onReset, jobActive = jobActive)
            }
        }
    }
}

/** How long to hold a readout zero button (Settings → hold to zero). */
private const val HOLD_MS = 1500L

/**
 * The readout's zero button with "hold to zero" on: shaped like the outlined buttons, it fires
 * after [HOLD_MS] held; [progress] runs 0→1 meanwhile (its readout row draws the fill). A shorter
 * press does nothing but say "hold" for a moment. It takes the whole touch, so the row under it
 * doesn't also see a tap.
 */
@Composable
private fun HoldZeroButton(label: String, enabled: Boolean, wide: Boolean,
                           progress: androidx.compose.animation.core.Animatable<Float, *>,
                           onHold: () -> Unit, onShort: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf(false) }
    val en by rememberUpdatedState(enabled)
    val hold by rememberUpdatedState(onHold)
    val short by rememberUpdatedState(onShort)
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape)
            .border(1.dp, if (enabled) cs.outline else cs.onSurface.copy(alpha = 0.12f), shape)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (!en) {
                        while (true) { val ev = awaitPointerEvent(); ev.changes.forEach { it.consume() }; if (ev.changes.none { it.pressed }) break }
                        return@awaitEachGesture
                    }
                    val ring = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, androidx.compose.animation.core.tween(HOLD_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing))
                    }
                    // "up" = let go early, "moved" = slid off, null = held the full time
                    val outcome: String? = withTimeoutOrNull(HOLD_MS) {
                        var r = ""
                        while (r.isEmpty()) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            r = when {
                                c == null || !c.pressed -> "up"
                                (c.position - down.position).getDistance() > slop * 3 -> "moved"
                                else -> ""
                            }
                        }
                        r
                    }
                    ring.cancel()
                    scope.launch { progress.snapTo(0f) }
                    when (outcome) {
                        null -> {
                            hold()
                            while (true) { val ev = awaitPointerEvent(); ev.changes.forEach { it.consume() }; if (ev.changes.none { it.pressed }) break }
                        }
                        "up" -> { short(); scope.launch { hint = true; kotlinx.coroutines.delay(1200); hint = false } }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (hint) "hold" else label, fontSize = if (wide) 17.sp else 13.sp,
            color = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(horizontal = if (wide) 16.dp else 10.dp))
    }
}

fun isSpindle(h: Hotkey) = h.steps.size == 1 &&
    (h.steps[0] as? com.z1pendant.hotkeys.Step.Builtin)?.action == com.z1pendant.hotkeys.BuiltIn.SPINDLE_TOGGLE

/** On/off for toggle tiles (light, vacuum); null = not a toggle, or state unknown. */
fun toggleState(h: Hotkey, outputs: Map<String, Boolean>, connected: Boolean): Boolean? {
    val isLight = h.steps.size == 1 &&
        (h.steps[0] as? com.z1pendant.hotkeys.Step.Builtin)?.action == com.z1pendant.hotkeys.BuiltIn.LIGHT_TOGGLE
    val isVacuum = h.steps.size == 1 &&
        (h.steps[0] as? com.z1pendant.hotkeys.Step.Builtin)?.action == com.z1pendant.hotkeys.BuiltIn.VACUUM_TOGGLE
    return if (!connected) null else if (isLight) outputs["light"] else if (isVacuum) outputs["vacuum"] else null
}

fun fmtPos(v: Double?, connected: Boolean) = if (v == null || !connected) "---.---" else Fmt.fixed(v, 3, width = 9)

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(Modifier.weight(1f).fillMaxHeight()
                .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(o, fontSize = 16.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Spindle override (bar in RPM, 0..13,000; past the programmed RPM, i.e. 100 %, the fill is darker).
 * − / + step 5 % of the programmed RPM; dragging shows the RPM you'd get in place of "Spindle".
 * Sent as M223 (a % of the programmed RPM). Off while the spindle isn't running.
 */
@Composable
fun SpindleOverride(slide: com.z1pendant.SpindleSlide?, onSetPct: (Double) -> Unit, onReset: () -> Unit) {
    var drag by remember { mutableStateOf<Int?>(null) }
    if (slide == null) drag = null
    val hiPct = slide?.let { it.hi / it.target * 100 } ?: 100.0
    val pend = rememberPendingOverride(slide?.pct, onSetPct, same = { a, b -> kotlin.math.abs(a - b) < 0.11 })
    val pct: Double? = when {
        slide == null -> null
        drag != null -> slide.percentFor(drag!!)
        else -> pend.value ?: slide.pct
    }
    val rpm = drag ?: slide?.let { kotlin.math.round(it.target * (pct ?: 100.0) / 100).toInt() } ?: 0
    OverrideRow(
        label = "Spindle", dragLabel = drag?.let { Fmt.thousands(it) },
        value = rpm.toFloat(), base = slide?.target?.toFloat() ?: 0f,
        range = 0f..com.z1pendant.SPINDLE_MAX_RPM.toFloat(), enabled = slide != null,
        percent = pct?.let { kotlin.math.round(it).toInt() },
        onDrag = { v -> slide?.let { drag = it.clamp((v / 100f).roundToInt() * 100) } },
        onRelease = { val d = drag; drag = null; if (d != null && slide != null) pend.sendNow(slide.percentFor(d)) },
        onStep = { dir -> pct?.let { pend.step(stepFive(it, dir).coerceIn(50.0, hiPct)) } },
        resetText = "Back to 100%?", onReset = onReset,
    )
}

/** Feed override, 10–200 % (past 100 % darker). − / + step 5 %; drag snaps to 5 %. */
@Composable
fun FeedOverride(current: Double?, enabled: Boolean, onSet: (Int) -> Unit) {
    var drag by remember { mutableStateOf<Int?>(null) }
    val pend = rememberPendingOverride(current, { onSet(kotlin.math.round(it).toInt()) }, same = { a, b -> kotlin.math.abs(a - b) < 0.5 })
    val shown = drag ?: (pend.value ?: current ?: 100.0).roundToInt()
    OverrideRow(
        label = "Feed", dragLabel = null,
        value = shown.toFloat(), base = 100f, range = 10f..200f, enabled = enabled, percent = shown,
        onDrag = { v -> drag = ((v / 5f).roundToInt() * 5).coerceIn(10, 200) },
        onRelease = { val d = drag; drag = null; if (d != null) pend.sendNow(d.toDouble()) },
        onStep = { dir -> pend.step(stepFive(shown.toDouble(), dir).coerceIn(10.0, 200.0)) },
        resetText = "Back to 100%?", onReset = { onSet(100) },
    )
}
