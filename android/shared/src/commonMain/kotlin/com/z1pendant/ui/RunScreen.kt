package com.z1pendant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.isProbeTool
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineStatus
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.run.Assist
import com.z1pendant.run.Extents
import com.z1pendant.run.Mesh
import com.z1pendant.run.Origin
import com.z1pendant.run.RunController
import com.z1pendant.run.RunController.LaserPhase
import com.z1pendant.run.RunController.ScanPhase
import com.z1pendant.run.RunSetup
import com.z1pendant.util.Fmt
import com.z1pendant.run.ANCHOR_LENGTH
import com.z1pendant.run.ANCHOR_WIDTH
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

private val Ok = Color(0xFF86EFAC)
private val OkBg = Color(0xFF0F2E1C)
private val Warn = Color(0xFFFDE68A)
private val WarnBg = Color(0xFF3A2A0B)
private val Bad = Color(0xFFF87171)
private val BadBg = Color(0xFF3B1212)
private val Card = Color(0xFF172033)
private val MapBg = Color(0xFF202326)
private val PathGreen = Color(0xFF4ADE80)
private val OriginBlue = Color(0xFF38BDF8)
private val ZRed = Color(0xFFF87171)
private val LevelYellow = Color(0xFFFACC15)
private val LaserRed = Color(0xFFFF3B30)


private fun f3(v: Double) = Fmt.fixed(v, 3)

/**
 * Run a job (mockups/run_mock.html, round 2): bed map on top, then 1 Origin, 2 Checks, 3 Assist,
 * 4 Run. [onProbe] opens the 3D probe wizard (Custom origin); it comes back here.
 */
