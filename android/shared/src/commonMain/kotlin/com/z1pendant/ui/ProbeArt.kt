package com.z1pendant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.z1pendant.probe.Corner
import com.z1pendant.probe.ProbeKind
import com.z1pendant.probe.ProbeTool
import com.z1pendant.probe.edgeTouchLabels
import com.z1pendant.probe.finishLabel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Drawings for the probe wizard, matching mockups/probe_mock.html (style "D2": a small 3D part with
// the zero crosshair lying in the surface where zero lands). Coordinates are in a design box that
// is scaled to fit the canvas.
private val BL = Color(0xFF38BDF8)
private val ST = Color(0xFF94A3B8)
private val FILL = Color(0xFF334155)
private val DARK = Color(0xFF0B1120)
private val TOPC = Color(0xFF475569)
private val RFACE = Color(0xFF253041)
private val WALL1 = Color(0xFF2B3648)
private val WALL2 = Color(0xFF222C3C)
private val STEEL = Color(0xFFCBD5E1)
private val BODY = Color(0xFF94A3B8)
private val COLLAR = Color(0xFF64748B)
/** The rod's and the laser probe's black plastic, lifted a little so it shows on the dark tiles. */
private val HUB = Color(0xFF1E293B)
private val HUB_EDGE = Color(0xFF64748B)
/** The 3D probe's ruby ball. */
private val RUBY = Color(0xFFE0457B)

/** Draw [block] in a design box [vx, vy, vw, vh], scaled to fit this canvas and centred. */
private fun DrawScope.fit(vx: Float, vy: Float, vw: Float, vh: Float, block: DrawScope.() -> Unit) {
    val k = min(size.width / vw, size.height / vh)
    translate((size.width - vw * k) / 2 - vx * k, (size.height - vh * k) / 2 - vy * k) {
        scale(k, k, pivot = Offset.Zero) { block() }
    }
}

// ---- isometric part icons ------------------------------------------------------------------
private class Iso(val ox: Float, val oy: Float, val k: Float) {
    fun p(x: Float, y: Float, z: Float) = Offset(ox + (x - y) * 0.866f * k, oy - (x + y) * 0.5f * k - z * k)
    fun path(pts: List<FloatArray>) = Path().apply {
        pts.forEachIndexed { i, q -> val o = p(q[0], q[1], q[2]); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close()
    }
}

private fun circ(cx: Float, cy: Float, z: Float, r: Float, a0: Float = 0f, a1: Float = 360f, n: Int = 40) =
    (0..n).map { i -> val t = ((a0 + (a1 - a0) * i / n) * PI / 180).toFloat(); floatArrayOf(cx + r * cos(t), cy + r * sin(t), z) }
private fun sq(cx: Float, cy: Float, z: Float, h: Float) =
    listOf(floatArrayOf(cx - h, cy - h, z), floatArrayOf(cx + h, cy - h, z), floatArrayOf(cx + h, cy + h, z), floatArrayOf(cx - h, cy + h, z))

private fun DrawScope.poly(iso: Iso, pts: List<FloatArray>, fill: Color, edge: Boolean = true) {
    val path = iso.path(pts)
    drawPath(path, fill)
    if (edge) drawPath(path, ST, style = Stroke(0.8f, join = StrokeJoin.Round))
}

private fun DrawScope.box(iso: Iso, x0: Float, y0: Float, z0: Float, w: Float, d: Float, h: Float, top: Boolean = true) {
    poly(iso, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0 + d, z0), floatArrayOf(x0, y0 + d, z0 + h), floatArrayOf(x0, y0, z0 + h)), FILL)
    poly(iso, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0 + w, y0, z0), floatArrayOf(x0 + w, y0, z0 + h), floatArrayOf(x0, y0, z0 + h)), RFACE)
    if (top) poly(iso, listOf(floatArrayOf(x0, y0, z0 + h), floatArrayOf(x0 + w, y0, z0 + h), floatArrayOf(x0 + w, y0 + d, z0 + h), floatArrayOf(x0, y0 + d, z0 + h)), TOPC)
}

/** Top face of a w×d slab at height h with [hole] cut out of it. */
private fun DrawScope.topWithHole(iso: Iso, w: Float, d: Float, h: Float, hole: List<FloatArray>) {
    val p = iso.path(listOf(floatArrayOf(0f, 0f, h), floatArrayOf(w, 0f, h), floatArrayOf(w, d, h), floatArrayOf(0f, d, h)))
    p.addPath(iso.path(hole)); p.fillType = PathFillType.EvenOdd
    drawPath(p, TOPC); drawPath(p, ST, style = Stroke(0.8f))
}

/** The zero marker: a ring and a crosshair lying in the plane z, along the part's X and Y. */
private fun DrawScope.zero(iso: Iso, x: Float, y: Float, z: Float) {
    drawPath(iso.path(circ(x, y, z, 2.6f)), BL, style = Stroke(1.5f))
    drawLine(BL, iso.p(x - 5.5f, y, z), iso.p(x + 5.5f, y, z), 1.4f, StrokeCap.Round)
    drawLine(BL, iso.p(x, y - 5.5f, z), iso.p(x, y + 5.5f, z), 1.4f, StrokeCap.Round)
    drawPath(iso.path(circ(x, y, z, 0.9f, n = 16)), BL)
}

