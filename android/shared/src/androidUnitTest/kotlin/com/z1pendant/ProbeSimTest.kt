package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineTarget
import com.z1pendant.platform.Platform
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import com.z1pendant.platform.TextFile
import com.z1pendant.probe.Corner
import com.z1pendant.probe.ProbeKind
import com.z1pendant.probe.ProbeMode
import com.z1pendant.probe.ProbeSetup
import com.z1pendant.probe.RunState
import com.z1pendant.probe.ToolAction
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The probe wizard's controller on the machine simulator (python/z1sim.py + z1sim_probe.py), through
 * the real view model and link: load the probe (M6 T9999 → tool-change wait → calibrated on the
 * setter), Makera's routine (Set work zero) with Undo, Measure only (the pendant's own touches) with
 * Set, and a dead probe. The sim's part: block X −150..−90, Y −150..−110, top −70; pocket
 * X −112..−97, Y −145..−125, floor −78. Skipped when Python isn't available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeSimTest {
    private val port = 22241
    private var sim: Process? = null

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast", "--speed", "20")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_probe_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @After fun stopSim() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        Dispatchers.resetMain()
        unmockkStatic(SystemClock::class)
    }

    private suspend fun idleAt(link: MachineLink, x: Double, y: Double, z: Double) {
        link.sendLine("G53 G0 Z-60"); link.sendLine("G53 G0 X$x Y$y"); link.sendLine("G53 G0 Z$z")
        withTimeout(20_000) {
            while (link.status.value?.let { it.isIdle && it.mpos[0] == x && it.mpos[1] == y && it.mpos[2] == z } != true) delay(30)
        }
        delay(300)
    }

    private fun offsets(link: MachineLink) = link.status.value!!.let { st -> (0..2).map { st.mpos[it] - st.wpos[it] } }

    private suspend fun runAndWait(vm: AppViewModel): RunState {
        vm.probe.start()
        return withTimeout(120_000) {
            while (vm.probe.run.value is RunState.Running || vm.probe.run.value is RunState.Idle) delay(50)
            vm.probe.run.value
        }
    }

    @Test fun loadMakeraUndoMeasureSet() = runBlocking {
        val vm = AppViewModel(ProbeFakePlatform())
        val link = vm.link
        val p = vm.probe
        vm.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        link.sendLine("\$H"); link.sendLine("M493.2 T4")
        withTimeout(20_000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -1.0 && it.tool == 4 } != true) delay(50) }

        // Load the probe: tool change, wait for the swap, continue, calibrated on the setter.
        p.loadProbe()
        withTimeout(30_000) { while (p.toolAction.value?.stage != ToolAction.Stage.WAITING) delay(30) }
        p.continueToolChange()
        withTimeout(30_000) { while (!p.calibrated.value) delay(30) }
        assertTrue(p.confirmed.value)
        withTimeout(5_000) { while (link.status.value?.tool != 9999) delay(30) }
        // First calibration of the 3D probe: its setter Z is learned. Picking the laser probe now
        // says it reads like the 3D probe; "It's the 3D probe" switches back and matches.
        p.lengthCheck.value!!.let { assertEquals(com.z1pendant.probe.ProbeLength.Result.LEARNED, it.result); assertTrue("${it.z}", it.z < -60 && it.z > -80) }
        p.update(p.setup.value.withTool(com.z1pendant.probe.ProbeTool.LASER))
        assertEquals(ProbeKind.TOP, p.setup.value.kind)
        p.lengthCheck.value!!.let { assertEquals(com.z1pendant.probe.ProbeLength.Result.LOOKS_LIKE, it.result); assertEquals(com.z1pendant.probe.ProbeTool.PROBE_3D, it.other) }
        p.switchToMatch()
        assertEquals(com.z1pendant.probe.ProbeLength.Result.MATCH, p.lengthCheck.value!!.result)
        // Opening probing again needs a new calibration (the probe could have been swapped by hand);
        // while it runs, it isn't calibrated (Next stays locked).
        p.closeWizard(); p.openWizard()
        assertFalse(p.calibrated.value); assertEquals(null, p.lengthCheck.value)
        assertTrue("the probe is still in", p.confirmed.value)
        p.calibrate()
        assertFalse(p.calibrated.value)
        withTimeout(30_000) { while (!p.calibrated.value) delay(30) }
        assertEquals(com.z1pendant.probe.ProbeLength.Result.MATCH, p.lengthCheck.value!!.result)

        // Set work zero, outside corner front-left: Makera's routine writes X0 Y0 Z0 at the corner.
        idleAt(link, -145.0, -145.0, -65.0)
        val before = offsets(link)
        p.update(ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT))
        p.mode.value = ProbeMode.SET_ZERO
        val r1 = (runAndWait(vm) as RunState.Done).result
        assertEquals(setOf('X', 'Y', 'Z'), r1.zeroSet)
        delay(300)
        val o1 = offsets(link)
        assertEquals(-150.0, o1[0], 1e-3); assertEquals(-150.0, o1[1], 1e-3); assertEquals(-70.0, o1[2], 1e-3)
        assertEquals(-150.0 - before[0], r1.zeroX!!, 1e-3)
        // Undo puts the old X and Y back.
        p.undoXY(r1)
        withTimeout(5_000) { while (kotlin.math.abs(offsets(link)[0] - before[0]) > 1e-3) delay(30) }
        assertEquals(before[1], offsets(link)[1], 1e-3)
        p.reset()

        // Set work zero, X only with Z off (Makera has no routine for it). Z0 is on the top from the
        // run above; parked 25 mm over it, the pendant touches the X side 2 mm below Z0 and writes X0 only.
        idleAt(link, -145.0, -145.0, -45.0)
        val b1 = offsets(link)
        p.update(ProbeSetup(ProbeKind.OUTSIDE, Corner.FRONT_LEFT, axisY = false, axisZ = false))
        val r1b = (runAndWait(vm) as RunState.Done).result
        assertEquals(setOf('X'), r1b.zeroSet)
        withTimeout(5_000) { while (kotlin.math.abs(offsets(link)[0] + 150.0) > 1e-3) delay(30) }
        assertEquals("Y untouched", b1[1], offsets(link)[1], 1e-3); assertEquals("Z untouched", b1[2], offsets(link)[2], 1e-3)
        p.reset()

        // Measure only, hole center (the pocket): nothing changes; then Set X Y.
        idleAt(link, -103.0, -136.0, -73.0)
        val b2 = offsets(link)
        p.update(ProbeSetup(ProbeKind.HOLE, axisX = true, axisY = true))
        p.mode.value = ProbeMode.MEASURE
        val r2 = (runAndWait(vm) as RunState.Done).result
        assertEquals(-104.5, r2.machine.centerX!!, 1e-3); assertEquals(-135.0, r2.machine.centerY!!, 1e-3)
        assertEquals(15.0, r2.widthX!!, 1e-3); assertEquals(20.0, r2.widthY!!, 1e-3)
        assertTrue(r2.zeroSet.isEmpty())
        val o2 = offsets(link)
        for (i in 0..2) assertEquals("measure only changes nothing", b2[i], o2[i], 1e-6)
        p.setZero(r2, setOf('X', 'Y'))
        withTimeout(5_000) { while (kotlin.math.abs(offsets(link)[0] + 104.5) > 1e-3) delay(30) }
        assertEquals(-135.0, offsets(link)[1], 1e-3)
        p.reset()

        // Measure only, inside corner back-left of the pocket (−112, −125), from just outside it.
        idleAt(link, -115.0, -122.0, -65.0)
        p.update(ProbeSetup(ProbeKind.INSIDE, Corner.BACK_LEFT, x = 8.0, y = 8.0))
        val r3 = (runAndWait(vm) as RunState.Done).result
        assertEquals(-112.0, r3.machine.edgeX!!, 1e-3); assertEquals(-125.0, r3.machine.edgeY!!, 1e-3); assertEquals(-70.0, r3.machine.top!!, 1e-3)
        p.reset()

        // Measure only, block center (block center −120, −130; 60 × 40).
        idleAt(link, -118.0, -128.0, -65.0)
        p.update(ProbeSetup(ProbeKind.BLOCK, x = 40.0, y = 30.0))
        val r4 = (runAndWait(vm) as RunState.Done).result
        assertEquals(-120.0, r4.machine.centerX!!, 1e-3); assertEquals(-130.0, r4.machine.centerY!!, 1e-3)
        assertEquals(60.0, r4.widthX!!, 1e-3); assertEquals(40.0, r4.widthY!!, 1e-3)
        p.reset()

        // Edge, 2 points along, on a part turned 0.2° (counter-clockwise from above): the front edge
        // runs back toward the right, 6 mm × tan 0.2° = 0.021 mm over the length. Multiple points
        // only measure, even with Set work zero picked.
        link.sendLine("simprobe skew 0.2")
        idleAt(link, -118.0, -145.0, -65.0)
        p.mode.value = ProbeMode.SET_ZERO
        p.update(ProbeSetup(ProbeKind.EDGE, edge = com.z1pendant.probe.Side.FRONT, edgeMulti = true, edgeCount = 2, edgeLength = 6.0))
        val r5 = (runAndWait(vm) as RunState.Done).result
        assertEquals(ProbeMode.MEASURE, r5.mode); assertEquals(emptySet<Char>(), r5.zeroSet)
        assertEquals(0.02094, r5.fit!!.delta, 0.002)
        assertEquals(0.2, r5.fit!!.degrees, 0.02)
        assertNull("2 touches: no straightness", r5.fit!!.straightness)
        p.reset()

        // 1 point, Set work zero: Y0 goes on the edge, X untouched.
        idleAt(link, -118.0, -145.0, -65.0)
        val b5 = offsets(link)
        p.update(p.setup.value.copy(edgeMulti = false))
        val r6 = (runAndWait(vm) as RunState.Done).result
        assertEquals(setOf('Y', 'Z'), r6.zeroSet)
        withTimeout(5_000) { while (kotlin.math.abs(offsets(link)[1] - r6.machine.edgeY!!) > 1e-3) delay(30) }
        assertEquals("X untouched", b5[0], offsets(link)[0], 1e-3)
        p.reset(); p.mode.value = ProbeMode.MEASURE

        // Started beside the front edge, below the top (Z −72, top −70): 4 touches over 30 mm to the
        // right, no top touch and no Z move. The 0.2° turn shows as 30 × tan 0.2° = 0.105 mm, and the
        // edge is straight (all four on the line).
        idleAt(link, -130.0, -155.0, -72.0)
        p.update(ProbeSetup(ProbeKind.EDGE, edge = com.z1pendant.probe.Side.FRONT, edgeFromHere = true, y = 10.0,
            edgeMulti = true, edgeCount = 4, edgeLength = 30.0, alongSign = 1))
        val r7 = (runAndWait(vm) as RunState.Done).result
        assertNull("no top", r7.machine.top)
        assertEquals(4, r7.machine.edgeTouches.size)
        assertEquals(0.1047, r7.fit!!.delta, 0.003)
        assertEquals(0.2, r7.fit!!.degrees, 0.01)
        assertTrue("straight", r7.fit!!.straightness!! < 0.002)
        val end = withTimeout(10_000) { delay(400); while (link.status.value?.isIdle != true) delay(30); link.status.value!! }
        assertEquals("backs off to where it started", -155.0, end.mpos[1], 1e-3)
        assertEquals("never moved Z", -72.0, end.mpos[2], 1e-3)
        assertEquals("back along to where it was parked", -130.0, end.mpos[0], 1e-3)
        link.sendLine("simprobe skew 0")
        p.reset()

        // A dead probe: the touch never comes, the machine alarms, the run says so.
        link.sendLine("simprobe dead")
        idleAt(link, -140.0, -140.0, -65.0)
        p.update(ProbeSetup(ProbeKind.TOP))
        val f = runAndWait(vm) as RunState.Failed
        assertTrue(f.reason, "didn't touch" in f.reason || "Alarm" in f.reason)
        // No touch: the probe may be dead, so it has to be calibrated (tested) again.
        if ("didn't touch" in f.reason) assertFalse(p.calibrated.value)
        link.sendLine("\$X"); link.sendLine("simprobe ok")
        p.reset()

        // Another tool number: the probe is out.
        link.sendLine("M493.2 T4")
        withTimeout(5_000) { while (p.confirmed.value) delay(30) }
        assertFalse(p.calibrated.value)
        link.disconnect()
    }
}

private class ProbeFakePlatform : Platform {
    private val map = HashMap<String, Any?>()
    override val prefs = object : Prefs {
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
        override fun getString(key: String, default: String?) = map[key] as? String ?: default
        override fun edit(block: PrefsEditor.() -> Unit) {
            object : PrefsEditor {
                override fun putBoolean(key: String, value: Boolean) { map[key] = value }
                override fun putInt(key: String, value: Int) { map[key] = value }
                override fun putLong(key: String, value: Long) { map[key] = value }
                override fun putFloat(key: String, value: Float) { map[key] = value }
                override fun putString(key: String, value: String) { map[key] = value }
            }.block()
        }
    }
    override val hotkeyFile = object : TextFile {
        var text: String? = null
        override fun exists() = text != null
        override fun readText() = text ?: ""
        override fun writeText(text: String) { this.text = text }
    }
    override val haptics = object : Haptics {
        override var strength = 0f
        override val description = "none"
        override fun tick() {}
        override fun majorTick() {}
        override fun thud() {}
        override fun limit() {}
        override fun press() {}
    }
}