@Composable
fun RunScreen(vm: AppViewModel, onProbe: () -> Unit) {
    val r = vm.run
    val step by r.step.collectAsState()
    val path by r.path.collectAsState()
    val error by r.error.collectAsState()
    val laserAsk by r.laserAsk.collectAsState()
    val st by vm.link.status.collectAsState()
    PlatformBackHandler { r.back() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = r::close) { Icon(Icons.Filled.Close, "Close") }
            Text("Run ${path?.substringAfterLast('/') ?: ""}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        StepBar(step)
        if (step != 2) {
            BedMap(vm, st)
            if (r.offBed(st) == true) Text("With this origin the cutting area runs past the bed.", color = Bad, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                0 -> OriginStep(vm, onProbe)
                1 -> ChecksStep(vm, st)
                2 -> AssistStep(vm, st)
                else -> RunStep(vm, st)
            }
        }
        error?.let { Text(it, color = Bad, fontSize = 13.sp) }
        // The laser probe swap is waiting for you: the main button carries on with it, so there's
        // nothing to scroll to (2026-11-19: its button sat at the bottom of the page).
        val laserNow by r.laser.collectAsState()
        val toolNow by vm.probe.toolAction.collectAsState()
        val swapWaiting = laserNow.phase == LaserPhase.CHECKING && toolNow?.stage == com.z1pendant.probe.ToolAction.Stage.WAITING
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PendantOutlinedButton(onClick = r::back, modifier = Modifier.weight(1f).height(48.dp)) { Text(if (step == 0) "Cancel" else "Back", fontSize = 16.sp) }
            if (swapWaiting) Button(onClick = { vm.haptics.press(); vm.probe.continueToolChange() }, modifier = Modifier.weight(1f).height(48.dp)) {
                Text("Next", fontSize = 16.sp)
            }
            else if (step < 3) Button(onClick = { if (r.probeNeeded()) onProbe() else r.next() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Next", fontSize = 16.sp) }
            else {
                val block = r.startBlock(st)
                Button(onClick = r::start, enabled = block == null, modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E), contentColor = Color(0xFF052E16))) {
                    Text("Start job", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (laserAsk) LaserSheet(vm, st)
}

// ---- step bar --------------------------------------------------------------------------------------
private val STEPS = listOf("Origin", "Checks", "Assist", "Run")

/**
 * One step of the bar: a chevron that points right; every step but the first has a notch on its left.
 * The bar's two outer ends (the first step's left, the last step's right) have rounded corners.
 */
private class Chevron(private val first: Boolean, private val last: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = with(density) { 9.dp.toPx() }
        val r = with(density) { 8.dp.toPx() }
        val w = size.width; val h = size.height
        return Outline.Generic(Path().apply {
            if (first) { moveTo(r, 0f) } else moveTo(0f, 0f)
            if (last) { lineTo(w - r, 0f); quadraticTo(w, 0f, w, r); lineTo(w, h - r); quadraticTo(w, h, w - r, h) }
            else { lineTo(w - d, 0f); lineTo(w, h / 2); lineTo(w - d, h) }
            if (first) { lineTo(r, h); quadraticTo(0f, h, 0f, h - r); lineTo(0f, r); quadraticTo(0f, 0f, r, 0f) }
            else { lineTo(0f, h); lineTo(d, h / 2) }
            close()
        })
    }
}

/** Where the run is: done steps tinted, this one filled, the rest dark. Words only (Jimmy, 2026-11-18). Display only. */
@Composable
private fun StepBar(step: Int) = Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    STEPS.forEachIndexed { i, t ->
        val on = i == step; val done = i < step
        val p = MaterialTheme.colorScheme.primary
        Box(Modifier.weight(1f).fillMaxHeight().clip(Chevron(first = i == 0, last = i == STEPS.lastIndex))
            .background(if (on) p else if (done) p.copy(alpha = 0.22f) else Card)
            .padding(start = if (i == 0) 0.dp else 8.dp, end = if (i == STEPS.lastIndex) 0.dp else 8.dp),
            contentAlignment = Alignment.Center) {
            Text(t, fontSize = 12.sp, maxLines = 1,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.onPrimary else if (done) p else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- bed map ---------------------------------------------------------------------------------------
@Composable
private fun BedMap(vm: AppViewModel, st: MachineStatus?) {
    val r = vm.run
    val s by r.setup.collectAsState()
    val load by r.load.collectAsState()
    val anchor by r.anchor.collectAsState()
    val scan by r.scan.collectAsState()
    val step by r.step.collectAsState()
    val limits by vm.softLimits.collectAsState()
    val bed = limits.bed ?: com.z1pendant.link.Bed.Z1          // the machine's travel (its soft limits)
    val BED_X0 = bed.x0; val BED_X1 = bed.x1; val BED_Y0 = bed.y0; val BED_Y1 = bed.y1
    val e = (load as? RunController.Load.Ready)?.extents
    val o = r.plannedOrigin(st)
    Row(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp)).background(MapBg)) {
        // The legend has its own column, so it never sits on the bed (2026-11-19: it hid the bracket).
        Column(Modifier.widthIn(max = 160.dp).padding(start = 10.dp, top = 8.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when (val l = load) {
                is RunController.Load.Loading -> {
                    Text("Loading X/Y limits…", fontSize = 10.sp)
                    LinearProgressIndicator(progress = { if (l.total > 0) l.done.toFloat() / l.total else 0f }, modifier = Modifier.width(110.dp).padding(top = 2.dp))
                }
                is RunController.Load.Failed -> Text(l.why, fontSize = 10.sp, color = Bad)
                is RunController.Load.Ready -> {
                    Legend(PathGreen, if (e != null) "Cutting area ${Fmt.fixed(e.width, 1)} × ${Fmt.fixed(e.height, 1)}" else "No cutting moves")
                    Legend(OriginBlue, "Origin")
                    // The white crosshair: where the spindle is now (the laser dot replaces it while scanning).
                    if (st != null && scan.phase != ScanPhase.RUNNING) SpindleLegend()
                    if (step >= 1 && s.zOn) Legend(ZRed, "Z probe")
                    if (step >= 1 && s.leveling) Legend(LevelYellow, "Leveling")
                    if (scan.phase == ScanPhase.RUNNING) Legend(LaserRed, "Laser")
                }
            }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
            val bw = BED_X1 - BED_X0; val bh = BED_Y1 - BED_Y0
            val k = minOf(size.width / bw, size.height / bh).toFloat()
            // The bed sits on the right, next to the legend.
            val ox = size.width - bw.toFloat() * k; val oy = (size.height - bh.toFloat() * k) / 2
            fun p(x: Double, y: Double) = Offset(ox + ((x - BED_X0) * k).toFloat(), oy + ((BED_Y1 - y) * k).toFloat())
            drawRect(Color(0xFF2A2E33), p(BED_X0, BED_Y1), Size((bw * k).toFloat(), (bh * k).toFloat()))
            // 4 x 4 equal cells, so the grid ends on the bed's edges (50 mm steps left a sliver at the back and right).
            for (i in 1 until 4) {
                val gx = BED_X0 + bw * i / 4; drawLine(Color(0xFF34393F), p(gx, BED_Y0), p(gx, BED_Y1))
                val gy = BED_Y0 + bh * i / 4; drawLine(Color(0xFF34393F), p(BED_X0, gy), p(BED_X1, gy))
            }
            // The anchor bracket at full size: Anchor 1 is its inside corner; the arms run back and right from there.
            anchor?.let { (ax, ay) ->
                val c = Color(0xFF8A929C); val aw = ANCHOR_WIDTH; val al = ANCHOR_LENGTH
                drawRect(c, p(ax - aw, ay - aw + al), Size((aw * k).toFloat(), (al * k).toFloat()))   // left arm
                drawRect(c, p(ax - aw, ay), Size((al * k).toFloat(), (aw * k).toFloat()))             // front arm
            }
            if (e != null && o != null) {
                val (x0, y0) = o
                val tl = p(x0 + e.minX, y0 + e.maxY); val br = p(x0 + e.maxX, y0 + e.minY)
                val outside = x0 + e.minX < BED_X0 || x0 + e.maxX > BED_X1 || y0 + e.minY < BED_Y0 || y0 + e.maxY > BED_Y1
                val col = if (outside) Bad else if (scan.phase == ScanPhase.DONE) Color(0xFFFB923C) else PathGreen
                drawRect(col.copy(alpha = 0.12f), tl, Size(br.x - tl.x, br.y - tl.y))
                drawRect(col, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(2f,
                    pathEffect = if (scan.phase == ScanPhase.DONE) null else PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
                if (step >= 1 && s.leveling) { val a = s.levelArea(e); for (i in 0 until s.gridX) for (j in 0 until s.gridY) {
                    val fx = if (s.gridX > 1) i.toDouble() / (s.gridX - 1) else 0.5
                    val fy = if (s.gridY > 1) j.toDouble() / (s.gridY - 1) else 0.5
                    drawCircle(LevelYellow, 3f, p(x0 + a.minX + fx * a.width, y0 + a.minY + fy * a.height))
                } }
                drawCircle(OriginBlue, 5f, p(x0, y0))
                // A ring, so it still shows when it sits on the origin.
                if (step >= 1 && s.zOn) { val (zx, zy) = s.zPoint(e); drawCircle(ZRed, 7f, p(x0 + e.minX + zx, y0 + e.minY + zy), style = Stroke(2.5f)) }
            }
            st?.let { m ->
                val sp = p(m.mpos.getOrElse(0) { 0.0 }, m.mpos.getOrElse(1) { 0.0 })
                if (scan.phase == ScanPhase.RUNNING) drawCircle(LaserRed, 5f, sp)
                else { drawLine(Color.White, sp - Offset(6f, 0f), sp + Offset(6f, 0f), 1.5f); drawLine(Color.White, sp - Offset(0f, 6f), sp + Offset(0f, 6f), 1.5f) }
            }
        }
    }
}

/** The legend row for the spindle: a small white crosshair, as drawn on the map. */
@Composable
private fun SpindleLegend() = Row(verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(7.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawLine(Color.White, Offset(0f, c.y), Offset(size.width, c.y), 1.5f)
        drawLine(Color.White, Offset(c.x, 0f), Offset(c.x, size.height), 1.5f)
    }
    Spacer(Modifier.width(4.dp)); Text("Spindle", fontSize = 10.sp)
}

@Composable
private fun Legend(c: Color, t: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(c)); Spacer(Modifier.width(4.dp)); Text(t, fontSize = 10.sp)
}

// ---- step 1: origin ----------------------------------------------------------------------------------
@Composable
private fun OriginStep(vm: AppViewModel, onProbe: () -> Unit) {
    val r = vm.run
    val s by r.setup.collectAsState()
    val cam by r.camOffset.collectAsState()
    val anchor by r.anchor.collectAsState()
    val probed by r.probed3d.collectAsState()
    val st by vm.link.status.collectAsState()
    val saved by vm.anchors.list.collectAsState()
    for (o in Origin.entries) {
        val on = s.origin == o
        if (o == Origin.CUSTOM) { CustomAnchorOption(vm, s, saved, on); continue }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card)
            .then(if (on) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
            .clickable { r.update(s.withOrigin(o)) }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = on, onClick = { r.update(s.withOrigin(o)) })
                Column(Modifier.weight(1f)) {
                    Text(o.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    val sub = if (o == Origin.CURRENT && anchor != null && st != null) {
                        val (ax, ay) = anchor!!; val m = st!!
                        "X ${f3(m.mpos[0] - m.wpos[0] - ax)} · Y ${f3(m.mpos[1] - m.wpos[1] - ay)} from Anchor 1"
                    } else o.sub
                    Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (on && o == Origin.ANCHOR1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    NumField("X", s.offX, Modifier.weight(1f)) { r.update(s.copy(offX = it)) }
                    NumField("Y", s.offY, Modifier.weight(1f)) { r.update(s.copy(offY = it)) }
                }
                val c = cam
                val same = c != null && c.x == s.offX && c.y == s.offY
                TextButton(onClick = { c?.let { r.update(s.copy(offX = it.x, offY = it.y)) } }, enabled = c != null) {
                    Text(when {
                        c == null -> "Same as CAM (no offset in this file)"
                        same -> "Same as CAM (${Fmt.trimmed4(c.x)}, ${Fmt.trimmed4(c.y)}) ✓"
                        else -> "Same as CAM (${Fmt.trimmed4(c.x)}, ${Fmt.trimmed4(c.y)})"
                    })
                }
                Text("0, 0 puts the work zero on the bracket's inside corner (stock pushed into it). Z is probed on the next step.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (anchor == null) Text("Reading Anchor 1 from the machine…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (on && o == Origin.SPINDLE) Text("Sets X/Y zero where the spindle is when you tap Next.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 12.dp))
            // Next opens the probe wizard; once it has set the zero, this shows it and offers a redo.
            if (on && o == Origin.PROBE3D && probed) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, start = 12.dp)) {
                Text("✓ Set with the 3D probe", color = Ok, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onProbe) { Text("Probe again") }
            }
        }
    }
}

/** Machine coordinates of an anchor, "X −150.250 · Y −110.600 · Z −82.400" (only the axes it has). */
fun anchorCoords(a: com.z1pendant.run.Anchor) = listOfNotNull(a.x?.let { "X ${f3(it)}" }, a.y?.let { "Y ${f3(it)}" }, a.z?.let { "Z ${f3(it)}" })
    .joinToString(" · ").replace('-', '\u2212')

/**
 * "Custom anchor" with a dropdown of the saved anchors next to it (Jimmy, 2026-11-24: no list of
 * anchors in Run, no Manage link). Under it when picked: the offset from the anchor and what happens
 * to Z (the anchor's saved Z, or the Z probe when it has none).
 */
@Composable
private fun CustomAnchorOption(vm: AppViewModel, s: RunSetup, saved: List<com.z1pendant.run.Anchor>, on: Boolean) {
    val r = vm.run
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val none = saved.isEmpty()
    // The one shown in the dropdown: the picked one, else the one used last time, else the first.
    val shown = s.anchor?.let { a -> saved.firstOrNull { it.id == a.id } } ?: saved.firstOrNull { it.id == r.lastAnchorId() } ?: saved.firstOrNull()
    fun pick(a: com.z1pendant.run.Anchor?) { if (a != null) r.update(s.withAnchor(a)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card)
        .then(if (on) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
        .clickable(enabled = !none) { if (!on) pick(shown) }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = on, onClick = { if (!on) pick(shown) }, enabled = !none)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Origin.CUSTOM.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        color = if (none) muted else Color.Unspecified)
                    if (!none) {
                        Spacer(Modifier.width(8.dp))
                        var open by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f, fill = false)) {
                            Row(Modifier.clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (on) MaterialTheme.colorScheme.primary else Color(0xFF334155), RoundedCornerShape(8.dp))
                                .clickable { open = true }.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(shown?.name ?: "", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text(" \u25BE", fontSize = 13.sp, color = muted)
                            }
                            DropdownMenu(open, { open = false }) {
                                for (a in saved) DropdownMenuItem(
                                    text = { Column { Text(a.name); Text(anchorCoords(a), fontSize = 11.sp, color = muted) } },
                                    onClick = { open = false; pick(a) })
                            }
                        }
                    }
                }
                Text(if (none) "None saved yet. Add them in Settings \u203A Anchors." else shown?.let { anchorCoords(it) } ?: "",
                    fontSize = 12.sp, color = muted)
            }
        }
        val a = s.anchor
        if (on && a != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp, top = 6.dp)) {
                Text("Offset", fontSize = 13.sp, color = muted)
                if (a.x != null) SmallNum("X", s.aOffX) { r.update(s.copy(aOffX = it)) }
                if (a.y != null) SmallNum("Y", s.aOffY) { r.update(s.copy(aOffY = it)) }
                Text("mm", fontSize = 13.sp, color = muted)
            }
            if (a.x == null || a.y == null) Text("This anchor has only ${if (a.x != null) "X" else "Y"}: " +
                "${if (a.x != null) "Y" else "X"} stays where the work zero is now.", fontSize = 12.sp, color = muted,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp))
            if (a.z != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 2.dp)) {
                Text("Use the anchor's saved Z, skip the Z probe", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(s.useAnchorZ, { r.update(s.copy(useAnchorZ = it, probeZ = false)) })
            } else Text("No Z saved with this anchor, so Z is probed on the next step (as with Anchor 1).",
                fontSize = 12.sp, color = muted, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
    }
}