/** A probe type as a small 3D part with where zero lands. [square] picks the square hole / block. */
@Composable
fun ProbeKindArt(kind: ProbeKind, size: Dp, square: Boolean = false) = Canvas(Modifier.size(size)) {
    fit(0f, 0f, 60f, 60f) {
        when (kind) {
            ProbeKind.OUTSIDE -> { val iso = Iso(30f, 47f, 1.55f); box(iso, 0f, 0f, 0f, 17f, 17f, 7f); zero(iso, 0f, 0f, 7f) }
            ProbeKind.INSIDE -> {
                val iso = Iso(30f, 51f, 1.42f); val h = 7f; val a = 6f; val b = 18f; val d = 5f
                val op = listOf(floatArrayOf(a, a, h), floatArrayOf(b, a, h), floatArrayOf(b, b, h), floatArrayOf(a, b, h))
                box(iso, 0f, 0f, 0f, 21f, 21f, h, top = false)
                clipPath(iso.path(op)) {
                    poly(iso, listOf(floatArrayOf(b, a, h - d), floatArrayOf(b, b, h - d), floatArrayOf(b, b, h), floatArrayOf(b, a, h)), WALL1)
                    poly(iso, listOf(floatArrayOf(a, b, h - d), floatArrayOf(b, b, h - d), floatArrayOf(b, b, h), floatArrayOf(a, b, h)), WALL2)
                    poly(iso, listOf(floatArrayOf(a, a, h - d), floatArrayOf(b, a, h - d), floatArrayOf(b, b, h - d), floatArrayOf(a, b, h - d)), DARK)
                }
                topWithHole(iso, 21f, 21f, h, op)
                zero(iso, a, a, h)
            }
            ProbeKind.HOLE -> {
                val iso = Iso(30f, 52f, 1.4f); val h = 7f; val c = 11f; val r = 7f
                val op = if (square) sq(c, c, h, r) else circ(c, c, h, r)
                val fl = if (square) sq(c, c, h - 8f, r) else circ(c, c, h - 8f, r)
                box(iso, 0f, 0f, 0f, 22f, 22f, h, top = false)
                clipPath(iso.path(op)) { drawPath(iso.path(op), WALL1); drawPath(iso.path(fl), DARK) }
                topWithHole(iso, 22f, 22f, h, op)
                zero(iso, c, c, h)
            }
            ProbeKind.BLOCK -> {
                val iso = Iso(30f, 53f, 1.2f); val c = 13f; val r = 6f; val z0 = 3f; val h = 8f
                box(iso, 0f, 0f, 0f, 26f, 26f, z0)
                if (square) box(iso, c - r, c - r, z0, 2 * r, 2 * r, h)
                else { poly(iso, circ(c, c, z0, r, 135f, 315f) + circ(c, c, z0 + h, r, 315f, 135f), FILL); poly(iso, circ(c, c, z0 + h, r), TOPC) }
                zero(iso, c, c, z0 + h)
            }
            ProbeKind.TOP -> { val iso = Iso(30f, 52f, 1.45f); box(iso, 0f, 0f, 0f, 20f, 20f, 6f); zero(iso, 10f, 10f, 6f) }
            ProbeKind.EDGE -> {
                // The part with one top edge in blue and two touch marks on it.
                val iso = Iso(30f, 48f, 1.5f); box(iso, 0f, 0f, 0f, 20f, 17f, 7f)
                drawLine(BL, iso.p(0f, 0f, 7f), iso.p(20f, 0f, 7f), 2.2f, StrokeCap.Round)
                for (x in listOf(5.5f, 14.5f)) drawPath(iso.path(circ(x, -2.2f, 7f, 1.6f, n = 20)), BL)
            }
        }
    }
}

/** Edge picker: the part from above with the chosen side in blue and the probe outside it. */
@Composable
fun EdgeArt(side: com.z1pendant.probe.Side, size: Dp) = Canvas(Modifier.size(size)) {
    fit(0f, 0f, 60f, 60f) {
        drawRoundRect(FILL, Offset(14f, 14f), Size(32f, 32f), CornerRadius(1f))
        drawRoundRect(ST, Offset(14f, 14f), Size(32f, 32f), CornerRadius(1f), style = Stroke(1.4f))
        val (a, b, dot) = when (side) {
            com.z1pendant.probe.Side.FRONT -> Triple(Offset(14f, 46f), Offset(46f, 46f), Offset(30f, 53f))
            com.z1pendant.probe.Side.BACK -> Triple(Offset(14f, 14f), Offset(46f, 14f), Offset(30f, 7f))
            com.z1pendant.probe.Side.LEFT -> Triple(Offset(14f, 14f), Offset(14f, 46f), Offset(7f, 30f))
            com.z1pendant.probe.Side.RIGHT -> Triple(Offset(46f, 14f), Offset(46f, 46f), Offset(53f, 30f))
        }
        drawLine(BL, a, b, 3.2f, StrokeCap.Round)
        drawCircle(BL, 3.4f, dot)
    }
}

/** Corner picker: the part from above with a crosshair on the chosen corner. [inside] = pocket. */
@Composable
fun CornerArt(corner: Corner, inside: Boolean, size: Dp) = Canvas(Modifier.size(size)) {
    fit(0f, 0f, 60f, 60f) {
        val cx = if (corner.left) 14f else 46f; val cy = if (corner.back) 14f else 46f
        if (inside) {
            drawRoundRect(FILL, Offset(4f, 4f), Size(52f, 52f), CornerRadius(2f))
            drawRect(DARK, Offset(14f, 14f), Size(32f, 32f)); drawRect(ST, Offset(14f, 14f), Size(32f, 32f), style = Stroke(1.4f))
        } else {
            drawRoundRect(FILL, Offset(14f, 14f), Size(32f, 32f), CornerRadius(1f))
            drawRoundRect(ST, Offset(14f, 14f), Size(32f, 32f), CornerRadius(1f), style = Stroke(1.4f))
        }
        drawCircle(BL, 3.4f, Offset(cx, cy), style = Stroke(1.5f))
        drawLine(BL, Offset(cx - 6.5f, cy), Offset(cx + 6.5f, cy), 1.5f); drawLine(BL, Offset(cx, cy - 6.5f), Offset(cx, cy + 6.5f), 1.5f)
    }
}

