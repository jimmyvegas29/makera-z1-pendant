package com.z1pendant.probe

import com.z1pendant.util.Fmt
import kotlin.math.abs

/**
 * 3D probing on the stock Z1: what you probe, its settings, Makera's own command for it, and the
 * touch sequences the pendant runs itself ("Measure only", and "Top only" in both modes).
 *
 * Makera's routines (`M480.1`–`.10`, firmware `ATCHandler::fill_*_scripts`) run inside the machine
 * and write the work zero with `G10 L20` between touches. The sequences here make the same moves
 * from the pendant, in machine coordinates (G53) so nothing depends on a zero being set, and read
 * each touch back from the machine's `[PRB:x,y,z:1]` report.
 */
enum class ProbeKind(val label: String) {
    OUTSIDE("Outside corner"), INSIDE("Inside corner"), HOLE("Hole center"), BLOCK("Block center"), TOP("Top only"), EDGE("Edge");
    val hasCorner get() = this == OUTSIDE || this == INSIDE
    val hasCenterAxes get() = this == HOLE || this == BLOCK
    /** Can touch Z (the top surface). A hole starts inside the hole, so it has no Z. */
    val hasTop get() = this != HOLE
}

/** Corner seen from above, front = toward you (machine −Y). Makera's "top-left" = back-left. */
enum class Corner(val label: String) {
    BACK_LEFT("back-left"), BACK_RIGHT("back-right"), FRONT_LEFT("front-left"), FRONT_RIGHT("front-right");
    val left get() = this == BACK_LEFT || this == FRONT_LEFT
    val back get() = this == BACK_LEFT || this == BACK_RIGHT
}

/**
 * One side of the part, seen from above (front = toward you, machine −Y). [axis] = the axis the probe
 * moves along to touch it; [dir] = which way it moves (+1 / −1) coming in from outside the part.
 */
enum class Side(val label: String, val axis: Char, val dir: Int) {
    BACK("back", 'Y', -1), FRONT("front", 'Y', 1), LEFT("left", 'X', 1), RIGHT("right", 'X', -1);
    /** The axis along the edge (where a second touch moves). */
    val along get() = if (axis == 'X') 'Y' else 'X'
}

/** [noun] = the name inside a sentence ("Load the 3D probe", "the laser probe"). */
enum class ProbeTool(val label: String, val noun: String) {
    PROBE_3D("3D probe", "3D probe"), ROD("Probe rod", "probe rod"), LASER("Laser probe", "laser probe");
    /** The laser probe (the stock head probe) only senses straight down, so it can only do Top only. */
    val topOnly get() = this == LASER
    /** Whether this probe can do [k] (the laser probe only senses straight down). */
    fun canDo(k: ProbeKind) = !topOnly || k == ProbeKind.TOP
}

/**
 * Which probe is in, from where it trips the tool setter: the machine Z of the calibration's slow
 * touch (`G38.6 … P1`). Each probe sits at its own length. On 2026-11-15 the 3D probe, the probe rod
 * and the laser probe read −69.57, −72.02 and −78.53, repeating within 0.011 mm after reinserting
 * (reference/probe_length_test_20261115.md). Values are learned per machine, not built in.
 */
object ProbeLength {
    /** How far a reading may be from a probe's learned value and still be that probe. */
    const val WINDOW = 0.5

    enum class Result {
        /** Matches this probe's learned value. */
        MATCH,
        /** First calibration of this probe: its value was just learned. */
        LEARNED,
        /** This probe has no learned value (and the reading matches no other probe). */
        NEW,
        /** Matches another probe's learned value, not this one's. */
        LOOKS_LIKE,
        /** Matches nothing learned: another probe, or seated differently. */
        UNKNOWN,
    }

    data class Check(val tool: ProbeTool, val z: Double, val result: Result, val expected: Double? = null, val other: ProbeTool? = null) {
        /** The wizard may go on (the probe is the one you picked, or this was its first time). */
        val ok get() = result == Result.MATCH || result == Result.LEARNED
    }

    fun check(tool: ProbeTool, z: Double, learned: Map<ProbeTool, Double>): Check {
        val exp = learned[tool]
        if (exp != null && abs(z - exp) <= WINDOW) return Check(tool, z, Result.MATCH, exp)
        val other = learned.filterKeys { it != tool }.entries
            .filter { abs(z - it.value) <= WINDOW }.minByOrNull { abs(z - it.value) }
        return when {
            other != null -> Check(tool, z, Result.LOOKS_LIKE, exp, other.key)
            exp == null -> Check(tool, z, Result.NEW)
            else -> Check(tool, z, Result.UNKNOWN, exp)
        }
    }
}

