package com.z1pendant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.link.Bed
import com.z1pendant.link.ConnState
import com.z1pendant.map.BedMap
import com.z1pendant.map.MapGrid
import com.z1pendant.map.MapMove
import com.z1pendant.map.MapTarget
import com.z1pendant.map.TargetKind
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.util.Fmt
import kotlin.math.hypot

private val MapBg = Color(0xFF111A2B)
private val BedFill = Color(0xFF1B2536)
private val BedEdge = Color(0xFF475569)
private val GridLine = Color(0xFF22304A)
private val PointC = Color(0xFF64748B)
private val Blue = Color(0xFF38BDF8)
private val ZeroC = Color(0xFF22C55E)
private val AnchorC = Color(0xFFA78BFA)
private val SetterC = Color(0xFFF59E0B)
private val CardBg = Color(0xFF1E293B)
private val MutedC = Color(0xFF94A3B8)
private val ErrC = Color(0xFFF87171)

/**
 * The bed map ("Go to", field_test/bed_map_spec.md): the machine's whole X/Y travel from the soft
 * limits, back at the top and front at the bottom, with a grid of points, your anchors, X0 Y0, the
 * tool setter and the spindle. Tap a point, an anchor or X0 Y0 to pick it; Move here lifts Z all
 * the way up, then goes there. Full screen like probing, so the jog keys and the dial aren't live.
 */
@Composable
fun MapScreen(vm: AppViewModel, onClose: () -> Unit) {
    val st by vm.link.status.collectAsState()
    val conn by vm.link.conn.collectAsState()
    val limits by vm.softLimits.collectAsState()
    val grid by vm.mapGrid.collectAsState()
    val anchors by vm.anchors.list.collectAsState()
    val setter by vm.probe.setterXY.collectAsState()
    val move by vm.mapMove.collectAsState()
    val connected = conn == ConnState.Connected
    val bed = limits.bed ?: Bed.Z1
    // X0 Y0 in machine coordinates: machine − work, from the status.
    val zero = st?.let { s -> (s.mpos[0] - (s.wpos.getOrNull(0) ?: 0.0)) to (s.mpos[1] - (s.wpos.getOrNull(1) ?: 0.0)) }
    val targets = BedMap.targets(bed, grid, anchors, zero)
    var picked by remember { mutableStateOf<MapTarget?>(null) }
    // Safe Z: on every time the map opens. Off leaves Z where it is (sweeping a part with an indicator).
    var safeZ by remember { mutableStateOf(true) }
    // A picked target that's gone (grid changed, anchor deleted): drop it. X0 Y0 follows the zero.
    val sel = picked?.let { p ->
        if (p.has(TargetKind.ZERO)) targets.firstOrNull { it.has(TargetKind.ZERO) }
        else targets.firstOrNull { kotlin.math.abs(it.x - p.x) < BedMap.SAME && kotlin.math.abs(it.y - p.y) < BedMap.SAME }
    }
    val moving = move?.let { it.stage != MapMove.Stage.DONE && it.error == null } == true
    fun close() { if (!moving) { vm.mapClear(); onClose() } }
    PlatformBackHandler { close() }

    // One screen, no scrolling: the map takes whatever height the rest leaves (Jimmy, 2026-11-27).
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MapIcon(Blue, Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (moving) "Moving" else "Go to", fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = ::close, enabled = !moving) { Icon(Icons.Filled.Close, "Close") }
        }
        Row(Modifier.clip(RoundedCornerShape(50)).background(CardBg).padding(horizontal = 11.dp, vertical = 5.dp)) {
            Text("Spindle  X ${pos(st?.mpos?.getOrNull(0), connected)}  Y ${pos(st?.mpos?.getOrNull(1), connected)}" +
                (if (moving) "  Z ${pos(st?.mpos?.getOrNull(2), connected)}" else "  (machine)"),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MutedC)
        }
        Segmented(MapGrid.entries.map { it.label }, grid.ordinal) { if (!moving) { vm.setMapGrid(MapGrid.entries[it]); vm.haptics.press() } }
        BedCanvas(Modifier.weight(1f).fillMaxWidth(), bed, grid, targets, sel, st?.mpos?.let { it[0] to it[1] }, setter,
            onTap = { x, y ->
                if (moving) return@BedCanvas
                // Within half the grid spacing (at least 6 mm) of a target.
                val reach = maxOf(minOf(bed.width, bed.height) / (grid.n - 1) / 2, 6.0)
                BedMap.nearest(targets, x, y, reach)?.let { picked = it; vm.mapClear(); vm.haptics.press() }
            })
        Legend(setter != null)
        val m = move
        Box(Modifier.fillMaxWidth().height(PANEL_H), contentAlignment = Alignment.TopCenter) { when {
            m != null && (moving || m.error != null || (sel != null && kotlin.math.abs(m.target.x - sel.x) < BedMap.SAME && kotlin.math.abs(m.target.y - sel.y) < BedMap.SAME)) ->
                MoveCard(vm, m, st?.isAlarm == true)
            sel != null -> TargetCard(vm, sel, st?.mpos, zero, safeZ) { safeZ = it; vm.haptics.press() }
            else -> Text("Tap a point, an anchor or X0 Y0 to pick it.", fontSize = 13.sp, color = MutedC,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } }
    }
}

