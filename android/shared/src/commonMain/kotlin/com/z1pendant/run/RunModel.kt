package com.z1pendant.run

import com.z1pendant.util.Fmt

/**
 * Running a job the way Makera Studio does (captured 2026-11-17, reference/captures/
 * studio_job_start_20261117.md): `buffer M495 X<minX>Y<minY>[C D][O F][A B I J H]P1`, then
 * `play <path>`. The firmware runs the buffered line before the file's first line: margin scan,
 * Z probe and leveling (each only when its letters are there), then P = go to the toolpath's corner
 * at safe Z. M495 never sets the X/Y origin; Studio does that separately with `G10L2P0X…Y…`.
 */

/** The toolpath's box in work coordinates: the feed moves' end points (what Studio sends as X/Y). */
data class Extents(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
}

object Toolpath {
    private val word = Regex("""([A-Z])\s*([-+]?(?:\d+\.?\d*|\.\d+))""")

    /**
     * Min/max X/Y over the end points of G1/G2/G3 moves (absolute or incremental), ignoring
     * comments and G53 (machine coordinate) lines. Matches Studio's numbers for can_lid_op1.cnc
     * (X 10.201–99.799, Y 7.188–102.813). Null when nothing cuts.
     */
    fun extents(text: String): Extents? {
        var x: Double? = null; var y: Double? = null
        var motion = -1; var absolute = true
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var any = false
        for (raw in text.lineSequence()) {
            val l = stripComments(raw).uppercase()
            if (l.isBlank()) continue
            var nx: Double? = null; var ny: Double? = null
            var g53 = false
            for (m in word.findAll(l)) {
                val v = m.groupValues[2].toDoubleOrNull() ?: continue
                when (m.groupValues[1]) {
                    "G" -> when (v) {
                        0.0 -> motion = 0; 1.0 -> motion = 1; 2.0 -> motion = 2; 3.0 -> motion = 3
                        90.0 -> absolute = true; 91.0 -> absolute = false; 53.0 -> g53 = true
                    }
                    "X" -> nx = v
                    "Y" -> ny = v
                }
            }
            if (g53 || (nx == null && ny == null)) continue
            if (nx != null) x = if (absolute || x == null) nx else x + nx
            if (ny != null) y = if (absolute || y == null) ny else y + ny
            val cx = x; val cy = y
            if (motion in 1..3 && cx != null && cy != null) {
                any = true
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
            }
        }
        return if (any) Extents(minX, minY, maxX, maxY) else null
    }

    /**
     * True if the file cuts (G1/G2/G3) before its first tool change (M6). After a probing step the
     * laser probe is still in, so a file like that would cut with the probe.
     */
    fun cutsBeforeToolChange(text: String): Boolean {
        // "T4M6" has a digit before the M: only another letter would make it part of a different word.
        val m6 = Regex("""(?<![A-Z])M0*6(?![0-9.])""")
        val g = Regex("""(?<![A-Z])G0*([0-3])(?![0-9.])""")
        var motion = -1
        for (raw in text.lineSequence()) {
            val l = stripComments(raw).uppercase()
            if (l.isBlank()) continue
            if (m6.containsMatchIn(l)) return false
            g.findAll(l).lastOrNull()?.let { motion = it.groupValues[1].toInt() }
            if (motion in 1..3 && !l.contains("G53") && Regex("[XYZ]").containsMatchIn(l)) return true
        }
        return false
    }

    private fun stripComments(s: String): String {
        val semi = s.indexOf(';')
        val t = if (semi >= 0) s.substring(0, semi) else s
        return t.replace(Regex("""\([^)]*\)"""), " ")
    }
}

/** "X Offset : 0" / "Y Offset : -3" in the Carvera community post's header: the work zero's offset from anchor 1. */
data class CamOffset(val x: Double, val y: Double) {
    companion object {
        fun parse(header: String): CamOffset? {
            val x = Regex("""X Offset\s*:\s*([-+]?[\d.]+)""").find(header)?.groupValues?.get(1)?.toDoubleOrNull()
            val y = Regex("""Y Offset\s*:\s*([-+]?[\d.]+)""").find(header)?.groupValues?.get(1)?.toDoubleOrNull()
            return if (x != null && y != null) CamOffset(x, y) else null
        }
    }
}

enum class Origin(val label: String, val sub: String) {
    CURRENT("Current origin", "Keep the work zero that's set now"),
    ANCHOR1("Anchor 1", "X/Y zero at the bracket's inside corner, plus an offset"),
    CUSTOM("Custom anchor", "One of your saved anchors (Settings › Anchors)"),
    SPINDLE("Where the spindle is", "X/Y zero right here"),
    PROBE3D("Custom · 3D probe", "Next opens the probe; set the zero to go on"),
}