enum class ProbeMode { SET_ZERO, MEASURE }

/** Everything the run needs. Distances in mm, always positive; the direction comes from the corner. */
data class ProbeSetup(
    val kind: ProbeKind = ProbeKind.OUTSIDE,
    val corner: Corner = Corner.FRONT_LEFT,
    /** Which axes to probe, each a toggle (Top only is always just Z; a hole has no Z). */
    val axisX: Boolean = true,
    val axisY: Boolean = true,
    val axisZ: Boolean = true,
    val tool: ProbeTool = ProbeTool.PROBE_3D,
    /** "X / Y start offset": how far it moves out (corner, block) or searches (hole, inside corner). */
    val x: Double = 20.0,
    val y: Double = 20.0,
    /** "Side depth": how far below the top the ball goes before touching the sides (with Z off, below the work Z0). */
    val depth: Double = 2.0,
    /** Ball (tip) diameter. */
    val tip: Double = 2.0,
    /** Edge: which side of the part. */
    val edge: Side = Side.FRONT,
    /**
     * Edge: "Start from here". The ball is already beside the part at touching height: no top touch,
     * no Z move; it goes straight in to the edge (and [x] / [y] is how far it searches). Off = "Touch
     * the top first": it starts over the part, touches the top, then out, down and back in.
     */
    val edgeFromHere: Boolean = false,
    /** Edge: several touches along it, measure only (how straight and how square it is). Off = 1 touch. */
    val edgeMulti: Boolean = false,
    /** Edge, multiple points: how many touches (2–8). */
    val edgeCount: Int = 2,
    /** Edge, multiple points: from the first touch to the last, mm. */
    val edgeLength: Double = 10.0,
    /** Edge, multiple points: +1 = toward + on the along axis (right, or back), −1 the other way. */
    val alongSign: Int = 1,
) {
    /** Pick a probe; the laser probe can only do Top only. */
    fun withTool(t: ProbeTool) = copy(tool = t, kind = if (t.topOnly) ProbeKind.TOP else kind).normalized()

    val doX get() = when (kind) { ProbeKind.TOP -> false; ProbeKind.EDGE -> edge.axis == 'X'; else -> axisX }
    val doY get() = when (kind) { ProbeKind.TOP -> false; ProbeKind.EDGE -> edge.axis == 'Y'; else -> axisY }
    val doZ get() = when (kind) { ProbeKind.TOP -> true; ProbeKind.EDGE -> !edgeFromHere; else -> kind.hasTop && axisZ }
    /** Edge, several touches along it (always measure only). */
    val multi get() = kind == ProbeKind.EDGE && edgeMulti
    /** Edge, starting beside the part at touching height. */
    val fromHere get() = kind == ProbeKind.EDGE && edgeFromHere
    /** Edge: how many touches. */
    val touchCount get() = if (multi) edgeCount.coerceIn(MIN_POINTS, MAX_POINTS) else 1
    /** Edge, multiple points: from one touch to the next. */
    val spacing get() = if (touchCount > 1) edgeLength / (touchCount - 1) else 0.0
    /** Edge: where each touch is along the edge, from where the probe was parked (the first is 0). */
    fun alongOffsets(): List<Double> = (0 until touchCount).map { alongSign * it * spacing }
    /** Touches the sides (not just the top). */
    val doSides get() = doX || doY
    /** At least one axis to probe. */
    val anyAxis get() = doX || doY || doZ
    /** Toggle [axis]; turning off the last one does nothing. */
    fun toggle(axis: Char): ProbeSetup {
        val n = when (axis) { 'X' -> copy(axisX = !axisX); 'Y' -> copy(axisY = !axisY); else -> copy(axisZ = !axisZ) }
        return if (n.anyAxis) n else this
    }
    /** Nothing left to probe (e.g. Z only, then Hole center, which has no Z): all axes back on. */
    fun normalized() = if (anyAxis) this else copy(axisX = true, axisY = true, axisZ = true)
    /**
     * Z off but touching sides: no top touch to measure the side depth from, so the work Z0 stands in
     * for the top (set it first, e.g. with Top only).
     */
    val depthFromZero get() = !doZ && doSides && kind != ProbeKind.HOLE && kind != ProbeKind.EDGE

    /**
     * Why it can't start from work Z [wposZ] (null = fine). With Z off it moves out over the part at
     * the start height, taking Z0 as the top: start below that and it would hit the part.
     */
    fun startProblem(wposZ: Double?): String? =
        if (depthFromZero && wposZ != null && wposZ < 1.0)
            "Z is off, so it takes your Z0 as the top. The ball is ${if (wposZ < 0) "below" else "at"} Z0 (Z ${Fmt.fixed(wposZ, 3)}): raise it above the top first."
        else null

    /** The probed axes, as "X Y Z". */
    fun axesText() = listOfNotNull("X".takeIf { doX }, "Y".takeIf { doY }, "Z".takeIf { doZ }).joinToString(" ")

    fun title(): String {
        if (kind == ProbeKind.EDGE) return "Edge · ${edge.label}" + if (multi) " · $touchCount points" else ""
        val all = doX && doY && (doZ || !kind.hasTop)
        val ax = if (kind != ProbeKind.TOP && !all) " · ${axesText()}" else ""
        return if (kind.hasCorner) "${kind.label} · ${corner.label}$ax" else kind.label + ax
    }

    /** Edge, multiple points: the way the touches go, as you'd say it ("right", "back"). */
    fun alongWord(sign: Int = alongSign) = if (edge.along == 'X') (if (sign > 0) "right" else "left") else (if (sign > 0) "back" else "front")

    /**
     * Edge: which touches would leave the machine's travel ([limits], machine coordinates) from the
     * probe parked at [start], and why (null = all fine). Checks where each touch is along the edge
     * and, touching the top first, where it steps out past the edge. Soft limits off: nothing to check.
     */
    fun travelCheck(start: Touch?, limits: com.z1pendant.link.SoftLimits?): TravelCheck {
        if (kind != ProbeKind.EDGE || start == null || limits == null || !limits.enabled) return TravelCheck(emptySet(), null)
        fun inside(axis: Char, v: Double) = (limits.min[axis]?.let { v >= it - 1e-6 } ?: true) && (limits.max[axis]?.let { v <= it + 1e-6 } ?: true)
        val ax = edge.axis; val al = edge.along
        val off = if (ax == 'X') x else y
        if (!fromHere && !inside(ax, start.on(ax) - edge.dir * off))
            return TravelCheck((0 until touchCount).toSet(), "The $ax start offset would take it past the $ax travel: make it smaller.")
        val bad = alongOffsets().withIndex().filter { (_, o) -> !inside(al, start.on(al) + o) }.map { it.index }.toSet()
        if (bad.isEmpty()) return TravelCheck(bad, null)
        return TravelCheck(bad, "Touch ${bad.min() + 1} would be past the $al travel: shorten the length or start further ${alongWord(-alongSign)}.")
    }

    companion object {
        const val MIN_POINTS = 2
        const val MAX_POINTS = 8
    }

    /**
     * Makera's command for this setup, as Makera Studio sends it (`M480.%n X Y Z D`, all positive,
     * 3 decimals; a center on one axis sends 0 for the other). Null when Makera has no routine for
     * these axes (its corners always touch X, Y and the top, its block center always the top; Top
     * only has none): those run the pendant's own touches.
     */
    fun makeraCommand(): String? {
        val n = when (kind) {
            ProbeKind.OUTSIDE -> cornerIndex().takeIf { doX && doY && doZ }
            ProbeKind.INSIDE -> (cornerIndex() + 4).takeIf { doX && doY && doZ }
            ProbeKind.HOLE -> 9.takeIf { doSides }
            ProbeKind.BLOCK -> 10.takeIf { doZ && doSides }
            ProbeKind.TOP, ProbeKind.EDGE -> null
        } ?: return null
        val xv = if (doX) abs(x) else 0.0
        val yv = if (doY) abs(y) else 0.0
        return "M480.$n X${f3(xv)} Y${f3(yv)} Z${f3(abs(depth))} D${f3(abs(tip))}"
    }

    /** Makera's corner numbering: 1 top(back)-left, 2 back-right, 3 front-right, 4 front-left. */
    private fun cornerIndex() = when (corner) {
        Corner.BACK_LEFT -> 1; Corner.BACK_RIGHT -> 2; Corner.FRONT_RIGHT -> 3; Corner.FRONT_LEFT -> 4
    }
}