// ---- probe pictures ------------------------------------------------------------------------
/**
 * Side view of [tool] with its touching end (blue) reaching (cx, by + r): the 3D probe's ball of radius
 * r centred at (cx, by); the probe rod's 2r-wide pin under its black hub; the laser probe's spring pin
 * under its block.
 */
private fun DrawScope.probeSide(cx: Float, by: Float, r: Float, sc: Float, tool: ProbeTool = ProbeTool.PROBE_3D) {
    when (tool) {
        ProbeTool.ROD -> {
            val bottom = by + r; val pinTop = by - r - 9 * sc; val hw = 12 * sc; val hh = 11 * sc
            drawRect(STEEL, Offset(cx - 2.2f * sc, pinTop - hh - 7 * sc), Size(4.4f * sc, 7 * sc + 1))
            drawRoundRect(HUB, Offset(cx - hw / 2 - 4 * sc, pinTop - hh + 3 * sc), Size(5 * sc, 4.5f * sc), CornerRadius(1f * sc))
            drawRoundRect(HUB, Offset(cx - hw / 2, pinTop - hh), Size(hw, hh), CornerRadius(2f * sc))
            drawRoundRect(HUB_EDGE, Offset(cx - hw / 2, pinTop - hh), Size(hw, hh), CornerRadius(2f * sc), style = Stroke(0.8f))
            drawRoundRect(BL, Offset(cx - r, pinTop), Size(2 * r, bottom - pinTop), CornerRadius(min(r * 0.35f, 1.2f * sc)))
            return
        }
        ProbeTool.LASER -> {
            val bottom = by + r; val pw = maxOf(r, 2.4f * sc); val pinTop = bottom - 7 * sc
            val bw = 16 * sc; val bh = 15 * sc; val top = pinTop - 3 * sc - bh
            drawRect(STEEL, Offset(cx + 2 * sc, top - 6 * sc), Size(3.5f * sc, 6 * sc + 1))
            val body = Path().apply {
                moveTo(cx - bw / 2, top); lineTo(cx + bw / 2, top); lineTo(cx + bw / 2, top + bh)
                lineTo(cx - bw / 2 + 5 * sc, top + bh); lineTo(cx - bw / 2, top + bh - 5 * sc); close()
            }
            drawPath(body, HUB); drawPath(body, HUB_EDGE, style = Stroke(0.8f))
            drawCircle(Color(0xFFEF4444), 1.1f * sc, Offset(cx - bw / 2 + 4 * sc, top + bh / 2))
            drawRoundRect(STEEL, Offset(cx - 4 * sc, top + bh), Size(8 * sc, 3 * sc), CornerRadius(0.8f * sc))
            drawRoundRect(BL, Offset(cx - pw / 2, pinTop), Size(pw, bottom - pinTop), CornerRadius(pw / 3))
            return
        }
        else -> {}
    }
    // The 3D probe: shank, wide steel body with its clear band, collar, stylus, ball.
    val st = 1.8f * sc; val bw = 14 * sc; val bh = 12 * sc; val stemTop = by - r - 9 * sc
    val top = stemTop - 2 * sc - bh
    drawRect(STEEL, Offset(cx - 1.5f * sc, top - 6 * sc), Size(3 * sc, 6 * sc + 1))
    drawRoundRect(BODY, Offset(cx - bw / 2, top), Size(bw, bh), CornerRadius(1f * sc))
    drawRect(Color(0xFFE2E8F0), Offset(cx - bw / 2, top + bh * 0.42f), Size(bw, 1.3f * sc))
    drawRect(COLLAR, Offset(cx - 2 * sc, top + bh), Size(4 * sc, 2 * sc))
    drawRect(STEEL, Offset(cx - st / 2, stemTop), Size(st, 9 * sc + 1))
    drawCircle(BL, r, Offset(cx, by))
}

private fun DrawScope.probeTop(x: Float, y: Float) { drawCircle(COLLAR, 5.5f, Offset(x, y)); drawCircle(BL, 3.6f, Offset(x, y)) }
private val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 2f))
private val dot = PathEffect.dashPathEffect(floatArrayOf(2f, 1.5f))

private fun DrawScope.arrowHeads(x1: Float, y1: Float, x2: Float, y2: Float, c: Color, both: Boolean) {
    fun head(x: Float, y: Float, ux: Float, uy: Float) {
        val h = 3.4f; val w = 1.8f
        drawPath(Path().apply { moveTo(x, y); lineTo(x - ux * h - uy * w, y - uy * h + ux * w); lineTo(x - ux * h + uy * w, y - uy * h - ux * w); close() }, c)
    }
    val dx = x2 - x1; val dy = y2 - y1; val n = kotlin.math.sqrt(dx * dx + dy * dy); val ux = dx / n; val uy = dy / n
    drawLine(c, Offset(x1 + if (both) ux * 3.4f else 0f, y1 + if (both) uy * 3.4f else 0f), Offset(x2 - ux * 3.4f, y2 - uy * 3.4f), 1.4f)
    head(x2, y2, ux, uy); if (both) head(x1, y1, -ux, -uy)
}

enum class FieldArt { X, Y, DEPTH, TIP }

private val AMBER = Color(0xFFF59E0B)
private val BAD = Color(0xFFEF4444)

/** The edge drawings are made for the front edge (probe coming in from the front) and turned for the others. */
private fun edgeTurn(side: com.z1pendant.probe.Side) = when (side) {
    com.z1pendant.probe.Side.FRONT -> 0f; com.z1pendant.probe.Side.BACK -> 180f
    com.z1pendant.probe.Side.LEFT -> 90f; com.z1pendant.probe.Side.RIGHT -> -90f
}

