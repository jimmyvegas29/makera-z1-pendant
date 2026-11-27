package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.makera.MakeraLink
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import com.z1pendant.run.AnchorsController
import com.z1pendant.run.RunCommands
import com.z1pendant.run.RunSetup
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Custom anchors against the simulator: a probed work zero is read back with `$#` into a new anchor,
 * it's saved per machine, and Run's G10 L2 for it puts the work zero back exactly there.
 */
class AnchorsSimTest {
    private val port = 22263
    private var sim: Process? = null

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_anchors_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @After fun stopSim() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        unmockkStatic(SystemClock::class)
    }

    private class MemPrefs : Prefs {
        val map = HashMap<String, Any?>()
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

    @Test fun probedZeroBecomesAnAnchorAndRunPutsItBack() = runBlocking {
        val link = MakeraLink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = MemPrefs()
        val machine = MutableStateFlow<MachineTarget?>(MachineTarget("Makera_Z1P_000001", "127.0.0.1", port))
        val anchors = AnchorsController(link, scope, prefs, machine)
        link.connect(machine.value!!)
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }

        // Stand-in for the probe wizard setting X, Y and Z zero on a vise jaw.
        link.sendLine("G10L2P0X-150.25Y-110.6Z-82.4")
        anchors.startNew()
        anchors.updateDraft(anchors.draft.value!!.copy(name = "Vise fixed jaw"))
        anchors.probeForDraft()
        anchors.probeReturned(setOf('X', 'Y', 'Z'))
        withTimeout(5000) { while (anchors.draft.value?.z == null) delay(20) }
        val d = anchors.draft.value!!
        assertEquals(-150.25, d.x!!, 1e-6); assertEquals(-110.6, d.y!!, 1e-6); assertEquals(-82.4, d.z!!, 1e-6)
        assertEquals("3D probe", d.how)
        assertNull(anchors.saveDraft())
        val saved = anchors.list.value.single()

        // Saved per machine: another machine has none, and coming back shows it again.
        machine.value = MachineTarget("Makera_Z1P_000002", "127.0.0.2", port)
        withTimeout(2000) { while (anchors.list.value.isNotEmpty()) delay(10) }
        machine.value = MachineTarget("Makera_Z1P_000001", "127.0.0.1", port)
        withTimeout(2000) { while (anchors.list.value.isEmpty()) delay(10) }
        assertEquals(saved, anchors.list.value.single())

        // Somewhere else, then Run's origin for the anchor (with an offset) puts the zero back.
        link.sendLine("G10L2P0X-10Y-10Z-10")
        RunCommands.originLines(RunSetup().withAnchor(saved).copy(aOffX = 1.0), null).forEach { link.sendLine(it) }
        delay(300)
        val g = anchors.readWorkOffset()
        assertNotNull(g)
        assertEquals(-149.25, g!!.first, 1e-6); assertEquals(-110.6, g.second, 1e-6); assertEquals(-82.4, g.third!!, 1e-6)

        // Re-probe: measured a little off, compared, saved.
        link.sendLine("G10L2P0X-150.24Y-110.61")
        anchors.probeAgain(saved)
        anchors.probeReturned(setOf('X', 'Y'))
        withTimeout(5000) { while (anchors.compare.value == null) delay(20) }
        val c = anchors.compare.value!!
        assertNull("Z wasn't probed again", c.z)
        assertEquals(0.01, c.maxChange, 1e-6)
        anchors.saveMeasured()
        val now = anchors.list.value.single()
        assertEquals(-150.24, now.x!!, 1e-6); assertEquals(-82.4, now.z!!, 1e-6)

        // Save as anchor from a probe result. It set the work zero on X Y Z: read back from the machine.
        link.sendLine("G10L2P0X-120.5Y-130.25Z-75.5")
        assertNull(anchors.saveFromProbe("Fixture", setOf('X', 'Y', 'Z'), null, null))
        anchors.list.value.first { it.name == "Fixture" }.let {
            assertEquals(-120.5, it.x!!, 1e-6); assertEquals(-130.25, it.y!!, 1e-6); assertEquals(-75.5, it.z!!, 1e-6); assertEquals("3D probe", it.how)
        }
        assertEquals("There's already an anchor called fixture.", anchors.saveFromProbe("fixture", setOf('X'), null, null))
        // Measure only: the measured point, no Z.
        assertNull(anchors.saveFromProbe("Edge mark", emptySet(), -60.0, null))
        anchors.list.value.first { it.name == "Edge mark" }.let { assertEquals(-60.0, it.x!!, 1e-9); assertNull(it.y); assertNull(it.z) }
        assertEquals("Keep at least X or Y.", anchors.saveFromProbe("Nothing", emptySet(), null, null))
        // The X / Y / Z chips: Z left out keeps X and Y only.
        val pt = anchors.probedPoint(setOf('X', 'Y', 'Z'), null, null)!!
        assertEquals(-75.5, pt.z!!, 1e-6)
        assertNull(anchors.saveProbed("Jaw XY", pt, setOf('X', 'Y')))
        anchors.list.value.first { it.name == "Jaw XY" }.let { assertEquals(-120.5, it.x!!, 1e-6); assertEquals(-130.25, it.y!!, 1e-6); assertNull(it.z) }
        assertEquals("Keep at least X or Y.", anchors.saveProbed("Only Z", pt, setOf('Z')))

        link.disconnect(); scope.cancel()
    }
}