/** [bad] = the touches (0-based) that would leave the travel; [problem] = what to tell the user. */
data class TravelCheck(val bad: Set<Int>, val problem: String?)

internal fun Touch.on(axis: Char) = when (axis) { 'X' -> x; 'Y' -> y; else -> z }

internal fun f3(v: Double) = Fmt.fixed(v, 3)

/** The steps of a run, as the probing screen lists them. */
object Steps {
    const val TOP = "Touch the top"
    const val X_SIDE = "Touch the X side"; const val Y_SIDE = "Touch the Y side"
    const val X_WALL = "Touch the X wall"; const val Y_WALL = "Touch the Y wall"
    const val X_WALLS = "Touch the X walls"; const val Y_WALLS = "Touch the Y walls"
    const val X_SIDES = "Touch the X sides"; const val Y_SIDES = "Touch the Y sides"
    const val CORNER = "Move over the corner"; const val EDGE = "Move over the edge"; const val CENTER = "Move to the center"
    /** Edge started beside the part: it ends backed off the edge, at the same height. */
    const val BACK_OFF = "Back off the edge"

    /** Edge, multiple points: touch [i] (1-based) of [n]. */
    fun touchOf(i: Int, n: Int) = "Touch $i of $n"
    private val touchOf = Regex("""Touch \d+ of \d+""")