/** Once turned, whether "+ along the edge" ([com.z1pendant.probe.ProbeSetup.alongSign] = +1) points right in the front-edge drawing. */
private fun edgeK(side: com.z1pendant.probe.Side) = if (side == com.z1pendant.probe.Side.FRONT || side == com.z1pendant.probe.Side.RIGHT) 1 else -1

/**
 * "Where does it start?": from the side, the probe over the part ready to touch the top first, or
 * already beside the part at touching height, going straight in.
 */
@Composable
fun StartArt(fromHere: Boolean, tool: ProbeTool, modifier: Modifier) = Canvas(modifier) {
    fit(0f, 0f, 70f, 42f) {
        if (!fromHere) {
            drawRoundRect(FILL, Offset(6f, 24f), Size(46f, 16f), CornerRadius(1f)); drawRoundRect(ST, Offset(6f, 24f), Size(46f, 16f), CornerRadius(1f), style = Stroke(1f))
            probeSide(30f, 13f, 3f, 0.55f, tool)
            drawLine(BL, Offset(30f, 17f), Offset(30f, 23f), 1.1f, pathEffect = dot)
        } else {
            drawRoundRect(FILL, Offset(4f, 12f), Size(40f, 28f), CornerRadius(1f)); drawRoundRect(ST, Offset(4f, 12f), Size(40f, 28f), CornerRadius(1f), style = Stroke(1f))
            drawLine(BL, Offset(44f, 12f), Offset(44f, 40f), 2f)
            probeSide(58f, 27f, 3f, 0.55f, tool)
            drawLine(BL, Offset(54f, 27f), Offset(47.5f, 27f), 1.1f, pathEffect = dot)
        }
    }
}

/**
 * The touches along an edge, from above: the part, the [side] in blue, [count] touches going the way
 * [sign] picks (the first one filled), and the length in amber with an arrow toward the last. Touches
 * in [bad] (past the machine's travel) are red.
 */
@Composable
fun EdgePointsArt(side: com.z1pendant.probe.Side, sign: Int, count: Int, modifier: Modifier, bad: Set<Int> = emptySet(), showWay: Boolean = true) = Canvas(modifier) {
    fit(0f, 0f, 60f, 60f) {
        rotate(edgeTurn(side), pivot = Offset(30f, 30f)) {
            drawRoundRect(FILL, Offset(6f, 6f), Size(48f, 30f), CornerRadius(1f)); drawRoundRect(ST, Offset(6f, 6f), Size(48f, 30f), CornerRadius(1f), style = Stroke(1f))
            drawLine(BL, Offset(6f, 36f), Offset(54f, 36f), 2f)
            val n = count.coerceAtLeast(1)
            val fwd = edgeK(side) * sign > 0
            val xs = (0 until n).map { i -> if (n == 1) 30f else 11f + 38f * i / (n - 1) }.let { if (fwd) it else it.reversed() }
            xs.forEachIndexed { i, x ->
                val c = if (i in bad) BAD else BL
                if (i == 0 && showWay) drawCircle(c, 2.8f, Offset(x, 40f)) else drawCircle(c, 2.4f, Offset(x, 40f), style = Stroke(1f))
            }
            if (n > 1 && showWay) {
                val a = xs.first(); val b = xs.last()
                drawLine(AMBER, Offset(a, 47f), Offset(a, 53f), 0.8f)
                drawLine(AMBER, Offset(b, 47f), Offset(b, 53f), 0.8f)
                arrowHeads(a, 50f, b, 50f, if (bad.isEmpty()) AMBER else BAD, false)
            }
        }
    }
}

/**
 * The edge's X / Y field: touching the top first, the ball over the part, out past the edge (dotted
 * ring) and back in; started beside the part, the ball outside going straight in, how far it searches
 * in amber. Drawn for the front edge and turned to [side].
 */
private fun DrawScope.edgeOffsetArt(side: com.z1pendant.probe.Side, fromHere: Boolean) {
    fit(0f, 0f, 60f, 60f) {
        rotate(edgeTurn(side), pivot = Offset(30f, 30f)) {
            drawRoundRect(FILL, Offset(6f, 4f), Size(48f, 30f), CornerRadius(1f)); drawRoundRect(ST, Offset(6f, 4f), Size(48f, 30f), CornerRadius(1f), style = Stroke(1.2f))
            drawLine(BL, Offset(6f, 34f), Offset(54f, 34f), 2f)
            if (!fromHere) {
                drawLine(BL, Offset(30f, 26f), Offset(30f, 52f), 1.5f, pathEffect = dash)
                drawCircle(BL, 3.6f, Offset(30f, 55f), style = Stroke(1.2f, pathEffect = dot))
                probeTop(30f, 26f)
            } else {
                drawLine(BL, Offset(30f, 50f), Offset(30f, 38f), 1.5f, pathEffect = dash)
                arrowHeads(30f, 46f, 30f, 37.5f, BL, false)
                probeTop(30f, 52f)
                drawLine(AMBER, Offset(40f, 34f), Offset(46f, 34f), 0.8f); drawLine(AMBER, Offset(40f, 52f), Offset(46f, 52f), 0.8f)
                arrowHeads(43f, 52f, 43f, 34f, AMBER, true)
            }
        }
    }
}

/**
 * The X / Y field picture for an inside corner, hole or block center, drawn for X in a 60 × 40 box
 * (top view); Y is the same turned a quarter so the move points toward the front. Outside corner
 * has its own (below).
 *  - Inside corner: parked on the part beside the pocket, it moves this far into the pocket.
 *  - Hole: from the middle, it searches this far out to each wall.
 *  - Block: from over the middle, it steps this far out past each side.
 */
