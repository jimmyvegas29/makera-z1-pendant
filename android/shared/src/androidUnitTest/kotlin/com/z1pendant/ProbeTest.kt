package com.z1pendant

import com.z1pendant.probe.Corner
import com.z1pendant.probe.EdgeFit
import com.z1pendant.probe.Pick
import com.z1pendant.probe.missingPick
import com.z1pendant.probe.Side
import com.z1pendant.probe.Steps
import com.z1pendant.probe.ProbeIo
import com.z1pendant.probe.ProbeKind
import com.z1pendant.probe.ProbeSequences
import com.z1pendant.probe.ProbeSetup
import com.z1pendant.probe.ProbeStop
import com.z1pendant.probe.Touch
import com.z1pendant.probe.parsePrb
import com.z1pendant.probe.stepAfterTouches
import com.z1pendant.probe.stepLabels
import com.z1pendant.probe.touchesInStep
import com.z1pendant.probe.widthsFromTouches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.z1pendant.probe.ProbeLength.Result as R
import com.z1pendant.probe.ProbeTool as T
import kotlin.math.abs

/**
 * The probing sequences against a simulated part: a block standing on the table, or a pocket cut
 * into a slab. The fake machine follows G53 moves and answers G38.2 with where a ball of the given
 * diameter would first touch, like the Z1's [PRB:] report.
 */
class ProbeTest {
    private val eps = 1e-6

    /** A block [x0,x1]×[y0,y1] with its top at [top]; or, if [pocket], a slab with that pocket cut [depth] deep. */
    private class World(val x0: Double, val x1: Double, val y0: Double, val y1: Double, val top: Double,
                        val pocket: Boolean = false, val depth: Double = 10.0, val tip: Double = 2.0) : ProbeIo {
        var x = 0.0; var y = 0.0; var z = 0.0
        val lines = mutableListOf<String>()
        private val r = tip / 2
        private fun inside(px: Double, py: Double) = px in x0..x1 && py in y0..y1
        private fun surfaceAt(px: Double, py: Double) =
            if (pocket) (if (inside(px, py)) top - depth else top) else (if (inside(px, py)) top else top - 100)

        override suspend fun send(line: String) {
            lines += line
            Regex("""G53 G[01] ((?:[XYZ]-?[\d.]+ ?)+)""").find(line)?.groupValues?.get(1)?.split(' ')?.filter { it.isNotBlank() }?.forEach {
                val v = it.drop(1).toDouble()
                when (it[0]) { 'X' -> x = v; 'Y' -> y = v; 'Z' -> z = v }
            }
            if (z < surfaceAt(x, y) - 1e-9) throw ProbeStop("crashed into the part at $x,$y,$z")
        }

        override suspend fun probe(line: String): Touch {
            lines += line
            val m = Regex("""G38\.2 ([XYZ])(-?[\d.]+)""").find(line)!!
            val axis = m.groupValues[1][0]; val d = m.groupValues[2].toDouble()
            val dir = if (d > 0) 1 else -1
            val stop: Double? = when (axis) {
                'Z' -> surfaceAt(x, y).takeIf { it in (z - abs(d))..z }
                'X' -> wall(x, dir, y, true)?.takeIf { (it - x) * dir in 0.0..abs(d) }
                else -> wall(y, dir, x, false)?.takeIf { (it - y) * dir in 0.0..abs(d) }
            }
            stop ?: throw ProbeStop("no touch on $line from $x,$y,$z")
            when (axis) { 'X' -> x = stop; 'Y' -> y = stop; else -> z = stop }
            return Touch(x, y, z)
        }

        /** Ball center where it meets a side moving along one axis (only below the top). */
        private fun wall(p: Double, dir: Int, other: Double, isX: Boolean): Double? {
            val lo = if (isX) x0 else y0; val hi = if (isX) x1 else y1
            val oLo = if (isX) y0 else x0; val oHi = if (isX) y1 else x1
            if (z >= top) return null
            return if (pocket) {
                if (other !in oLo..oHi) null else if (dir < 0) lo + r else hi - r
            } else {
                if (other !in oLo..oHi) null else if (dir > 0 && p < lo) lo - r else if (dir < 0 && p > hi) hi + r else null
            }
        }
    }

    private fun run(w: World, s: ProbeSetup, start: Touch, set: Boolean = false, zeroZ: Double? = null) = runBlocking {
        w.x = start.x; w.y = start.y; w.z = start.z
        ProbeSequences(w, s, start, set, zeroZ).run()
    }