/** The bottom panel's fixed height: the target card with Safe Z off (its longest text) and Move here. */
private val PANEL_H = 262.dp

private fun pos(v: Double?, connected: Boolean) = if (v == null || !connected) "---.-" else Fmt.fixed(v, 1)

@Composable
private fun Legend(setter: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun item(c: Color, mark: String, t: String) = Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mark, color = c, fontSize = 12.sp); Spacer(Modifier.width(4.dp)); Text(t, fontSize = 11.5.sp, color = MutedC)
        }
        item(Blue, "◎", "spindle"); item(ZeroC, "⊕", "X0 Y0"); item(AnchorC, "◆", "anchors")
        if (setter) item(SetterC, "▣", "tool setter")
    }
}

/** The picked target: where it is, what Move does, and Move here. */
@Composable
private fun TargetCard(vm: AppViewModel, t: MapTarget, mpos: List<Double>?, zero: Pair<Double, Double>?, safeZ: Boolean, onSafeZ: (Boolean) -> Unit) {
    val blocked = vm.mapBlocked()
    val dist = mpos?.let { hypot(it[0] - t.x, it[1] - t.y) }
    val here = dist != null && dist < 0.01
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Every name on this spot as a chip: an anchor where X0 Y0 is shows both.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            t.names.forEach { n ->
                val (fg, bg, mark) = when (n.kind) {
                    TargetKind.ANCHOR -> Triple(Color(0xFFC4B5FD), Color(0xFF2A2250), "◆ ")
                    TargetKind.ZERO -> Triple(Color(0xFF86EFAC), Color(0xFF123522), "⊕ ")
                    TargetKind.GRID -> Triple(MutedC, Color(0xFF263244), "")
                }
                Text(mark + n.name, fontSize = 12.5.sp, color = fg, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 2.dp))
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("X ${Fmt.fixed(t.x, 1)}  Y ${Fmt.fixed(t.y, 1)}", fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text("machine", fontSize = 11.sp, color = MutedC)
        }
        val work = zero?.let { "work X ${Fmt.fixed(t.x - it.first, 3)}  Y ${Fmt.fixed(t.y - it.second, 3)}" }
        Text(listOfNotNull(work, dist?.let { if (here) "the spindle is here" else "${Fmt.fixed(it, 0)} mm from the spindle" }).joinToString(" · "),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MutedC)
        // Safe Z, right above Move here: on lifts Z to the top first; off moves X Y at the height it's at.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSafeZ(!safeZ) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Safe Z", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(if (safeZ) "Lifts Z to the top (Z ${Fmt.fixed(BedMap.SAFE_Z, 0)}) first, then moves X Y."
                    else "Z stays at ${mpos?.let { Fmt.fixed(it[2], 3) } ?: "where it is"}: X Y move at this height. Make sure the way is clear.",
                    fontSize = 12.5.sp, color = if (safeZ) MutedC else SetterC, lineHeight = 16.sp)
            }
            androidx.compose.material3.Switch(checked = safeZ, onCheckedChange = onSafeZ)
        }
        Button(onClick = { vm.mapGo(t, safeZ) }, enabled = blocked == null && !here, modifier = Modifier.fillMaxWidth().height(46.dp)) {
            Text("Move here", fontSize = 16.sp)
        }
        blocked?.let { Text(it, fontSize = 12.sp, color = SetterC) }
    }
}