private fun DrawScope.offsetArt(kind: ProbeKind, isY: Boolean) {
    val box = if (isY) Offset(40f, 60f) else Offset(60f, 40f)
    fit(0f, 0f, box.x, box.y) {
        val draw: DrawScope.() -> Unit = {
            when (kind) {
                ProbeKind.INSIDE -> {
                    drawRoundRect(FILL, Offset(0f, 2f), Size(60f, 36f), CornerRadius(1.5f))
                    drawRect(DARK, Offset(20f, 2f), Size(40f, 26f)); drawRect(ST, Offset(20f, 2f), Size(40f, 26f), style = Stroke(1.2f))
                    drawLine(BL, Offset(10f, 15f), Offset(32f, 15f), 1.5f, pathEffect = dash)
                    drawCircle(BL, 3.6f, Offset(34f, 15f), style = Stroke(1.2f, pathEffect = dot))
                    probeTop(10f, 15f)
                }
                ProbeKind.HOLE -> {
                    drawRoundRect(FILL, Offset(0f, 2f), Size(60f, 36f), CornerRadius(1.5f))
                    drawCircle(DARK, 16f, Offset(30f, 20f)); drawCircle(ST, 16f, Offset(30f, 20f), style = Stroke(1.2f))
                    drawLine(BL, Offset(16f, 20f), Offset(44f, 20f), 1.5f, pathEffect = dash)
                    arrowHeads(16f, 20f, 44f, 20f, BL, true)
                    probeTop(30f, 20f)
                }
                else -> {                                                   // block
                    drawRoundRect(FILL, Offset(19f, 9f), Size(22f, 22f), CornerRadius(1f)); drawRoundRect(ST, Offset(19f, 9f), Size(22f, 22f), CornerRadius(1f), style = Stroke(1.2f))
                    drawLine(BL, Offset(8f, 20f), Offset(52f, 20f), 1.5f, pathEffect = dash)
                    drawCircle(BL, 3.6f, Offset(6f, 20f), style = Stroke(1.2f, pathEffect = dot))
                    drawCircle(BL, 3.6f, Offset(54f, 20f), style = Stroke(1.2f, pathEffect = dot))
                    probeTop(30f, 20f)
                }
            }
        }
        // Y: turned a quarter counter-clockwise about (30, 30), which maps the 60 × 40 X box onto 40 × 60.
        if (isY) rotate(-90f, pivot = Offset(30f, 30f)) { draw() } else draw()
    }
}

/** The little drawing on each B2 field (top view for X/Y, drawn for [kind]; side view for depth/tip). */
@Composable
fun FieldArtImage(which: FieldArt, modifier: Modifier, tool: ProbeTool = ProbeTool.PROBE_3D, kind: ProbeKind = ProbeKind.OUTSIDE,
                  side: com.z1pendant.probe.Side? = null, fromHere: Boolean = false) = Canvas(modifier) {
    when {
        (which == FieldArt.X || which == FieldArt.Y) && kind == ProbeKind.EDGE && side != null -> edgeOffsetArt(side, fromHere)
        (which == FieldArt.X || which == FieldArt.Y) && kind != ProbeKind.OUTSIDE && kind != ProbeKind.TOP && kind != ProbeKind.EDGE ->
            offsetArt(kind, which == FieldArt.Y)
        else -> fieldArt(which, tool)
    }
}

/** Outside corner (front-left) for X / Y, and the side views for depth and tip. */
private fun DrawScope.fieldArt(which: FieldArt, tool: ProbeTool) {
    when (which) {
        FieldArt.X -> fit(0f, 3f, 60f, 37f) {
            drawRoundRect(FILL, Offset(26f, 4f), Size(32f, 34f), CornerRadius(1f)); drawRoundRect(ST, Offset(26f, 4f), Size(32f, 34f), CornerRadius(1f), style = Stroke(1.2f))
            drawLine(BL, Offset(36f, 28f), Offset(8f, 28f), 1.5f, pathEffect = dash)
            drawCircle(BL, 3.6f, Offset(6f, 28f), style = Stroke(1.2f, pathEffect = dot))
            probeTop(36f, 28f)
        }
        FieldArt.Y -> fit(22f, 3f, 36f, 56f) {
            drawRoundRect(FILL, Offset(26f, 4f), Size(32f, 34f), CornerRadius(1f)); drawRoundRect(ST, Offset(26f, 4f), Size(32f, 34f), CornerRadius(1f), style = Stroke(1.2f))
            drawLine(BL, Offset(36f, 28f), Offset(36f, 52f), 1.5f, pathEffect = dash)
            drawCircle(BL, 3.6f, Offset(36f, 54f), style = Stroke(1.2f, pathEffect = dot))
            probeTop(36f, 28f)
        }
        FieldArt.DEPTH -> fit(0f, 11f, 60f, 48f) {
            drawRoundRect(FILL, Offset(28f, 28f), Size(30f, 30f), CornerRadius(1f)); drawRoundRect(ST, Offset(28f, 28f), Size(30f, 30f), CornerRadius(1f), style = Stroke(1.2f))
            probeSide(23.5f, 37f, 4.5f, 0.8f, tool)
            drawLine(BL, Offset(3f, 28f), Offset(28f, 28f), 1f, pathEffect = dot)
            drawLine(BL, Offset(3f, 41.5f), Offset(18f, 41.5f), 1f, pathEffect = dot)
            arrowHeads(7f, 28f, 7f, 41.5f, BL, true)
        }
        FieldArt.TIP -> fit(8f, 0f, 44f, 58f) {
            probeSide(30f, 34f, 10f, 1.1f, tool)
            drawLine(BL, Offset(20f, 34f), Offset(20f, 57f), 1f, pathEffect = dot)
            drawLine(BL, Offset(40f, 34f), Offset(40f, 57f), 1f, pathEffect = dot)
            arrowHeads(20f, 52f, 40f, 52f, BL, true)
        }
    }
}