    /** Touches each step makes (fast + slow per side), to follow Makera's routine by its [PRB:] reports. */
    fun touches(label: String) = when {
        label == TOP || label == X_SIDE || label == Y_SIDE || label == X_WALL || label == Y_WALL || touchOf.matches(label) -> 2
        label == X_WALLS || label == Y_WALLS || label == X_SIDES || label == Y_SIDES -> 4
        else -> 0
    }
}

/** Edge: the label of each touch (one side touch, or "Touch 1 of 5", …). */
fun ProbeSetup.edgeTouchLabels(): List<String> =
    if (multi) (1..touchCount).map { Steps.touchOf(it, touchCount) } else listOf(if (doX) Steps.X_SIDE else Steps.Y_SIDE)

/** The steps this setup's run goes through, in order. */
fun ProbeSetup.stepLabels(): List<String> = when (kind) {
    ProbeKind.OUTSIDE -> listOfNotNull(Steps.TOP.takeIf { doZ }, Steps.X_SIDE.takeIf { doX }, Steps.Y_SIDE.takeIf { doY }, finishLabel())
    ProbeKind.INSIDE -> listOfNotNull(Steps.TOP.takeIf { doZ }, Steps.X_WALL.takeIf { doX }, Steps.Y_WALL.takeIf { doY }, finishLabel())
    ProbeKind.HOLE -> listOfNotNull(Steps.X_WALLS.takeIf { doX }, Steps.Y_WALLS.takeIf { doY }, finishLabel())
    ProbeKind.BLOCK -> listOfNotNull(Steps.TOP.takeIf { doZ }, Steps.X_SIDES.takeIf { doX }, Steps.Y_SIDES.takeIf { doY }, finishLabel())
    ProbeKind.TOP -> listOf(Steps.TOP)
    ProbeKind.EDGE -> listOfNotNull(Steps.TOP.takeIf { doZ }) + edgeTouchLabels() + listOfNotNull(finishLabel())
}

/**
 * The last step, moving over the point found: none when only the top was touched. An edge started
 * beside the part can't move over it (the ball is below the top): it backs off instead.
 */
fun ProbeSetup.finishLabel(): String? = when {
    !doSides -> null
    fromHere -> Steps.BACK_OFF
    kind == ProbeKind.HOLE || kind == ProbeKind.BLOCK -> Steps.CENTER
    doX && doY -> Steps.CORNER
    else -> Steps.EDGE
}

/** The step a run is on after [n] touches (Makera's routine reports each touch). */
fun ProbeSetup.stepAfterTouches(n: Int): Int {
    val labels = stepLabels()
    var sum = 0
    labels.forEachIndexed { i, l -> sum += Steps.touches(l); if (n < sum) return i }
    return labels.lastIndex
}

/** Of [total] touches so far, how many fall in step [index] (0 when an echo moved the run on early). */
fun ProbeSetup.touchesInStep(index: Int, total: Int): Int {
    val before = stepLabels().take(index).sumOf { Steps.touches(it) }
    return (total - before).coerceIn(0, Steps.touches(stepLabels().getOrElse(index) { "" }).coerceAtLeast(0))
}

/** One touch, from `[PRB:x,y,z:1]`: machine coordinates of the spindle when the probe triggered. */
data class Touch(val x: Double, val y: Double, val z: Double)