    @Test fun makeraCommands() {
        assertEquals("M480.4 X20.000 Y20.000 Z2.000 D2.000", ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT).makeraCommand())
        assertEquals("M480.1 X20.000 Y30.000 Z1.500 D2.000", ProbeSetup(ProbeKind.OUTSIDE, Corner.BACK_LEFT, y = 30.0, depth = 1.5).makeraCommand())
        assertEquals("M480.6 X20.000 Y20.000 Z2.000 D3.175", ProbeSetup(ProbeKind.INSIDE, Corner.BACK_RIGHT, tip = 3.175).makeraCommand())
        assertEquals("M480.9 X20.000 Y0.000 Z2.000 D2.000", ProbeSetup(ProbeKind.HOLE, axisY = false).makeraCommand())
        assertEquals("M480.10 X0.000 Y20.000 Z2.000 D2.000", ProbeSetup(ProbeKind.BLOCK, axisX = false).makeraCommand())
        assertNull(ProbeSetup(ProbeKind.TOP).makeraCommand())
        // Makera has no routine for these: the pendant runs them itself.
        assertNull(ProbeSetup(ProbeKind.OUTSIDE, axisY = false).makeraCommand())
        assertNull(ProbeSetup(ProbeKind.INSIDE, axisZ = false).makeraCommand())
        assertNull(ProbeSetup(ProbeKind.BLOCK, axisZ = false).makeraCommand())
        // A hole has no Z, so its Z toggle doesn't matter.
        assertEquals("M480.9 X20.000 Y20.000 Z2.000 D2.000", ProbeSetup(ProbeKind.HOLE, axisZ = false).makeraCommand())
    }

    @Test fun axisToggles() {
        val s = ProbeSetup(ProbeKind.OUTSIDE)
        assertEquals("X Y Z", s.axesText())
        val xOnly = s.toggle('Y').toggle('Z')
        assertEquals("X", xOnly.axesText())
        assertEquals("the last axis can't be turned off", xOnly, xOnly.toggle('X'))
        assertEquals(listOf("Touch the X side", "Move over the edge"), xOnly.stepLabels())
        assertEquals(listOf("Touch the top"), s.toggle('X').toggle('Y').stepLabels())
        // Z only, then Hole center (no Z there): everything back on rather than nothing to do.
        val hole = s.toggle('X').toggle('Y').copy(kind = ProbeKind.HOLE).normalized()
        assertTrue(hole.doX && hole.doY && !hole.doZ)
        assertEquals("Outside corner · front-left · X", xOnly.title())
    }

    @Test fun outsideCornerXOnlyWithoutZ() {
        // Z off: no top touch. Z0 is on the top (machine −60) and the probe is parked way up at −20:
        // it goes down to 2 mm below Z0, not 2 mm below where it was parked.
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT, axisY = false, axisZ = false), Touch(5.0, 5.0, -20.0), zeroZ = -60.0)
        assertEquals(0.0, m.edgeX!!, eps); assertNull(m.edgeY); assertNull(m.top)
        assertTrue("never touches Z", w.lines.none { it.startsWith("G38.2 Z") })
        assertTrue("never touches Y", w.lines.none { it.startsWith("G38.2 Y") })
        assertTrue(w.lines.contains("G53 G1 Z-62.000 F300"))
        assertEquals("over the X edge, Y and Z where it started", listOf(0.0, 5.0, -20.0), listOf(w.x, w.y, w.z))
        // No Z0 to go by: it refuses rather than guessing.
        val e = runCatching { run(World(0.0, 50.0, 0.0, 40.0, top = -60.0), ProbeSetup(ProbeKind.OUTSIDE, axisZ = false), Touch(5.0, 5.0, -20.0)) }.exceptionOrNull()
        assertTrue(e is ProbeStop)
        // Parked at or below Z0 with Z off: it won't start (it would sweep into the part).
        assertTrue(ProbeSetup(ProbeKind.OUTSIDE, axisZ = false).startProblem(0.3) != null)
        assertNull(ProbeSetup(ProbeKind.OUTSIDE, axisZ = false).startProblem(40.0))
        assertNull("Z on touches the top itself", ProbeSetup(ProbeKind.OUTSIDE).startProblem(-5.0))
    }

    @Test fun cornerXAndZWithoutY() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.OUTSIDE, Corner.BACK_RIGHT, axisY = false), Touch(45.0, 35.0, -55.0))
        assertEquals(50.0, m.edgeX!!, eps); assertNull(m.edgeY); assertEquals(-60.0, m.top!!, eps)
        assertEquals(listOf(50.0, 35.0, -55.0), listOf(w.x, w.y, w.z))
    }

    @Test fun insideCornerYOnly() {
        val w = World(0.0, 40.0, 0.0, 30.0, top = -60.0, pocket = true)
        val m = run(w, ProbeSetup(ProbeKind.INSIDE, Corner.BACK_LEFT, x = 10.0, y = 10.0, axisX = false), Touch(-5.0, 35.0, -55.0))
        assertNull(m.edgeX); assertEquals(30.0, m.edgeY!!, eps); assertEquals(-60.0, m.top!!, eps)
    }

    @Test fun blockCenterWithoutZ() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.BLOCK, x = 35.0, y = 30.0, axisZ = false), Touch(24.0, 21.0, -40.0), zeroZ = -60.0)
        assertEquals(25.0, m.centerX!!, eps); assertEquals(20.0, m.centerY!!, eps); assertNull(m.top)
        assertTrue(w.lines.none { it.startsWith("G38.2 Z") })
    }

    @Test fun outsideCornerFrontLeftAndBackRight() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT), Touch(5.0, 5.0, -55.0))
        assertEquals(0.0, m.edgeX!!, eps); assertEquals(0.0, m.edgeY!!, eps); assertEquals(-60.0, m.top!!, eps)
        assertEquals("parks over the corner at the start height", listOf(0.0, 0.0, -55.0), listOf(w.x, w.y, w.z))
        assertTrue("never sets the zero in Measure only", w.lines.none { it.startsWith("G10") })
        assertTrue("no relative moves", w.lines.none { "G91" in it })

        val w2 = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m2 = run(w2, ProbeSetup(ProbeKind.OUTSIDE, Corner.BACK_RIGHT, depth = 1.5), Touch(45.0, 35.0, -55.0))
        assertEquals(50.0, m2.edgeX!!, eps); assertEquals(40.0, m2.edgeY!!, eps)
    }

    @Test fun edgeOnePoint() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val s = ProbeSetup(ProbeKind.EDGE, edge = Side.FRONT)
        assertEquals(listOf(Steps.TOP, Steps.Y_SIDE, Steps.EDGE), s.stepLabels())
        assertEquals("Edge · front", s.title())
        assertNull("no Makera routine for an edge", s.makeraCommand())
        val m = run(w, s, Touch(20.0, 5.0, -55.0))
        assertEquals(0.0, m.edgeY!!, eps); assertNull(m.edgeX); assertEquals(-60.0, m.top!!, eps); assertEquals(listOf(0.0), m.edgeTouches)
        assertEquals("parks over the edge at the start height", listOf(20.0, 0.0, -55.0), listOf(w.x, w.y, w.z))
        assertTrue("never touches X", w.lines.none { it.startsWith("G38.2 X") })

        // The edge's own Z choice, not the corners' Z toggle.
        assertTrue("axisZ off doesn't turn off an edge's top touch", ProbeSetup(ProbeKind.EDGE, axisZ = false).doZ)
    }

    @Test fun edgeStartFromHere() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        // Parked beside the right edge, 5 mm out and 2 mm below the top.
        val s = ProbeSetup(ProbeKind.EDGE, edge = Side.RIGHT, edgeFromHere = true, x = 10.0)
        assertEquals(listOf(Steps.X_SIDE, Steps.BACK_OFF), s.stepLabels())
        assertNull("it doesn't move Z, so there's no Z0 to need", s.startProblem(-30.0))
        val m = run(w, s, Touch(55.0, 20.0, -62.0))
        assertEquals(50.0, m.edgeX!!, eps); assertNull(m.top)
        assertTrue("no top touch", w.lines.none { it.startsWith("G38.2 Z") })
        assertTrue("never moves Z", w.lines.none { "Z" in it && it.startsWith("G53") })
        assertTrue("searches at most the search distance", "G38.2 X-10.000 F100" in w.lines)
        assertEquals("backs off to where it started", listOf(55.0, 20.0, -62.0), listOf(w.x, w.y, w.z))
    }

    @Test fun edgeMultipleAlongTopFirst() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        // Right edge (probe comes in −X), 2 touches 6 mm toward the front (−Y).
        val s = ProbeSetup(ProbeKind.EDGE, edge = Side.RIGHT, edgeMulti = true, edgeCount = 2, edgeLength = 6.0, alongSign = -1)
        assertEquals(listOf(Steps.TOP, "Touch 1 of 2", "Touch 2 of 2", Steps.EDGE), s.stepLabels())
        assertEquals("Edge · right · 2 points", s.title()); assertEquals("front", s.alongWord())
        assertEquals(listOf(0.0, -6.0), s.alongOffsets())
        val m = run(w, s, Touch(45.0, 30.0, -55.0))
        assertEquals(listOf(50.0, 50.0), m.edgeTouches); assertEquals(listOf(30.0, 24.0), m.edgeAlong)
        // Before moving along the edge it goes back out to the full offset (X 65), at the start height.
        val out = w.lines.indexOfLast { it == "G53 G0 X65.000" }
        assertEquals("then along the edge", "G53 G0 Y24.000", w.lines[out + 1])
        assertTrue("the second touch comes after", w.lines.drop(out).count { it.startsWith("G38.2 X") } == 2)
        assertEquals("parks over the first touch's spot on the edge", listOf(50.0, 30.0, -55.0), listOf(w.x, w.y, w.z))
    }

    @Test fun edgeMultipleFromHere() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        // Beside the front edge (Y 0), 4 touches over 12 mm to the right.
        val s = ProbeSetup(ProbeKind.EDGE, edge = Side.FRONT, edgeFromHere = true, y = 8.0, edgeMulti = true, edgeCount = 4, edgeLength = 12.0)
        assertEquals(4.0, s.spacing, eps)
        assertEquals(listOf("Touch 1 of 4", "Touch 2 of 4", "Touch 3 of 4", "Touch 4 of 4", Steps.BACK_OFF), s.stepLabels())
        val m = run(w, s, Touch(20.0, -4.0, -62.0))
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), m.edgeTouches); assertEquals(listOf(20.0, 24.0, 28.0, 32.0), m.edgeAlong)
        assertEquals("8 touches (fast + slow each)", 8, w.lines.count { it.startsWith("G38.2 Y") })
        assertTrue("never moves Z", w.lines.none { "Z" in it && it.startsWith("G53") })
        assertEquals("ends where it was parked", listOf(20.0, -4.0, -62.0), listOf(w.x, w.y, w.z))
    }

    @Test fun edgeFitMath() {
        val q = EdgeFit(listOf(0.0, 6.0), listOf(0.0, 0.021), outSign = 1)
        assertEquals(0.2005, q.degrees, 1e-3); assertEquals(0.35, q.per100, 1e-9); assertEquals(0.021, q.delta, 1e-9)
        assertNull(q.straightness); assertNull(q.shape())
        assertEquals(0.0675, q.noiseDegrees, 1e-3)
        // 5 touches over 80 mm, 0.30° out, hollow in the middle by 0.008 mm.
        val t = listOf(0.0, 20.0, 40.0, 60.0, 80.0)
        val bow = listOf(0.008, -0.004, -0.008, -0.004, 0.008)
        val slope = kotlin.math.tan(0.3 * kotlin.math.PI / 180)
        val f = EdgeFit(t, t.indices.map { -0.078 + slope * t[it] + bow[it] }, outSign = 1)
        assertEquals(0.3, f.degrees, 1e-6); assertEquals(0.016, f.straightness!!, 1e-9)
        bow.indices.forEach { assertEquals(bow[it], f.offLine[it], 1e-9) }
        assertTrue(f.shape()!!, "hollow" in f.shape()!!)
        // The same bow seen from the other side of the part bulges out instead.
        assertTrue("bulges" in EdgeFit(t, t.indices.map { -0.078 + slope * t[it] + bow[it] }, outSign = -1).shape()!!)
        // Straight within the probe's repeat.
        assertTrue("Straight" in EdgeFit(t, t.map { slope * it }, 1).shape()!!)
    }

    @Test fun edgeTravelCheck() {
        val lim = com.z1pendant.link.SoftLimits.Z1                      // X −207..−1, Y −206..−1
        // Right edge, 3 touches 100 mm toward the front from Y −150: the third would be at Y −250.
        val s = ProbeSetup(ProbeKind.EDGE, edge = Side.RIGHT, edgeMulti = true, edgeCount = 3, edgeLength = 100.0, alongSign = -1)
        val c = s.travelCheck(Touch(-100.0, -150.0, -60.0), lim)
        assertEquals(setOf(2), c.bad)
        assertEquals("Touch 3 would be past the Y travel: shorten the length or start further back.", c.problem)
        assertNull(s.copy(edgeLength = 40.0).travelCheck(Touch(-100.0, -150.0, -60.0), lim).problem)
        // Touching the top first it steps out past the edge by the start offset: that has to fit too.
        val out = ProbeSetup(ProbeKind.EDGE, edge = Side.RIGHT, x = 20.0).travelCheck(Touch(-10.0, -100.0, -60.0), lim)
        assertEquals(setOf(0), out.bad); assertTrue(out.problem!!, "start offset" in out.problem!!)
        assertNull("soft limits off: nothing to check", s.travelCheck(Touch(-100.0, -150.0, -60.0), lim.copy(enabled = false)).problem)
    }

    @Test fun setupPicks() {
        val e = ProbeSetup(ProbeKind.EDGE)
        assertEquals("Pick an operation", e.missingPick(emptySet()))
        assertEquals("Pick an edge", e.missingPick(setOf(Pick.OPERATION)))
        assertEquals("Pick where it starts", e.missingPick(setOf(Pick.OPERATION, Pick.SIDE)))
        assertEquals("Pick the touches", e.missingPick(setOf(Pick.OPERATION, Pick.SIDE, Pick.START)))
        assertNull(e.missingPick(setOf(Pick.OPERATION, Pick.SIDE, Pick.START, Pick.POINTS)))
        assertEquals("Pick which way", e.copy(edgeMulti = true).missingPick(setOf(Pick.OPERATION, Pick.SIDE, Pick.START, Pick.POINTS)))
        assertEquals("Pick a corner", ProbeSetup(ProbeKind.OUTSIDE).missingPick(setOf(Pick.OPERATION)))
        assertNull(ProbeSetup(ProbeKind.HOLE).missingPick(setOf(Pick.OPERATION)))
    }

    @Test fun insideCornerBackLeft() {
        val w = World(0.0, 40.0, 0.0, 30.0, top = -60.0, pocket = true)
        // Start over the slab just outside the pocket's back-left corner; X/Y 10 lead into the pocket.
        val m = run(w, ProbeSetup(ProbeKind.INSIDE, Corner.BACK_LEFT, x = 10.0, y = 10.0), Touch(-5.0, 35.0, -55.0))
        assertEquals(0.0, m.edgeX!!, eps); assertEquals(30.0, m.edgeY!!, eps); assertEquals(-60.0, m.top!!, eps)
    }

    @Test fun holeCenterBothAxesAndXOnly() {
        val w = World(0.0, 40.0, 0.0, 30.0, top = -60.0, pocket = true)
        val m = run(w, ProbeSetup(ProbeKind.HOLE), Touch(18.0, 14.0, -62.0))
        assertEquals(20.0, m.centerX!!, eps); assertEquals(15.0, m.centerY!!, eps)
        assertEquals(40.0, m.widthX!!, eps); assertEquals(30.0, m.widthY!!, eps)
        assertNull("a hole has no Z", m.top)

        val w2 = World(0.0, 40.0, 0.0, 30.0, top = -60.0, pocket = true)
        val m2 = run(w2, ProbeSetup(ProbeKind.HOLE, axisY = false), Touch(18.0, 14.0, -62.0))
        assertEquals(20.0, m2.centerX!!, eps); assertNull(m2.centerY)
        assertTrue(w2.lines.none { it.contains(" Y") && it.startsWith("G38") })
    }

    @Test fun blockCenter() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.BLOCK, x = 35.0, y = 30.0), Touch(24.0, 21.0, -55.0))
        assertEquals(25.0, m.centerX!!, eps); assertEquals(20.0, m.centerY!!, eps)
        assertEquals(50.0, m.widthX!!, eps); assertEquals(40.0, m.widthY!!, eps)
        assertEquals(-60.0, m.top!!, eps)
    }

    @Test fun topOnlySetsZeroOnlyWhenAsked() {
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val m = run(w, ProbeSetup(ProbeKind.TOP), Touch(10.0, 10.0, -55.0), set = true)
        assertEquals(-60.0, m.top!!, eps)
        val i = w.lines.indexOf("G10 L20 P0 Z0")
        assertTrue("Z0 written right after the slow touch", i > 0 && w.lines[i - 1].startsWith("G38.2 Z-3"))
        assertEquals(-55.0, w.z, eps)

        val w2 = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        run(w2, ProbeSetup(ProbeKind.TOP), Touch(10.0, 10.0, -55.0), set = false)
        assertTrue(w2.lines.none { it.startsWith("G10") })
    }

    @Test fun startOffsetTooShortIsCaughtNotCrashed() {
        // Outside corner with X 3: stepping out 3 mm from 5 mm in is still over the part.
        val w = World(0.0, 50.0, 0.0, 40.0, top = -60.0)
        val e = runCatching { run(w, ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT, x = 3.0), Touch(5.0, 5.0, -55.0)) }.exceptionOrNull()
        assertTrue(e is ProbeStop)
    }

    @Test fun prbAndMakeraWidths() {
        assertEquals(Touch(-9.691, -13.58, -69.637) to true, parsePrb("[PRB:-9.691,-13.580,-69.637:1]"))
        assertEquals(false, parsePrb("[PRB:1.000,2.000,3.000:0]")!!.second)
        assertNull(parsePrb("ok"))
        // A run on the real machine, 2026-11-15, hole center X only: fast/slow on X−, fast/slow on X+.
        val t = listOf(-177.686, -177.683, -144.903, -144.908).map { Touch(it, -164.497, -56.0) }
        val (wx, wy) = widthsFromTouches(ProbeKind.HOLE, ProbeSetup(ProbeKind.HOLE, axisY = false), t)
        assertEquals(34.775, wx!!, 1e-9); assertNull(wy)
    }

    /** Probes told apart by where they trip the setter (values from the 2026-11-15 test). */
    @Test fun probeLengthCheck() {
        val L = com.z1pendant.probe.ProbeLength
        val learned = mapOf(T.PROBE_3D to -69.570, T.ROD to -72.015, T.LASER to -78.537)
        // What each probe can do: the laser probe only Top only (no "use it instead" for a corner).
        for (k in com.z1pendant.probe.ProbeKind.entries) {
            assertTrue(T.PROBE_3D.canDo(k)); assertTrue(T.ROD.canDo(k))
            assertEquals(k == com.z1pendant.probe.ProbeKind.TOP, T.LASER.canDo(k))
        }
        assertEquals(R.MATCH, L.check(T.PROBE_3D, -69.573, learned).result)
        assertEquals(R.MATCH, L.check(T.LASER, -78.526, learned).result)
        // Picked the 3D probe, the laser probe is in.
        L.check(T.PROBE_3D, -78.530, learned).let { assertEquals(R.LOOKS_LIKE, it.result); assertEquals(T.LASER, it.other); assertFalse(it.ok) }
        // Picked the rod, it reads like nothing learned.
        assertEquals(R.UNKNOWN, L.check(T.ROD, -70.6, learned).result)
        // First time with the laser probe, but it reads like the 3D probe: don't learn it.
        L.check(T.LASER, -69.6, mapOf(T.PROBE_3D to -69.570)).let { assertEquals(R.LOOKS_LIKE, it.result); assertEquals(T.PROBE_3D, it.other) }
        // First time, nothing close: NEW (the controller learns it).
        assertEquals(R.NEW, L.check(T.ROD, -72.0, mapOf(T.PROBE_3D to -69.570)).result)
        // The laser probe forces Top only.
        val s = ProbeSetup(kind = ProbeKind.OUTSIDE).withTool(T.LASER)
        assertEquals(ProbeKind.TOP, s.kind)
    }

    @Test fun touchesWithinAStepPickTheSide() {
        // Block center, X and Y: top (2 touches), X sides (4: left fast/slow, right fast/slow), Y sides (4), center.
        val s = ProbeSetup(kind = ProbeKind.BLOCK, axisX = true, axisY = true)
        fun at(n: Int) = s.stepAfterTouches(n).let { it to s.touchesInStep(it, n) }
        assertEquals(0 to 0, at(0))
        assertEquals(1 to 0, at(2))      // X sides started: the left side
        assertEquals(1 to 1, at(3))
        assertEquals(1 to 2, at(4))      // left done, now the right side
        assertEquals(1 to 3, at(5))
        assertEquals(2 to 0, at(6))      // Y sides: the front
        assertEquals(2 to 2, at(8))      // then the back
        // An echo can move the run on before its touches: never negative.
        assertEquals(0, s.touchesInStep(2, 3))
    }
}