/** Probe pictures for the tool choice: the 3D probe, the probe rod, or the laser probe. */
@Composable
fun ProbeToolArt(tool: com.z1pendant.probe.ProbeTool, size: Dp) = Canvas(Modifier.size(size)) {
    fit(0f, 0f, 60f, 60f) {
        when (tool) {
            com.z1pendant.probe.ProbeTool.ROD -> {
                // A black T hub with the cable out the side: the 3.175 shank up, the thinner probing pin down.
                val cable = Path().apply { moveTo(24f, 25f); cubicTo(14f, 25f, 7f, 30f, 3f, 42f) }
                drawPath(cable, HUB_EDGE, style = Stroke(5.2f, cap = StrokeCap.Round))
                drawPath(cable, HUB, style = Stroke(3.8f, cap = StrokeCap.Round))
                drawRect(STEEL, Offset(27.3f, 2f), Size(5.4f, 16f))
                drawRect(STEEL, Offset(28.4f, 34f), Size(3.2f, 23f))
                drawRoundRect(HUB, Offset(23.5f, 17f), Size(13f, 18f), CornerRadius(2.5f))
                drawRoundRect(HUB_EDGE, Offset(23.5f, 17f), Size(13f, 18f), CornerRadius(2.5f), style = Stroke(1f))
                drawRoundRect(HUB, Offset(19f, 21.5f), Size(6f, 7f), CornerRadius(1f))
            }
            com.z1pendant.probe.ProbeTool.LASER -> {
                // Makera's wired probe: a black block (chamfered at the bottom-left) with the shank on the
                // right, the cable out the top-left, a steel collar and the gold spring pin underneath.
                val cable = Path().apply { moveTo(20f, 18f); cubicTo(20f, 10f, 14f, 6f, 10f, 2f) }
                drawPath(cable, HUB_EDGE, style = Stroke(5f, cap = StrokeCap.Round))
                drawPath(cable, HUB, style = Stroke(3.6f, cap = StrokeCap.Round))
                drawRect(STEEL, Offset(30.5f, 2f), Size(6f, 9f))
                drawRect(STEEL, Offset(32f, 10f), Size(3f, 8f))
                val body = Path().apply { moveTo(15f, 18f); lineTo(42f, 18f); lineTo(42f, 46f); lineTo(24f, 46f); lineTo(15f, 37f); close() }
                drawPath(body, HUB); drawPath(body, HUB_EDGE, style = Stroke(1f))
                drawCircle(Color(0xFFEF4444), 1.3f, Offset(22f, 31f))          // the laser window
                drawRoundRect(STEEL, Offset(29.5f, 46f), Size(8f, 4f), CornerRadius(1f))
                drawRoundRect(Color(0xFFE0A526), Offset(32.3f, 50f), Size(2.4f, 7f), CornerRadius(1f))
            }
            else -> {
                // Makera's 3D probe: a steel shank, a turned steel body with a clear band and a set
                // screw, a small collar, the stylus, and the ruby ball.
                drawRoundRect(STEEL, Offset(28.4f, 2f), Size(3.2f, 11f), CornerRadius(1.2f))
                drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF7D8896), Color(0xFFDDE3EA), Color(0xFFA7B1BE), Color(0xFF6F7A88)), 19f, 41f),
                    Offset(19f, 12f), Size(22f, 31f), CornerRadius(1.5f))
                drawRect(Color(0xFFE8EEF5).copy(alpha = 0.8f), Offset(19f, 25.5f), Size(22f, 2.4f))
                drawLine(Color(0xFF64748B), Offset(19f, 25.5f), Offset(41f, 25.5f), 0.5f)
                drawLine(Color(0xFF64748B), Offset(19f, 27.9f), Offset(41f, 27.9f), 0.5f)
                drawCircle(Color(0xFF334155), 1.2f, Offset(24.5f, 16.5f))
                drawRect(Color(0xFF9AA5B3), Offset(28.3f, 43f), Size(3.4f, 2f))
                drawRect(STEEL, Offset(28.8f, 45f), Size(2.4f, 5f))
                drawCircle(Color(0xFF334155), 0.5f, Offset(30f, 46.8f))
                drawRect(STEEL, Offset(29.4f, 50f), Size(1.2f, 4.5f))
                drawCircle(RUBY, 2.6f, Offset(30f, 56.5f))
                drawCircle(Color.White.copy(alpha = 0.55f), 0.7f, Offset(29.1f, 55.6f))
            }
        }
    }
}

/** The Calibrate icon: the probe sitting on the tool setter. Drawn in [tint]. */
@Composable
fun CalibrateIcon(tint: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
    fit(0f, 0f, 24f, 24f) {
        drawRoundRect(tint, Offset(10.3f, 1.5f), Size(3.4f, 5f), CornerRadius(1f))
        drawRoundRect(tint, Offset(8.3f, 6f), Size(7.4f, 4f), CornerRadius(1.2f))
        drawRect(tint, Offset(11.2f, 10f), Size(1.6f, 3.4f))
        drawCircle(tint, 2.2f, Offset(12f, 14.6f))
        drawRoundRect(tint, Offset(5f, 17.6f), Size(14f, 2.4f), CornerRadius(1f))
        drawRoundRect(tint, Offset(3f, 20.2f), Size(18f, 2.3f), CornerRadius(1f))
    }
}

// ---- the probing screen's picture ------------------------------------------------------------
private val DONE = Color(0xFF22C55E)
private val TODO = Color(0xFF475569)
private const val BALL_R = 2.8f