/** `[PRB:-9.691,-13.580,-69.637:1]` → the touch, or null for a miss (`:0`) / anything else. */
fun parsePrb(line: String): Pair<Touch, Boolean>? {
    val m = Regex("""\[PRB:\s*(-?[\d.]+),\s*(-?[\d.]+),\s*(-?[\d.]+)(?:,[^:\]]*)*:(\d)\]""").find(line) ?: return null
    val (x, y, z, ok) = m.destructured
    return Touch(x.toDouble(), y.toDouble(), z.toDouble()) to (ok == "1")
}

/**
 * What a run found, in machine coordinates. Edges are the part's sides (the ball's radius already
 * taken off); centers are midway between two sides; widths are side to side.
 */
data class Measured(
    val edgeX: Double? = null, val edgeY: Double? = null,
    val top: Double? = null,
    val centerX: Double? = null, val centerY: Double? = null,
    val widthX: Double? = null, val widthY: Double? = null,
    /** Edge: every touch's edge position on the probed axis (machine), in order; the first is edgeX / edgeY. */
    val edgeTouches: List<Double> = emptyList(),
    /** Edge: where each touch was along the edge (machine, on the other axis). */
    val edgeAlong: List<Double> = emptyList(),
) {
    /** The point that becomes (or would become) X0 / Y0. */
    val zeroX get() = centerX ?: edgeX
    val zeroY get() = centerY ?: edgeY
}

/**
 * Several touches along one edge: a best-fit line through them (least squares), so how far the edge
 * is turned from the machine's axis, and how far each touch is off that line (straightness).
 * [t] = each touch's distance along the edge from the first (mm, ≥ 0 in the direction it went);
 * [e] = each touch's edge position on the probed axis (machine). [outSign] = which way is out of the
 * part on that axis, so a + offset means the touch sits further out than the line.
 */
data class EdgeFit(val t: List<Double>, val e: List<Double>, val outSign: Int) {
    val n get() = t.size
    private val tm = t.average()
    private val em = e.average()
    private val sxx = t.sumOf { (it - tm) * (it - tm) }
    /** mm on the probed axis per mm along the edge. */
    val slope: Double = if (sxx > 0) t.indices.sumOf { (t[it] - tm) * (e[it] - em) } / sxx else 0.0
    /** From the first touch to the last. */
    val length get() = (t.lastOrNull() ?: 0.0) - (t.firstOrNull() ?: 0.0)
    /** How far the line moves on the probed axis from the first touch to the last (+ = toward + on that axis). */
    val delta get() = slope * length
    val degrees get() = kotlin.math.atan(slope) * 180.0 / kotlin.math.PI
    /** mm per 100 mm along the edge: the number you'd tap a vise or part around by. */
    val per100 get() = slope * 100.0
    /** Each touch's distance from the line (+ = further out of the part than the line). */
    val offLine: List<Double> = t.indices.map { (e[it] - (em + slope * (t[it] - tm))) * outSign }
    /** Peak to valley of [offLine]; null with 2 touches (two points always sit on their line). */
    val straightness: Double? get() = if (n >= 3) offLine.max() - offLine.min() else null
    /** How fine the angle is, from the probe's [REPEAT] over these touches, in degrees. */
    val noiseDegrees get() = noiseDegrees(t)

    /** Straightness in words ("slightly hollow" …); null with 2 touches. */
    fun shape(): String? {
        val s = straightness ?: return null
        if (s <= 2 * REPEAT) return "Straight to within the probe's repeat."
        val r = offLine
        val ends = (r.first() + r.last()) / 2
        val middle = r.subList(1, r.size - 1).average()
        val bit = if (s < 0.05) "slightly " else ""
        return when {
            ends - middle > s / 2 -> "The middle sits ${Fmt.fixed(-r.subList(1, r.size - 1).min(), 3)} mm in from the line and the ends stand out: the edge is ${bit}hollow."
            middle - ends > s / 2 -> "The middle stands ${Fmt.fixed(r.subList(1, r.size - 1).max(), 3)} mm out from the line: the edge ${bit}bulges out."
            else -> {
                val i = r.indices.maxBy { abs(r[it]) }
                "Touch ${i + 1} is furthest off the line (${Fmt.fixed(r[i], 3, plus = true)} mm): a burr, a chip or an uneven edge there."
            }
        }
    }