/** Everything the run asks. Distances in mm. */
data class RunSetup(
    val origin: Origin = Origin.CURRENT,
    /** Anchor 1: the work zero's offset from the anchor. */
    val offX: Double = 0.0,
    val offY: Double = 0.0,
    /** Custom anchor: the one picked, and the work zero's offset from it (machine X/Y, mm). */
    val anchor: Anchor? = null,
    val aOffX: Double = 0.0,
    val aOffY: Double = 0.0,
    /** Custom anchor with a saved Z: set Z from it (no Z probe needed). */
    val useAnchorZ: Boolean = true,
    val probeZ: Boolean = false,
    /** Where Z is probed, from the toolpath's min corner (null = the middle of the toolpath). */
    val zAtX: Double? = null,
    val zAtY: Double? = null,
    val marginAtStart: Boolean = false,
    val leveling: Boolean = false,
    val gridX: Int = 3,
    val gridY: Int = 3,
    /** Leveling probe start height (M495 H / G32 H). */
    val clearance: Double = 5.0,
    /** Leveling: how far in from the cutting area's edge the outer touches are (lead-ins run past the stock). */
    val levelInset: Double = 3.0,
    /** Leveling: the deepest a touch may go below the stock top (Settings; 2 mm, Jimmy 2026-11-19). */
    val levelBelow: Double = LEVEL_BELOW_MM,
) {
    /** The custom anchor sets Z too (it has one and it's wanted). */
    val anchorSetsZ get() = origin == Origin.CUSTOM && anchor?.z != null && useAnchorZ
    /** Anchor 1 (and a custom anchor without Z) sets X/Y only, so Z must be probed. */
    val probeZLocked get() = origin == Origin.ANCHOR1 || (origin == Origin.CUSTOM && !anchorSetsZ)
    val zOn get() = probeZ || probeZLocked
    /** Any of these runs with the laser probe (T0) in the spindle. */
    val needsLaser get() = zOn || marginAtStart || leveling

    fun withOrigin(o: Origin) = copy(origin = o, probeZ = o == Origin.ANCHOR1 || o == Origin.SPINDLE)

    /** Pick [a] as the custom anchor (offsets back to 0; its saved Z is used if it has one). */
    fun withAnchor(a: Anchor?) = copy(origin = Origin.CUSTOM, anchor = a, aOffX = 0.0, aOffY = 0.0, useAnchorZ = true, probeZ = false)

    /** Z probe point relative to the toolpath's min corner. */
    fun zPoint(e: Extents): Pair<Double, Double> = (zAtX ?: (e.width / 2)) to (zAtY ?: (e.height / 2))

    /** The leveling grid's box: the cutting area moved in by [levelInset] (kept at least 1 mm across). */
    fun levelArea(e: Extents): Extents {
        val i = levelInset.coerceIn(0.0, ((minOf(e.width, e.height) - 1.0) / 2).coerceAtLeast(0.0))
        return Extents(e.minX + i, e.minY + i, e.maxX - i, e.maxY - i)
    }

    /**
     * `max_z` for the leveling touches. Smoothie's grid probe goes down at most 2 × `zprobe.max_z`
     * from the start height [clearance] (ZProbe.run_probe: max_dist < 0 → 2 × max_z) [inferred from
     * the Smoothie source, check on the Z1]. Z0 is the stock top by then, so this lets each touch
     * reach [levelBelow] below it and no further: a touch past the stock's edge stops the
     * leveling instead of running down to the vise (2026-11-19, with the stock 100).
     */
    val levelMaxZ get() = (clearance + levelBelow) / 2
}

/** Default for how far below the stock top a leveling touch may go before it gives up (Settings). */
const val LEVEL_BELOW_MM = 2.0

object RunCommands {
    private fun n(v: Double) = Fmt.fixed(v, 3)

    /** The job's buffered first line (Studio's format, no spaces): `M495 X…Y…[C…D…][O…F…][A…B…I…J…H…]P1`. */
    fun m495(s: RunSetup, e: Extents, goToStart: Boolean = true): String = buildString {
        append("M495 X").append(n(e.minX)).append("Y").append(n(e.minY))
        if (s.marginAtStart) append("C").append(n(e.maxX)).append("D").append(n(e.maxY))
        if (s.zOn) { val (zx, zy) = s.zPoint(e); append("O").append(n(zx)).append("F").append(n(zy)) }
        if (s.leveling) append("A").append(n(e.width)).append("B").append(n(e.height))
            .append("I").append(s.gridX).append("J").append(s.gridY).append("H").append(n(s.clearance))
        if (goToStart) append("P1")
    }