/**
 * The run from above (front = bottom), drawn for the front-left corner and mirrored for the others:
 * the part, where the probe started, the paths to each touch, and the ball at the current step.
 * Steps before [index] are done (green), the current one is blue, later ones grey. Top only is a
 * side view. A hole or block step touches two sides, in the order the machine does them: −X (left)
 * then +X (right), −Y (front) then +Y (back). [stepTouch], the touches made in the step so far (fast
 * + slow per side), says which side the ball is on.
 */
@Composable
fun ProbeRunArt(setup: com.z1pendant.probe.ProbeSetup, labels: List<String>, index: Int, modifier: Modifier, stepTouch: Int = 0) = Canvas(modifier) {
    val S = com.z1pendant.probe.Steps
    fun col(label: String) = labels.indexOf(label).let { i -> when { i < 0 -> TODO; i < index -> DONE; i == index -> BL; else -> TODO } }
    fun isNow(label: String) = labels.indexOf(label) == index
    val dashed = PathEffect.dashPathEffect(floatArrayOf(2f, 1.5f))
    fun DrawScope.ball(at: Offset) { drawCircle(BL, 5f, at, alpha = 0.35f); drawCircle(BL, BALL_R, at) }
    /** A path: the first leg dashed (stepping out), the rest solid (the touch). */
    fun DrawScope.path(c: Color, p: List<Offset>) {
        for (i in 0 until p.size - 1) drawLine(c, p[i], p[i + 1], 0.9f, pathEffect = if (i == 0 && p.size > 2) dashed else null, cap = StrokeCap.Round)
    }
    /** The ball at [at] while it touches; once done, a tick on the part's [edge] (what the touch found). */
    fun DrawScope.touch(c: Color, at: Offset, now: Boolean, edge: Offset = at) {
        when {
            now -> ball(at)
            c == DONE -> { drawLine(DONE, edge + Offset(-2f, -2f), edge + Offset(2f, 2f), 1f); drawLine(DONE, edge + Offset(2f, -2f), edge + Offset(-2f, 2f), 1f) }
            else -> drawCircle(TODO, BALL_R, at, style = Stroke(0.8f))
        }
    }
    fun DrawScope.start(at: Offset) = drawCircle(STEEL, 2.2f, at, style = Stroke(0.8f))
    fun DrawScope.target(at: Offset, label: String) {
        val c = col(label)
        drawCircle(c, 2.6f, at, style = Stroke(1f))
        drawLine(c, at + Offset(-4.5f, 0f), at + Offset(4.5f, 0f), 0.8f); drawLine(c, at + Offset(0f, -4.5f), at + Offset(0f, 4.5f), 0.8f)
        if (isNow(label)) ball(at)
    }
    fun DrawScope.topMark(at: Offset) = touch(col(S.TOP), at, isNow(S.TOP))
    /** Where it ends up (corner, edge or center); nothing when only the top is touched. */
    fun DrawScope.finish(at: Offset) { setup.finishLabel()?.let { target(at, it) } }
    /** A two-sided step: [first] side, then [second]; the first is ticked once the ball has moved on. */
    fun DrawScope.twoSides(label: String, first: Offset, second: Offset, firstEdge: Offset, secondEdge: Offset) {
        val c = col(label); val now = isNow(label); val onSecond = now && stepTouch >= 2
        touch(if (onSecond) DONE else c, first, now && !onSecond, firstEdge)
        touch(c, second, onSecond, secondEdge)
    }

    drawRoundRect(Color(0xFF111A2B), size = size, cornerRadius = CornerRadius(16.dp.toPx()))
    fit(0f, 0f, 120f, 95f) {
        val c = setup.corner
        // The corner kinds are drawn for front-left and mirrored for the other corners.
        val mx = if (setup.kind.hasCorner && !c.left) -1f else 1f
        val my = if (setup.kind.hasCorner && c.back) -1f else 1f
        scale(mx, my, pivot = Offset(60f, 47.5f)) {
            when (setup.kind) {
                ProbeKind.OUTSIDE -> {
                    drawRoundRect(FILL, Offset(44f, 12f), Size(70f, 56f), CornerRadius(1.5f))
                    drawRoundRect(ST, Offset(44f, 12f), Size(70f, 56f), CornerRadius(1.5f), style = Stroke(0.8f))
                    val s = Offset(58f, 54f); start(s)
                    val xc = col(S.X_SIDE); val yc = col(S.Y_SIDE)
                    if (setup.doX) { path(xc, listOf(s, Offset(16f, 54f), Offset(41.2f, 54f))); touch(xc, Offset(41.2f, 54f), isNow(S.X_SIDE), Offset(44f, 54f)) }
                    if (setup.doY) { path(yc, listOf(s, Offset(58f, 88f), Offset(58f, 70.8f))); touch(yc, Offset(58f, 70.8f), isNow(S.Y_SIDE), Offset(58f, 68f)) }
                    if (setup.doZ) topMark(s)
                    finish(Offset(if (setup.doX) 44f else 58f, if (setup.doY) 68f else 54f))
                }
                ProbeKind.INSIDE -> {
                    drawRoundRect(FILL, Offset(4f, 4f), Size(112f, 87f), CornerRadius(2f))
                    drawRect(DARK, Offset(40f, 8f), Size(72f, 52f)); drawRect(ST, Offset(40f, 8f), Size(72f, 52f), style = Stroke(0.8f))
                    val s = Offset(30f, 70f); start(s); val p = Offset(54f, 46f)
                    val xc = col(S.X_WALL); val yc = col(S.Y_WALL)
                    drawLine(if (setup.doX) xc else yc, s, p, 0.9f, pathEffect = dashed)
                    if (setup.doX) { path(xc, listOf(p, Offset(42.8f, 46f))); touch(xc, Offset(42.8f, 46f), isNow(S.X_WALL), Offset(40f, 46f)) }
                    if (setup.doY) { path(yc, listOf(p, Offset(54f, 57.2f))); touch(yc, Offset(54f, 57.2f), isNow(S.Y_WALL), Offset(54f, 60f)) }
                    if (setup.doZ) topMark(s)
                    finish(Offset(if (setup.doX) 40f else 54f, if (setup.doY) 60f else 46f))
                }
                ProbeKind.HOLE -> {
                    drawRoundRect(FILL, Offset(4f, 4f), Size(112f, 87f), CornerRadius(2f))
                    val cc = Offset(60f, 47.5f); drawCircle(DARK, 30f, cc); drawCircle(ST, 30f, cc, style = Stroke(0.8f))
                    val xc = col(S.X_WALLS); val yc = col(S.Y_WALLS)
                    if (setup.doX) {
                        val a = Offset(32.8f, 47.5f); val b = Offset(87.2f, 47.5f)
                        drawLine(xc, a, b, 0.9f); twoSides(S.X_WALLS, first = a, second = b, firstEdge = Offset(30f, 47.5f), secondEdge = Offset(90f, 47.5f))       // left wall, then right
                    }
                    if (setup.doY) {
                        val back = Offset(60f, 20.3f); val front = Offset(60f, 74.7f)
                        drawLine(yc, back, front, 0.9f); twoSides(S.Y_WALLS, first = front, second = back, firstEdge = Offset(60f, 77.5f), secondEdge = Offset(60f, 17.5f))
                    }
                    finish(cc)
                }
                ProbeKind.BLOCK -> {
                    drawRoundRect(FILL, Offset(34f, 21.5f), Size(52f, 52f), CornerRadius(1.5f))
                    drawRoundRect(ST, Offset(34f, 21.5f), Size(52f, 52f), CornerRadius(1.5f), style = Stroke(0.8f))
                    val cc = Offset(60f, 47.5f); start(cc)
                    val xc = col(S.X_SIDES); val yc = col(S.Y_SIDES)
                    if (setup.doX) {
                        drawLine(xc, Offset(10f, 47.5f), Offset(31.2f, 47.5f), 0.9f); drawLine(xc, Offset(110f, 47.5f), Offset(88.8f, 47.5f), 0.9f)
                        twoSides(S.X_SIDES, first = Offset(31.2f, 47.5f), second = Offset(88.8f, 47.5f), firstEdge = Offset(34f, 47.5f), secondEdge = Offset(86f, 47.5f))   // left side, then right
                    }
                    if (setup.doY) {
                        drawLine(yc, Offset(60f, 2f), Offset(60f, 18.7f), 0.9f); drawLine(yc, Offset(60f, 93f), Offset(60f, 76.3f), 0.9f)
                        twoSides(S.Y_SIDES, first = Offset(60f, 76.3f), second = Offset(60f, 18.7f), firstEdge = Offset(60f, 73.5f), secondEdge = Offset(60f, 21.5f))      // front, then back
                    }
                    if (setup.doZ) topMark(cc)
                    finish(cc)
                }
                ProbeKind.EDGE -> {
                    // From above, drawn for the front edge (probe coming in from the front) and turned
                    // for the others. Touching the top first, it starts over the part and goes out,
                    // along and back in for each touch; started beside the part, it starts outside
                    // and goes straight in, along the start line between touches.
                    val e = setup.edge
                    rotate(edgeTurn(e), pivot = Offset(60f, 47.5f)) {
                        drawRoundRect(FILL, Offset(20f, 14f), Size(80f, 48f), CornerRadius(1.5f))
                        drawRoundRect(ST, Offset(20f, 14f), Size(80f, 48f), CornerRadius(1.5f), style = Stroke(0.8f))
                        drawLine(BL, Offset(20f, 62f), Offset(100f, 62f), 1.2f)
                        val labels2 = setup.edgeTouchLabels()
                        val n = labels2.size
                        val fwd = edgeK(e) * setup.alongSign > 0
                        val xs = (0 until n).map { i -> if (n == 1) 60f else 28f + 64f * i / (n - 1) }.let { if (fwd) it else it.reversed() }
                        val line = if (setup.fromHere) 80f else 84f
                        val s = Offset(xs[0], if (setup.fromHere) 80f else 50f); start(s)
                        xs.forEachIndexed { i, x ->
                            val c = col(labels2[i])
                            val legs = if (i == 0) (if (setup.fromHere) listOf(s, Offset(x, 64.8f)) else listOf(s, Offset(x, line), Offset(x, 64.8f)))
                                else listOf(Offset(xs[i - 1], line), Offset(x, line), Offset(x, 64.8f))
                            path(c, legs); touch(c, Offset(x, 64.8f), isNow(labels2[i]), Offset(x, 62f))
                        }
                        if (setup.doZ) topMark(s)
                        finish(if (setup.fromHere) s else Offset(xs[0], 62f))
                    }
                }
                ProbeKind.TOP -> {                                     // side view
                    drawRoundRect(FILL, Offset(20f, 52f), Size(80f, 34f), CornerRadius(1.5f))
                    drawRoundRect(ST, Offset(20f, 52f), Size(80f, 34f), CornerRadius(1.5f), style = Stroke(0.8f))
                    val tc = col(S.TOP)
                    drawLine(tc, Offset(60f, 14f), Offset(60f, 49f), 0.9f, pathEffect = dashed)
                    // The probe you picked, its tip on the top once the touch has started.
                    val down = labels.indexOf(S.TOP).let { it in 0..index }
                    probeSide(60f, (if (down) 52f else 30f) - BALL_R, BALL_R, 0.75f, setup.tool)
                    if (!down) touch(tc, Offset(60f, 49.2f), false)
                    drawLine(BL, Offset(14f, 52f), Offset(106f, 52f), 0.6f, pathEffect = dashed)
                }
            }
        }
    }
}