/** A small number box for offsets ("X [ 0 ]"): keeps what you type until it parses. */
@Composable
private fun SmallNum(label: String, value: Double, onChange: (Double) -> Unit) {
    fun show(v: Double) = Fmt.fixed(v, 3).trimEnd('0').trimEnd('.')
    var text by remember { mutableStateOf(show(value)) }
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = show(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        androidx.compose.foundation.text.BasicTextField(text, { t -> text = t; t.toDoubleOrNull()?.let(onChange) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.End),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.width(72.dp).clip(RoundedCornerShape(7.dp)).border(1.dp, Color(0xFF334155), RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.background).padding(horizontal = 7.dp, vertical = 5.dp))
    }
}

// ---- step 2: checks ---------------------------------------------------------------------------------------
@Composable
private fun ChecksStep(vm: AppViewModel, st: MachineStatus?) {
    val r = vm.run
    val s by r.setup.collectAsState()
    val scan by r.scan.collectAsState()
    val load by r.load.collectAsState()
    val e = (load as? RunController.Load.Ready)?.extents
    LaserBanner(vm, st, s.needsLaser)

    Box3 {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Probe Z at start", fontWeight = FontWeight.SemiBold)
                Text(when {
                    s.probeZLocked && s.origin == Origin.ANCHOR1 -> "Required with Anchor 1: sets Z zero on the stock top"
                    s.probeZLocked -> "Required: the anchor doesn't set Z. Sets Z zero on the stock top"
                    s.anchorSetsZ && !s.zOn -> "Off: Z zero comes from the anchor"
                    else -> "Sets Z zero on the stock top"
                },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Locked on with Anchor 1 (or an anchor without Z): shown on (not greyed), and a tap does nothing.
            Switch(s.zOn, { if (!s.probeZLocked) r.update(s.copy(probeZ = it)) })
        }
        if (s.zOn && e != null) {
            // The middle of the toolpath unless moved (the middle might be a hole or an earlier op's pocket).
            var moving by remember { mutableStateOf(s.zAtX != null || s.zAtY != null) }
            val (zx, zy) = s.zPoint(e)
            if (!moving) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text("Touches the middle of the cutting area (red ring)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { moving = true }) { Text("Move") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Touch point, from the cutting area's front-left corner", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { moving = false; r.update(s.copy(zAtX = null, zAtY = null)) }) { Text("Middle") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("X", zx, Modifier.weight(1f)) { r.update(s.copy(zAtX = it, zAtY = zy)) }
                    NumField("Y", zy, Modifier.weight(1f)) { r.update(s.copy(zAtX = zx, zAtY = it)) }
                }
            }
        }
    }
    Box3 {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scan margin", fontWeight = FontWeight.SemiBold)
                Text(when (scan.phase) {
                    ScanPhase.RUNNING -> "Scanning… watch that the dot stays on the stock"
                    ScanPhase.DONE -> "Scanned ✓"
                    ScanPhase.FAILED -> scan.note ?: "The scan stopped"
                    else -> "The laser traces the cutting area"
                }, fontSize = 12.sp, color = when (scan.phase) { ScanPhase.DONE -> Ok; ScanPhase.FAILED -> Bad; else -> MaterialTheme.colorScheme.onSurfaceVariant })
            }
            if (scan.phase == ScanPhase.RUNNING) OutlinedButton(onClick = r::stopScan, border = androidx.compose.foundation.BorderStroke(1.dp, Bad)) { Text("Stop", color = Bad) }
            else OutlinedButton(onClick = r::scanNow, enabled = e != null) { Text(if (scan.phase == ScanPhase.DONE) "Scan again" else "Scan now") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Also scan at the start of the job", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(s.marginAtStart, { r.update(s.copy(marginAtStart = it)) })
        }
    }
    Box3 {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto leveling at start", fontWeight = FontWeight.SemiBold)
                Text("Touches a grid over the cutting area; the job follows the surface", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(s.leveling, { r.update(s.copy(leveling = it)) })
        }
        if (s.leveling) {
            // One row, as before the inset was added.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                NumField("Height", s.clearance, Modifier.weight(1f)) { r.update(s.copy(clearance = it.coerceIn(1.0, 30.0))) }
                NumField("Inset", s.levelInset, Modifier.weight(1f)) { r.update(s.copy(levelInset = it.coerceIn(0.0, 50.0))) }
                NumField("X points", s.gridX.toDouble(), Modifier.weight(1f), whole = true) { r.update(s.copy(gridX = it.toInt().coerceIn(2, 10))) }
                NumField("Y points", s.gridY.toDouble(), Modifier.weight(1f), whole = true) { r.update(s.copy(gridY = it.toInt().coerceIn(2, 10))) }
            }
            Text("Height: how far above the stock each touch starts. Inset: how far in from the cutting area's " +
                "edge the outer touches are (mm). A touch that finds nothing ${Fmt.trimmed4(s.levelBelow)} mm below the stock top stops the job (Settings).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Box3(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(10.dp), content = content)

@Composable
private fun LaserBanner(vm: AppViewModel, st: MachineStatus?, needed: Boolean) {
    val r = vm.run
    val laser by r.laser.collectAsState()
    val action by vm.probe.toolAction.collectAsState()
    val (fg, bg, text) = when (laser.phase) {
        LaserPhase.OK -> Triple(Ok, OkBg, "✓ Laser probe checked" + (laser.note?.let { ": $it" } ?: ""))
        LaserPhase.CHECKING -> Triple(MaterialTheme.colorScheme.primary, Card, laser.note ?: "Checking the laser probe…")
        LaserPhase.WRONG -> Triple(Bad, BadBg, laser.note ?: "That isn't the laser probe.")
        LaserPhase.FAILED -> Triple(Bad, BadBg, "Laser probe check stopped: ${laser.note ?: ""}")
        // Nothing to say before a check runs: Start (or Scan now) checks it, and the Run page says so.
        LaserPhase.NONE -> return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(10.dp)) {
        Text(text, color = fg, fontSize = 13.sp)
        if (laser.phase == LaserPhase.WRONG) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { r.laserChoice(alreadyIn = false) }) { Text("Swap to the laser probe") }
            if (laser.other == null) TextButton(onClick = r::laserRemember) { Text("Confirm Laser Probe") }
        }
        if (laser.phase == LaserPhase.FAILED) TextButton(onClick = { r.laserAsk.value = true }) { Text("Try again") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaserSheet(vm: AppViewModel, st: MachineStatus?) {
    val r = vm.run
    val t = st?.tool
    val probeIn = r.probeNumberIn(st)
    ModalBottomSheet(onDismissRequest = r::laserCancel, containerColor = Color(0xFF111A2B)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This needs the laser probe", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(when {
                t == null -> "The machine hasn't said which tool is in."
                t == 0 -> "The machine says T0. It measures the probe on the tool setter first to confirm the laser probe."
                isProbeTool(t) -> "The machine says a probe is in (T$t). It measures it on the tool setter to tell which probe it is."
                t < 0 -> "The collet is empty."
                else -> "T$t is in the collet now."
            }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val swap = @Composable { primary: Boolean ->
                Choice("Swap to the laser probe", "Goes to the tool change spot and waits while you swap, then measures it on the setter.", primary) { r.laserChoice(false) }
            }
            val inNow = @Composable { primary: Boolean ->
                Choice("It's already in", "Sets T0 (no motion), then measures it on the setter and checks its length against the laser probe's.", primary) { r.laserChoice(true) }
            }
            if (probeIn) { inNow(true); swap(false) } else { swap(true); inNow(false) }
            TextButton(onClick = r::laserCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun Choice(title: String, sub: String, primary: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card)
        .then(if (primary) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
        .clickable(onClick = onClick).padding(12.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(sub, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- step 3: assist -------------------------------------------------------------------------------------
@Composable
private fun AssistStep(vm: AppViewModel, st: MachineStatus?) {
    Text("These stay on in the machine until turned off (here or in Studio).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val connected = vm.link.conn.collectAsState().value == ConnState.Connected
    for (a in Assist.entries) {
        val on = a.state(st?.sFields)
        Box3 {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontWeight = FontWeight.SemiBold)
                    Text(a.sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on == true, { v -> vm.link.sendLine(if (v) a.on else a.off) }, enabled = connected && on != null)
            }
        }
    }
}

// ---- step 4: run ----------------------------------------------------------------------------------------
/**
 * The summary before Start (mockups/run_page_mock.html, 2026-11-18): the time estimate and the tools,
 * the precheck routine (only when there is one), then origin and assist. Edit links go back a page.
 * Whatever stops Start is one line under the cards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunStep(vm: AppViewModel, st: MachineStatus?) {
    val r = vm.run
    val s by r.setup.collectAsState()
    val laser by r.laser.collectAsState()
    val load by r.load.collectAsState()
    val anchor by r.anchor.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ready = load as? RunController.Load.Ready
    val time = ready?.time
    val tools = ready?.tools

    // Time and tools
    if (time != null || !tools?.changes.isNullOrEmpty()) Box3 {
        time?.let { t ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(durationText(t.total), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
                Text("  machine time, estimated", fontSize = 13.sp, color = muted, modifier = Modifier.padding(bottom = 4.dp))
            }
            Rule()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tool changes", fontSize = 13.sp, color = muted, modifier = Modifier.weight(1f))
                Text("${t.toolChanges}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
            }
        }
        val rows = tools?.toolTimes().orEmpty()
        if (rows.isNotEmpty()) {
            if (time != null) Rule()
            for ((c, secs) in rows) Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Text("T${c.tool}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(36.dp))
                Column(Modifier.weight(1f)) {
                    // "Spiral O Metal 3.175*12mm Makera · flat end mill": the name, then the kind under it.
                    val d = tools?.describe(c.tool)
                    // Descriptions only come from some CAMs' comments; without one the row is just the T number.
                    if (d != null) Text(d.substringBeforeLast(" · "), fontSize = 13.sp)
                    if (d != null && " · " in d) Text(d.substringAfterLast(" · "), fontSize = 11.5.sp, color = muted)
                }
                secs?.let { Text(com.z1pendant.ui.fmtDuration(it.toLong()), fontSize = 13.sp, style = Tabular,
                    modifier = Modifier.padding(start = 8.dp)) }
            }
        }
    }

    // Precheck routine: what the machine does before the file's first line, in the order it does it.
    val pre = buildList {
        if (s.marginAtStart) add("Scan the margin" to "The laser traces the cutting area, so you can see it's on the stock")
        if (s.zOn) add("Probe Z" to ("Sets Z zero on the stock top, " +
            if (s.zAtX == null && s.zAtY == null) "at the middle of the cutting area" else "at the point you set"))
        if (s.leveling) add("Auto leveling, ${s.gridX} × ${s.gridY}" to ("Touches a grid " +
            (if (s.levelInset > 0) "${Fmt.trimmed4(s.levelInset)} mm in from the cutting area's edge" else "over the cutting area") +
            "; the job follows the surface"))
    }
    if (pre.isNotEmpty()) Box3 {
        SectionHead("Precheck routine") { r.goTo(1) }
        pre.forEachIndexed { i, (title, sub) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(Color(0xFF1E293B)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(sub, fontSize = 12.sp, color = muted)
                }
            }
        }
        // Everything in the routine uses the laser probe.
        val (c, t) = when (laser.phase) {
            LaserPhase.NONE -> muted to "Uses the laser probe. It's checked when you tap Start."
            LaserPhase.OK -> Ok to "Laser probe checked"
            LaserPhase.CHECKING -> MaterialTheme.colorScheme.primary to "Checking the laser probe…"
            LaserPhase.WRONG -> Bad to "Not the laser probe"
            LaserPhase.FAILED -> Bad to "Laser probe not confirmed"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(c))
            Text("  $t", fontSize = 12.5.sp, color = c)
        }
    }

    // Origin and assist
    Box3 {
        Row(verticalAlignment = Alignment.Top) {
            Text("Origin", fontSize = 13.sp, color = muted, modifier = Modifier.width(64.dp))
            Column(Modifier.weight(1f)) {
                Text(s.origin.label + (s.anchor?.takeIf { s.origin == Origin.CUSTOM }?.let { " · ${it.name}" } ?: ""), fontSize = 14.sp)
                val sub = when (s.origin) {
                    Origin.ANCHOR1 -> "Offset X ${Fmt.trimmed4(s.offX)} · Y ${Fmt.trimmed4(s.offY)} from the bracket's corner"
                    Origin.CUSTOM -> "Offset X ${Fmt.trimmed4(s.aOffX)} · Y ${Fmt.trimmed4(s.aOffY)} from the anchor" +
                        if (s.anchorSetsZ) "; Z from the anchor" else ""
                    Origin.CURRENT -> anchor?.let { (ax, ay) -> st?.let { m ->
                        "X ${f3(m.mpos[0] - m.wpos[0] - ax)} · Y ${f3(m.mpos[1] - m.wpos[1] - ay)} from Anchor 1" } }
                    else -> null
                }
                sub?.let { Text(it, fontSize = 12.sp, color = muted) }
            }
            EditLink { r.goTo(0) }
        }
        Rule()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Assist", fontSize = 13.sp, color = muted, modifier = Modifier.width(64.dp))
            val on = Assist.entries.filter { it.state(st?.sFields) == true }
            if (on.isEmpty()) Text("None on", fontSize = 14.sp, color = muted, modifier = Modifier.weight(1f))
            else FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in on) Text(a.label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 3.dp))
            }
            EditLink { r.goTo(2) }
        }
    }

    // Start job runs the laser probe check from here: its progress, the continue button for the swap,
    // and a wrong / failed result (a match is the green line above).
    if (laser.phase != LaserPhase.NONE && laser.phase != LaserPhase.OK) LaserBanner(vm, st, needed = false)
    // Still loading, off the bed, cuts before its first M6 ...: only said when it stops the start. Not
    // Idle isn't said (Start is just off).
    r.startNote(st)?.let { Text(it, fontSize = 13.sp, color = Warn) }
}

/** Same-width digits in the normal font, so times line up without looking like a terminal. */
private val Tabular = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

/** "13m 22s" with the unit letters smaller and muted. */
@Composable
private fun durationText(secs: Double) = androidx.compose.ui.text.buildAnnotatedString {
    val unit = androidx.compose.ui.text.SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (ch in com.z1pendant.ui.fmtDuration(secs.toLong())) {
        if (ch.isLetter()) { pushStyle(unit); append(ch); pop() } else append(ch)
    }
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Color(0x2494A3B8)))

@Composable
private fun SectionHead(title: String, onEdit: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    EditLink(onEdit)
}

@Composable
private fun EditLink(onClick: () -> Unit) = Text("Edit", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp))

/** A number box that keeps what you type until it parses. */
@Composable
private fun NumField(label: String, value: Double, modifier: Modifier = Modifier, whole: Boolean = false, onChange: (Double) -> Unit) {
    fun show(v: Double) = if (whole) v.toInt().toString() else Fmt.fixed(v, 3).trimEnd('0').trimEnd('.')
    var text by remember { mutableStateOf(show(value)) }
    // Only replace what's typed when the value changed from elsewhere (Same as CAM, a new file).
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = show(value) }
    OutlinedTextField(text, { t -> text = t; t.toDoubleOrNull()?.let(onChange) }, label = { Text(label, fontSize = 11.sp) },
        singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (whole) KeyboardType.Number else KeyboardType.Decimal),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp))
}

// ---- leveling mesh ------------------------------------------------------------------------------------------
/** The active leveling grid (`M375.1`), as a heat map. Read-only; nothing moves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshSheet(vm: AppViewModel, onClose: () -> Unit) {
    var grid by remember { mutableStateOf<List<List<Double>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { grid = vm.run.readMesh(); failed = grid == null }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color(0xFF111A2B)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Leveling mesh · this job", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            val g = grid
            when {
                g != null && Mesh.largest(g) == null -> Text("The grid is empty: no points have a value, so it corrects nothing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                g != null -> {
                    val all = g.flatten().filter { it.isFinite() }; val lo = all.min(); val hi = all.max()
                    Text("${g[0].size} × ${g.size} points · spread ${Fmt.fixed(Mesh.spread(g), 3)} mm", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Back row on top, like the bed map.
                    for (row in g.reversed()) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (v in row) {
                            val t = if (hi > lo) ((v - lo) / (hi - lo)).toFloat() else 0.5f
                            val c = if (v.isFinite()) androidx.compose.ui.graphics.lerp(Color(0xFF2563EB), Color(0xFFF97316), t) else Color(0xFF333B4A)
                            Text(if (v.isFinite()) Fmt.fixed(v, 3) else "–", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(c).padding(vertical = 8.dp))
                        }
                    }
                    Text("lowest ${Fmt.fixed(lo, 3)} · highest ${Fmt.fixed(hi, 3)} mm", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                failed -> Text("The machine didn't send a grid.", color = Bad)
                else -> Text("Reading the grid from the machine…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

// ---- tool change popup -------------------------------------------------------------------------------------
/**
 * The machine waits in a tool change (state Tool): which tool to put in (`|T:` third field), what it
 * is (the job file's tool list) and Continue (`M490.2`, the same as the machine's button). Also
 * shows after a reconnect, since the state and the wanted tool come with the first status.
 */
@Composable
fun ToolChangePopup(vm: AppViewModel) {
    val st by vm.link.status.collectAsState()
    val tools by vm.jobTools.collectAsState()
    val job by vm.job.collectAsState()
    val waiting = st?.state.equals("Tool", true)
    var hidden by remember { mutableStateOf<String?>(null) }
    val key = "${st?.toolTarget}:${job?.progress?.line}"
    if (!waiting || hidden == key) return
    val target = st?.toolTarget
    val name = when (target) { 0 -> "the laser probe"; com.z1pendant.probe.PROBE_TOOL -> "the 3D probe"; null -> "the next tool"; else -> "T$target" }
    androidx.compose.ui.window.Dialog(onDismissRequest = { hidden = key }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111A2B)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("Tool change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                job?.let { Text("line ${Fmt.thousands(it.progress.line.toInt())}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("Put in $name", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            target?.takeIf { it > 0 }?.let { t -> tools?.describe(t)?.let { Text(it, fontSize = 14.sp) } }
            st?.tool?.takeIf { it > 0 && it != target }?.let { t ->
                Text("Take out T$t" + (tools?.describe(t)?.let { " · $it" } ?: ""), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("After you continue, the Z1 measures it on the tool setter, then carries on.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.haptics.press(); vm.link.sendLine("M490.2") }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (target != null && target > 0) "I've put in T$target · continue" else "It's in · continue", fontSize = 16.sp)
            }
            TextButton(onClick = { hidden = key }) { Text("Hide") }
        }
    }
}