    companion object {
        /** About how well the probe repeats (mm). */
        const val REPEAT = 0.005
        /** The angle [REPEAT] makes over touches at [t] along the edge (least-squares slope error), in degrees. */
        fun noiseDegrees(t: List<Double>): Double {
            val m = t.average(); val sxx = t.sumOf { (it - m) * (it - m) }
            return if (sxx > 0) kotlin.math.atan(REPEAT / kotlin.math.sqrt(sxx)) * 180.0 / kotlin.math.PI else 0.0
        }
    }
}

/** Machine-side moves the sequences need; implemented over the link (and faked in tests). */
interface ProbeIo {
    /** Send a line and wait for its ok. Throws [ProbeStop] if the machine refuses it or stops. */
    suspend fun send(line: String)
    /** Send a probing move (G38.2 …) and wait for the touch. Throws [ProbeStop] on a miss or a stop. */
    suspend fun probe(line: String): Touch
    /** Progress for the screen ("Touching the X side"). */
    fun step(text: String) {}
}

class ProbeStop(message: String) : Exception(message)

/** Probe feeds and distances, as Makera's routines use them (Z1 config: atc.probe.*). */
object ProbeFeeds {
    const val FAST = 100.0          // atc.probe.slow_rate_mm_m
    const val SLOW = 50.0           // half of it, for the second touch
    const val RETRACT = 1.0         // atc.probe.retract_mm
    const val SECOND = 3.0          // search for the second touch: retract + 2 mm
    const val Z_SEARCH = 108.0      // coordinate.toolrack_z: how far the first Z touch may go down
    /** Makera lowers to the side depth with G0 at 10 % feed override (M220 S10); this is the same speed. */
    const val PLUNGE = 300.0
}

/**
 * The touch sequences, run from the pendant. Positions are G53 (machine) moves; probing moves are
 * relative, as on the Z1 (`G38.2 X20` = search up to 20 mm in +X). [setZero] only matters for Top
 * only, the one kind without a Makera routine: it writes Z0 at the touch like Makera's routines do.
 */