/** A move from the map: its two steps and Stop; then arrived, or what went wrong. */
@Composable
private fun MoveCard(vm: AppViewModel, m: MapMove, alarm: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val t = m.target
        @Composable fun step(done: Boolean, now: Boolean, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                done -> Icon(Icons.Filled.CheckCircle, null, tint = ZeroC, modifier = Modifier.size(20.dp))
                now -> Icon(Icons.Filled.RadioButtonChecked, null, tint = Blue, modifier = Modifier.size(20.dp))
                else -> Box(Modifier.size(20.dp).padding(2.dp).clip(RoundedCornerShape(50)).background(Color(0xFF334155)))
            }
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 14.sp, fontWeight = if (now) FontWeight.Medium else FontWeight.Normal, color = if (done || now) Color.Unspecified else MutedC)
        }
        val lifted = m.stage != MapMove.Stage.LIFT
        if (m.safeZ) step(lifted, m.stage == MapMove.Stage.LIFT && m.error == null, "Lift Z to the top")
        step(m.stage == MapMove.Stage.DONE, m.stage == MapMove.Stage.MOVE && m.error == null,
            "Move to ${t.label}  (X ${Fmt.fixed(t.x, 1)} Y ${Fmt.fixed(t.y, 1)})")
        when {
            m.error != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Error, null, tint = ErrC, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(m.error, fontSize = 13.sp, color = ErrC)
                }
                if (alarm) Button(onClick = { vm.link.dialect.unlock().forEach { vm.link.sendLine(it) }; vm.mapClear() }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Icon(Icons.Filled.LockOpen, null); Spacer(Modifier.width(6.dp)); Text("Unlock")
                }
            }
            m.stage == MapMove.Stage.DONE -> Text("Arrived. Pick another spot, or close the map.", fontSize = 13.sp, color = ZeroC)
            else -> Button(onClick = { vm.mapStop() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The map itself, in machine coordinates: x grows right, y grows toward the back (up on screen), as
 * big as fits in [modifier]'s space. A 10 mm line grid, the grid points, the tool setter, anchors and
 * X0 Y0 with their names, then the spindle on top with a dashed line to the picked target. Marks on
 * the same spot nest (the X0 Y0 ring goes around an anchor's diamond, the spindle ring around both)
 * and their names stack beside them.
 */
@Composable
private fun BedCanvas(modifier: Modifier, bed: Bed, grid: MapGrid, targets: List<MapTarget>, sel: MapTarget?, spindle: Pair<Double, Double>?,
                      setter: Pair<Double, Double>?, onTap: (Double, Double) -> Unit) {
    val pad = 14f
    val ratio = (bed.width / bed.height).toFloat().coerceIn(0.5f, 2f)
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val w = minOf(maxWidth, maxHeight * ratio); val h = w / ratio
        Box(Modifier.size(w, h).clip(RoundedCornerShape(12.dp)).background(MapBg).padding(6.dp)) {
            Canvas(Modifier.fillMaxSize().pointerInput(bed, grid, targets) {
                detectTapGestures { o ->
                    val k = (size.width - 2 * pad) / bed.width.toFloat()
                    onTap(bed.x0 + (o.x - pad) / k, bed.y1 - (o.y - pad) / k)
                }
            }) {
                val k = (size.width - 2 * pad) / bed.width.toFloat()
                fun p(x: Double, y: Double) = Offset(pad + ((x - bed.x0) * k).toFloat(), pad + ((bed.y1 - y) * k).toFloat())
                val tl = p(bed.x0, bed.y1); val br = p(bed.x1, bed.y0)
                drawRoundRect(BedFill, tl, Size(br.x - tl.x, br.y - tl.y), CornerRadius(6f))
                var v = kotlin.math.ceil(bed.x0 / 10) * 10
                while (v < bed.x1) { drawLine(GridLine, p(v, bed.y0), p(v, bed.y1), 1f); v += 10 }
                v = kotlin.math.ceil(bed.y0 / 10) * 10
                while (v < bed.y1) { drawLine(GridLine, p(bed.x0, v), p(bed.x1, v), 1f); v += 10 }
                drawRoundRect(BedEdge, tl, Size(br.x - tl.x, br.y - tl.y), CornerRadius(6f), style = Stroke(2f))
                // Sizes in dp, so the marks read the same on any phone.
                val dp = density
                fun isSel(t: MapTarget) = sel != null && kotlin.math.abs(t.x - sel.x) < BedMap.SAME && kotlin.math.abs(t.y - sel.y) < BedMap.SAME
                val dotR = (if (grid.n > 9) 2.6f else 3.6f) * dp
                targets.filter { it.kind == TargetKind.GRID }.forEach { t ->
                    val on = isSel(t)
                    drawCircle(if (on) Blue else PointC, if (on) dotR * 1.7f else dotR, p(t.x, t.y))
                }
                setter?.let { (x, y) -> val c = p(x, y); val hs = 6f * dp
                    drawRect(SetterC, Offset(c.x - hs, c.y - hs), Size(2 * hs, 2 * hs), style = Stroke(2f * dp)) }
                // Anchors and X0 Y0: nested when they share a spot, and named.
                targets.filter { it.kind != TargetKind.GRID }.forEach { t ->
                    val c = p(t.x, t.y); val on = isSel(t)
                    val both = t.has(TargetKind.ANCHOR) && t.has(TargetKind.ZERO)
                    var outer = 0f
                    if (t.has(TargetKind.ANCHOR)) {
                        val hd = (if (both) 5f else 7f) * dp
                        drawPath(Path().apply { moveTo(c.x, c.y - hd); lineTo(c.x + hd, c.y); lineTo(c.x, c.y + hd); lineTo(c.x - hd, c.y); close() }, if (on) Blue else AnchorC)
                        outer = hd
                    }
                    if (t.has(TargetKind.ZERO)) {
                        val col = if (on) Blue else ZeroC
                        val r = (if (both) 9.5f else 5f) * dp; val arm = (if (both) 16f else 10f) * dp
                        drawCircle(col, r, c, style = Stroke(1.8f * dp))
                        // Around a diamond the cross stops at the ring, so the diamond stays clear.
                        val inner = if (both) r + 1.5f * dp else 0f
                        drawLine(col, c + Offset(-arm, 0f), c + Offset(-inner, 0f), 1.6f * dp); drawLine(col, c + Offset(inner, 0f), c + Offset(arm, 0f), 1.6f * dp)
                        drawLine(col, c + Offset(0f, -arm), c + Offset(0f, -inner), 1.6f * dp); drawLine(col, c + Offset(0f, inner), c + Offset(0f, arm), 1.6f * dp)
                        outer = arm
                    }
                    // Names beside the mark, one per line (right of it, or left near the right edge).
                    val texts = t.names.filter { it.kind != TargetKind.GRID }.map { n ->
                        val col = if (on) Color(0xFF7DD3FC) else if (n.kind == TargetKind.ANCHOR) Color(0xFFC4B5FD) else Color(0xFF86EFAC)
                        tm.measure(n.name, androidx.compose.ui.text.TextStyle(color = col, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                    }
                    val lineH = texts.maxOfOrNull { it.size.height } ?: 0
                    val wMax = texts.maxOfOrNull { it.size.width } ?: 0
                    val gap = outer + 4f * dp
                    val left = c.x + gap + wMax > size.width - 2f
                    var y0 = c.y - lineH * texts.size / 2f
                    texts.forEach { tl2 ->
                        val x0 = if (left) c.x - gap - tl2.size.width else c.x + gap
                        drawText(tl2, topLeft = Offset(x0, y0)); y0 += lineH
                    }
                }
                // The spindle last, a ring bigger than any mark, so it shows around whatever it's on.
                spindle?.let { (x, y) ->
                    val c = p(x, y)
                    sel?.let { s -> drawLine(Blue, c, p(s.x, s.y), 1.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * dp, 4f * dp))) }
                    drawCircle(Blue, 20f * dp, c, alpha = 0.16f)
                    drawCircle(Color.White, 13f * dp, c, style = Stroke(2.4f * dp))
                    drawCircle(Blue, 13f * dp, c, style = Stroke(1.4f * dp))
                    drawCircle(Color.White, 2.2f * dp, c)
                }
            }
        }
    }
}

/** The map icon (a folded map), for its button and the map's title. */
@Composable
fun MapIcon(tint: Color, modifier: Modifier) = Canvas(modifier) {
    val u = size.width / 24f
    val outline = Path().apply {
        moveTo(3 * u, 6.5f * u); lineTo(9 * u, 4 * u); lineTo(15 * u, 6.5f * u); lineTo(21 * u, 4 * u); lineTo(21 * u, 17.5f * u)
        lineTo(15 * u, 20 * u); lineTo(9 * u, 17.5f * u); lineTo(3 * u, 20 * u); close()
    }
    drawPath(outline, tint, style = Stroke(1.8f * u, join = StrokeJoin.Round))
    drawLine(tint, Offset(9 * u, 4 * u), Offset(9 * u, 17.5f * u), 1.6f * u, StrokeCap.Round)
    drawLine(tint, Offset(15 * u, 6.5f * u), Offset(15 * u, 20 * u), 1.6f * u, StrokeCap.Round)
}

/** The map button on the main screen (dial: its bottom-right corner; jog pad: right of the dial/pad toggle). */
@Composable
fun MapButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .clickable(onClick = onClick).semantics { contentDescription = "Bed map" }, contentAlignment = Alignment.Center) {
        MapIcon(MaterialTheme.colorScheme.primary, Modifier.size(28.dp))
    }
}
