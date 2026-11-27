package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.Bed
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.map.BedMap
import com.z1pendant.map.MapGrid
import com.z1pendant.map.MapMove
import com.z1pendant.map.TargetKind
import com.z1pendant.platform.Platform
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import com.z1pendant.platform.TextFile
import com.z1pendant.run.Anchor
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
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/** The bed map: its grid and targets, and moving there on the simulator with Safe Z on and off. */
class MapTest {
    @Test fun gridSpansTheBedEdgeToEdge() {
        val pts = BedMap.gridPoints(Bed.Z1, MapGrid.G9)
        assertEquals(81, pts.size)
        assertEquals(-207.0, pts.first().x, 1e-9); assertEquals(-206.0, pts.first().y, 1e-9)
        assertEquals(-1.0, pts.last().x, 1e-9); assertEquals(-1.0, pts.last().y, 1e-9)
        assertEquals("front-left is A1", "Grid A1", pts.first().label)
        assertEquals("back-right is I9", "Grid I9", pts.last().label)
        val mid = pts[40]; assertEquals(-104.0, mid.x, 1e-9); assertEquals(-103.5, mid.y, 1e-9); assertEquals("Grid E5", mid.label)
        assertEquals(289, BedMap.gridPoints(Bed.Z1, MapGrid.G17).size)
    }

    @Test fun targetsAndSnapping() {
        val anchors = listOf(Anchor("a", "Vise jaw", -150.0, -60.0), Anchor("b", "X only", -100.0, null), Anchor("c", "Off the bed", -300.0, -50.0))
        val t = BedMap.targets(Bed.Z1, MapGrid.G5, anchors, -171.5 to -158.5)
        assertEquals(listOf("Vise jaw"), t.filter { it.kind == TargetKind.ANCHOR }.map { it.label })
        assertEquals(1, t.count { it.kind == TargetKind.ZERO })
        // A tap right by the vise jaw picks it; one between grid points picks the nearest point.
        assertEquals("Vise jaw", BedMap.nearest(t, -149.0, -61.0, 25.0)!!.label)
        assertEquals("Grid C3", BedMap.nearest(t, -101.0, -100.0, 25.0)!!.label)
        assertNull("too far from anything", BedMap.nearest(BedMap.gridPoints(Bed.Z1, MapGrid.G5), -130.0, -130.0, 5.0))
        assertNull("X0 Y0 off the bed isn't a target", BedMap.targets(Bed.Z1, MapGrid.G5, emptyList(), 10.0 to 10.0).firstOrNull { it.kind == TargetKind.ZERO })
    }

    @Test fun sharedSpotsAreOneTarget() {
        // An anchor saved where X0 Y0 is, and another anchor on a grid point.
        val anchors = listOf(Anchor("a", "Vise jaw", -150.3, -110.6), Anchor("b", "Corner", -104.0, -103.5))
        val t = BedMap.targets(Bed.Z1, MapGrid.G9, anchors, -150.3 to -110.6)
        val jaw = t.single { it.has(TargetKind.ZERO) }
        assertEquals(listOf("Vise jaw", "X0 Y0"), jaw.names.map { it.name })
        assertEquals(TargetKind.ANCHOR, jaw.kind); assertEquals("Vise jaw · X0 Y0", jaw.label)
        val corner = t.single { it.has(TargetKind.ANCHOR) && it.has(TargetKind.GRID) }
        assertEquals(listOf("Corner", "Grid E5"), corner.names.map { it.name })
        assertEquals("the grid point isn't there twice", 81 + 1, t.size)
        // A tap near the shared spot picks the one target.
        assertEquals(jaw, BedMap.nearest(t, -149.0, -111.0, 12.0))
    }

    @Test fun moveLines() {
        val t = BedMap.gridPoints(Bed.Z1, MapGrid.G9)[40]
        assertEquals(listOf("G90", "G53 G0 Z-2.000", "G53 G0 X-104.000 Y-103.500"), BedMap.moveLines(t))
        assertEquals("Safe Z off: Z stays", listOf("G90", "G53 G0 X-104.000 Y-103.500"), BedMap.moveLines(t, safeZ = false))
    }
}

/** Moving from the map on the simulator: Z up first, then X/Y; with Safe Z off, Z stays put. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapSimTest {
    private val port = 22271
    private var sim: Process? = null

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val script = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast", "--speed", "20")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_map_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @After fun stopSim() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        Dispatchers.resetMain(); unmockkStatic(SystemClock::class)
    }

    private suspend fun done(vm: AppViewModel): MapMove = withTimeout(60_000) {
        while (vm.mapMove.value?.let { it.stage == MapMove.Stage.DONE || it.error != null } != true) delay(30)
        vm.mapMove.value!!
    }

    @Test fun moveWithAndWithoutSafeZ() = runBlocking {
        val vm = AppViewModel(MapFakePlatform())
        val link = vm.link
        vm.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        link.sendLine("\$H")
        link.sendLine("G53 G0 Z-40")
        withTimeout(20_000) { while (link.status.value?.let { it.isIdle && it.mpos[2] == -40.0 } != true) delay(30) }

        val mid = BedMap.gridPoints(Bed.Z1, MapGrid.G9)[40]
        vm.mapGo(mid)
        assertEquals(MapMove.Stage.LIFT, vm.mapMove.value!!.stage)
        val m = done(vm)
        assertNull(m.error); assertEquals(MapMove.Stage.DONE, m.stage)
        link.status.value!!.mpos.let { assertEquals(-104.0, it[0], 1e-3); assertEquals(-103.5, it[1], 1e-3); assertEquals("Z went up", -2.0, it[2], 1e-3) }

        // Down to an indicator's height, then Safe Z off: X/Y move, Z stays.
        link.sendLine("G53 G0 Z-55")
        withTimeout(20_000) { while (link.status.value?.let { it.isIdle && it.mpos[2] == -55.0 } != true) delay(30) }
        val next = BedMap.gridPoints(Bed.Z1, MapGrid.G9)[41]
        vm.mapClear()
        vm.mapGo(next, safeZ = false)
        assertEquals("no lift step", MapMove.Stage.MOVE, vm.mapMove.value!!.stage)
        val m2 = done(vm)
        assertNull(m2.error)
        link.status.value!!.mpos.let { assertEquals(next.x, it[0], 1e-3); assertEquals(next.y, it[1], 1e-3); assertEquals("Z stayed", -55.0, it[2], 1e-3) }
        link.disconnect()
    }
}

private class MapFakePlatform : Platform {
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