class ProbeSequences(private val io: ProbeIo, private val s: ProbeSetup, private val start: Touch, private val setZero: Boolean,
                     /** Machine Z of the work Z0 (machine − work), the top's stand-in when Z is off. */
                     private val zeroZ: Double? = null) {
    private val r = s.tip / 2

    suspend fun run(): Measured = when {
        !s.doSides -> Measured(top = top())
        s.kind == ProbeKind.OUTSIDE -> outsideCorner()
        s.kind == ProbeKind.INSIDE -> insideCorner()
        s.kind == ProbeKind.HOLE -> hole()
        s.kind == ProbeKind.EDGE -> edge()
        else -> block()
    }

    /** The top when Z is on; null when it's off. */
    private suspend fun topIfOn(): Double? = if (s.doZ) top() else null

    /** Down onto the top twice (fast, then slow from 1 mm up); returns the top's machine Z. */
    private suspend fun top(): Double {
        io.step(Steps.TOP)
        val t1 = io.probe("G38.2 Z-${f3(ProbeFeeds.Z_SEARCH)} F${f0(ProbeFeeds.FAST)}")
        io.send("G53 G0 Z${f3(t1.z + ProbeFeeds.RETRACT)}")
        val t2 = io.probe("G38.2 Z-${f3(ProbeFeeds.SECOND)} F${f0(ProbeFeeds.SLOW)}")
        if (setZero) io.send("G10 L20 P0 Z0")
        io.send("G53 G0 Z${f3(start.z)}")
        return t2.z
    }

    /**
     * Toward a side along [axis] in direction [dir] (+1 / −1): a fast touch searching [search] mm,
     * back off 1 mm, a slow touch. Returns the slow touch's coordinate on that axis.
     */
    private suspend fun side(axis: Char, dir: Int, search: Double): Double {
        val t1 = io.probe("G38.2 $axis${f3(dir * search)} F${f0(ProbeFeeds.FAST)}")
        io.send("G53 G0 $axis${f3(t1.on(axis) - dir * ProbeFeeds.RETRACT)}")
        val t2 = io.probe("G38.2 $axis${f3(dir * ProbeFeeds.SECOND)} F${f0(ProbeFeeds.SLOW)}")
        io.send("G53 G0 $axis${f3(t2.on(axis) - dir * ProbeFeeds.RETRACT)}")
        return t2.on(axis)
    }

    /** Down to touching height: [depth] below the top it touched, or with Z off below the work Z0. */
    private suspend fun plunge(topZ: Double?, depth: Double = s.depth) {
        val ref = topZ ?: zeroZ ?: throw ProbeStop("Z is off and there's no Z0 to measure the side depth from.")
        io.send("G53 G1 Z${f3(ref - depth)} F${f0(ProbeFeeds.PLUNGE)}")
    }

    /** Over the point found, at the start height (an axis not probed stays put). */
    private suspend fun finish(x: Double?, y: Double?) {
        io.step(s.finishLabel() ?: return)
        val parts = listOfNotNull(x?.let { "X${f3(it)}" }, y?.let { "Y${f3(it)}" })
        if (parts.isNotEmpty()) io.send("G53 G0 " + parts.joinToString(" "))
    }

    /** Starts over the part near the corner: top, then out past each side, down, and back in. */
    private suspend fun outsideCorner(): Measured {
        val dx = if (s.corner.left) 1 else -1          // direction the probe moves to reach the X side
        val dy = if (s.corner.back) -1 else 1
        val topZ = topIfOn()
        var ex: Double? = null; var ey: Double? = null
        if (s.doX) {
            io.step(Steps.X_SIDE)
            io.send("G53 G0 X${f3(start.x - dx * s.x)}")
            plunge(topZ)
            ex = side('X', dx, s.x) + dx * r
            io.send("G53 G0 Z${f3(start.z)}")
            io.send("G53 G0 X${f3(start.x)}")
        }
        if (s.doY) {
            io.step(Steps.Y_SIDE)
            io.send("G53 G0 Y${f3(start.y - dy * s.y)}")
            plunge(topZ)
            ey = side('Y', dy, s.y) + dy * r
            io.send("G53 G0 Z${f3(start.z)}")
            if (ex == null) io.send("G53 G0 Y${f3(start.y)}")
        }
        finish(ex, ey)
        return Measured(edgeX = ex, edgeY = ey, top = topZ)
    }

    /** Starts over the top next to the corner: top, then into the pocket, down, and out to each wall. */
    private suspend fun insideCorner(): Measured {
        val px = if (s.corner.left) -1 else 1          // direction to the X wall
        val py = if (s.corner.back) 1 else -1
        val topZ = topIfOn()
        io.step(if (s.doX) Steps.X_WALL else Steps.Y_WALL)
        val inX = start.x - px * s.x
        val inY = start.y - py * s.y
        io.send("G53 G0 X${f3(inX)} Y${f3(inY)}")
        plunge(topZ)
        var ex: Double? = null; var ey: Double? = null
        if (s.doX) {
            ex = side('X', px, s.x) + px * r
            io.send("G53 G0 X${f3(inX)}")
        }
        if (s.doY) {
            io.step(Steps.Y_WALL)
            ey = side('Y', py, s.y) + py * r
            io.send("G53 G0 Y${f3(inY)}")
        }
        io.send("G53 G0 Z${f3(start.z)}")
        // On the axis not probed, stay over the pocket (where it went in).
        finish(ex ?: inX, ey ?: inY)
        return Measured(edgeX = ex, edgeY = ey, top = topZ)
    }

    /**
     * Touching the top first: starts over the part, about 5 mm in from the edge: top, then out past
     * the edge, down, and back in (like one side of an outside corner), and over the edge at the end.
     * Started beside the part ([ProbeSetup.fromHere]): no top, no Z move; straight in to the edge, and
     * back to where it started (never further than the search distance).
     *
     * Multiple points touch again further along the edge, each from the start line: touching the top
     * first it goes back out to the full start offset and lifts before moving along (after a touch
     * the ball is only 1 mm off the face, and over a long stretch a crooked part could be under it).
     */
    private suspend fun edge(): Measured {
        val e = s.edge; val ax = e.axis; val dir = e.dir; val al = e.along
        val off = if (ax == 'X') s.x else s.y
        val labels = s.edgeTouchLabels()
        val offsets = s.alongOffsets()
        val found = mutableListOf<Double>(); val along = mutableListOf<Double>()
        val topZ = topIfOn()
        val out = if (s.fromHere) start.on(ax) else start.on(ax) - dir * off
        offsets.forEachIndexed { i, o ->
            io.step(labels[i])
            val a = start.on(al) + o
            if (!s.fromHere) io.send("G53 G0 $ax${f3(out)}")
            if (i > 0) io.send("G53 G0 $al${f3(a)}")
            if (!s.fromHere) plunge(topZ)
            found += side(ax, dir, off) + dir * r; along += a
            if (s.fromHere) io.send("G53 G0 $ax${f3(out)}") else io.send("G53 G0 Z${f3(start.z)}")
        }
        // Back along to where it was parked (the way it came), then over the edge or, started beside
        // the part, stay backed off on the start line.
        if (offsets.size > 1) io.send("G53 G0 $al${f3(start.on(al))}")
        val e1 = found.first()
        if (s.fromHere) io.step(Steps.BACK_OFF) else finish(if (ax == 'X') e1 else null, if (ax == 'Y') e1 else null)
        return if (ax == 'X') Measured(edgeX = e1, top = topZ, edgeTouches = found, edgeAlong = along)
            else Measured(edgeY = e1, top = topZ, edgeTouches = found, edgeAlong = along)
    }

    /** Starts inside the hole at touching depth: out to both walls on each axis, then to the middle. */
    private suspend fun hole(): Measured {
        var cx: Double? = null; var cy: Double? = null; var wx: Double? = null; var wy: Double? = null
        if (s.doX) {
            io.step(Steps.X_WALLS)
            val a = side('X', -1, s.x)
            val b = side('X', 1, 2 * s.x)
            cx = (a + b) / 2; wx = b - a + s.tip
            io.send("G53 G0 X${f3(cx)}")
        }
        if (s.doY) {
            io.step(Steps.Y_WALLS)
            val a = side('Y', -1, s.y)
            val b = side('Y', 1, 2 * s.y)
            cy = (a + b) / 2; wy = b - a + s.tip
            io.send("G53 G0 Y${f3(cy)}")
        }
        io.step(Steps.CENTER)
        return Measured(centerX = cx, centerY = cy, widthX = wx, widthY = wy)
    }

    /** Starts over the block, roughly centered: top, then down outside each side and in. */
    private suspend fun block(): Measured {
        val topZ = topIfOn()
        var cx: Double? = null; var cy: Double? = null; var wx: Double? = null; var wy: Double? = null
        if (s.doX) {
            io.step(Steps.X_SIDES)
            io.send("G53 G0 X${f3(start.x - s.x)}"); plunge(topZ)
            val a = side('X', 1, s.x)
            io.send("G53 G0 Z${f3(start.z)}"); io.send("G53 G0 X${f3(start.x + s.x)}"); plunge(topZ)
            val b = side('X', -1, 2 * s.x)
            io.send("G53 G0 Z${f3(start.z)}")
            cx = (a + b) / 2; wx = b - a - s.tip
            io.send("G53 G0 X${f3(cx)}")
        }
        if (s.doY) {
            io.step(Steps.Y_SIDES)
            io.send("G53 G0 Y${f3(start.y - s.y)}"); plunge(topZ)
            val a = side('Y', 1, s.y)
            io.send("G53 G0 Z${f3(start.z)}"); io.send("G53 G0 Y${f3(start.y + s.y)}"); plunge(topZ)
            val b = side('Y', -1, 2 * s.y)
            io.send("G53 G0 Z${f3(start.z)}")
            cy = (a + b) / 2; wy = b - a - s.tip
            io.send("G53 G0 Y${f3(cy)}")
        }
        io.step(Steps.CENTER)
        return Measured(top = topZ, centerX = cx, centerY = cy, widthX = wx, widthY = wy)
    }

    private fun f0(v: Double) = Fmt.fixed(v, 0)
}

/**
 * Widths from the touches Makera's routine reports (each side is touched twice: fast, then slow;
 * the slow one counts). Hole: [X−, X+] per axis; block: two Z touches first, then [X+side, X−side].
 */
fun widthsFromTouches(kind: ProbeKind, setup: ProbeSetup, touches: List<Touch>): Pair<Double?, Double?> {
    val t = if (kind == ProbeKind.BLOCK) touches.drop(2) else touches
    fun pair(i: Int, axis: Char): Double? {
        val a = t.getOrNull(i * 4 + 1) ?: return null
        val b = t.getOrNull(i * 4 + 3) ?: return null
        val d = abs(if (axis == 'X') b.x - a.x else b.y - a.y)
        return if (kind == ProbeKind.HOLE) d + setup.tip else d - setup.tip
    }
    if (kind != ProbeKind.HOLE && kind != ProbeKind.BLOCK) return null to null
    return when {
        setup.doX && setup.doY -> pair(0, 'X') to pair(1, 'Y')
        setup.doX -> pair(0, 'X') to null
        else -> null to pair(0, 'Y')
    }
}