    /**
     * Everything Start buffers ahead of the file, in order (each sent as `buffer <line>`; Studio
     * buffers more than one line too, `M495 …` then `M6T…`). Without leveling: the one M495 line, as
     * Studio sends it. With leveling, so the grid can stay off the stock's edge and can't dive:
     * `M670 Z` (the touch depth, [RunSetup.levelMaxZ]), the scan / Z probe M495 (if any), a leveling
     * M495 on [RunSetup.levelArea], then `M670 Z` back to the machine's own [machineMaxZ].
     */
    fun startLines(s: RunSetup, e: Extents, machineMaxZ: Double): List<String> {
        if (!s.leveling) return listOf(m495(s, e))
        val a = s.levelArea(e)
        val pre = s.copy(leveling = false)
        return listOfNotNull(
            "M670 Z" + n(s.levelMaxZ),
            if (pre.marginAtStart || pre.zOn) m495(pre, e, goToStart = false) else null,
            "M495 X" + n(a.minX) + "Y" + n(a.minY) + "A" + n(a.width) + "B" + n(a.height) +
                "I" + s.gridX + "J" + s.gridY + "H" + n(s.clearance) + "P1",
            "M670 Z" + n(machineMaxZ),
        )
    }

    /** Scan now: the margin trace on its own (laser on, at safe Z; no Z probe). */
    fun scanMargin(e: Extents) = "M495 X${n(e.minX)}Y${n(e.minY)}C${n(e.maxX)}D${n(e.maxY)}"

    /** Commands that set the X/Y work zero for [o] (Current and the 3D probe: none here). */
    fun originLines(s: RunSetup, anchor: Pair<Double, Double>?): List<String> = when (s.origin) {
        // G10 L2 = the work offset itself, in machine coordinates (Studio: "G10L2P0X%1Y%2").
        Origin.ANCHOR1 -> anchor?.let { (ax, ay) -> listOf("G10L2P0X${n(ax + s.offX)}Y${n(ay + s.offY)}") } ?: emptyList()
        Origin.CUSTOM -> s.anchor?.let { a ->
            val w = buildString {
                a.x?.let { append("X").append(n(it + s.aOffX)) }
                a.y?.let { append("Y").append(n(it + s.aOffY)) }
                if (s.anchorSetsZ) append("Z").append(n(a.z!!))
            }
            if (w.isEmpty()) emptyList() else listOf("G10L2P0$w")
        } ?: emptyList()
        Origin.SPINDLE -> listOf("G10L20P0X0Y0")
        else -> emptyList()
    }

    /** `play` takes the path with spaces as 0x01, like the Wi-Fi board's other file commands. */
    fun play(path: String) = "play " + path.replace(' ', '\u0001')
}

/** The leveling grid as `M375.1` prints it (plain rows, or the human-readable table). Rows front to back. */
object Mesh {
    fun parse(text: String): List<List<Double>>? {
        val rows = mutableListOf<List<Double>>()
        var human = false
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty() || l.contains("---") || l == "ok") continue
            val body = if ('|' in l) { human = true; l.substringAfter('|') } else l
            // An empty grid prints every point as "nan" (seen on the Z1 2026-11-22).
            val nums = body.trim().split(Regex("\\s+")).map { if (it.trimStart('-').equals("nan", true)) Double.NaN else it.toDoubleOrNull() }
            if (nums.isEmpty() || nums.any { it == null }) continue
            // The human-readable table ends with the X axis labels (no '|'): skip that line.
            if (human && '|' !in l) continue
            rows += nums.map { it!! }
        }
        if (rows.isEmpty() || rows.any { it.size != rows[0].size }) return null
        // The table prints the back row first.
        return if (human) rows.reversed() else rows
    }

    /** Highest minus lowest point (points without a value are left out). */
    fun spread(g: List<List<Double>>) = g.flatten().filter { it.isFinite() }.let { (it.maxOrNull() ?: 0.0) - (it.minOrNull() ?: 0.0) }

    /** The point furthest from zero (with its sign), or null if no point has a value (an empty grid). */
    fun largest(g: List<List<Double>>): Double? = g.flatten().filter { it.isFinite() }.maxByOrNull { kotlin.math.abs(it) }
}

/** Studio's assist switches: persistent firmware modes, reported in the status `|S:` fields. */
enum class Assist(val label: String, val sub: String, val on: String, val off: String, val sIndex: Int) {
    VACUUM("Auto vacuum", "Ext port on while the spindle runs", "M331", "M332", 3),
    BLOW("Auto blow", "Air on the cut while the spindle runs", "M331.1", "M332.1", 6),
    BED_CLEAN("Bed clean", "Cleans the bed after the job", "M331.2", "M332.2", 7),
    ANTI_STATIC("Anti-static", "For plastics and wood dust", "M331.4", "M332.4", 9);

    /** On/off from the raw `|S:` fields, or null if not reported. */
    fun state(sFields: List<String>?): Boolean? = sFields?.getOrNull(sIndex)?.trim()?.let { it == "1" }
}
