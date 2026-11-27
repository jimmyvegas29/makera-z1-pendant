package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.makera.MakeraLink
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * A file that pauses itself with M600 (Jimmy's thread job, 2026-11-24): the Z1 shows "Pause", and
 * the pendant's Resume used to send only `~`, which the firmware ignores then. Resume now sends
 * `resume` in Pause and `~` in a feed hold, and both carry on.
 */
class SuspendResumeSimTest {
    private val port = 22261
    private var sim: Process? = null
    private lateinit var simDir: File

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        simDir = File(System.getProperty("java.io.tmpdir"), "z1sim_sd_${System.nanoTime()}").apply { mkdirs() }
        File(simDir, "pause.cnc").writeText(FILE)
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast", "--sd", simDir.path, "--speed", "4")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_suspend_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @After fun stopSim() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        unmockkStatic(SystemClock::class)
        simDir.deleteRecursively()
    }

    private suspend fun MakeraLink.waitState(s: String, ms: Long = 10_000) =
        withTimeout(ms) { while (status.value?.state != s) delay(20) }

    @Test fun resumeEndsAFilePauseAndAFeedHold() = runBlocking {
        val link = MakeraLink()
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        link.sendLine("\$H")
        link.waitState("Home", 5_000)
        link.waitState("Idle", 30_000)
        val x0 = link.status.value!!.mpos[0]

        link.sendLine("play /sd/gcodes/pause.cnc")
        link.waitState("Pause")
        assertEquals("paused after the first move", x0 - 5, link.status.value!!.mpos[0], 1e-3)

        link.feedHold(); link.waitState("Hold")
        link.resume()                                  // a hold's `~` doesn't end the file's pause
        link.waitState("Pause")
        delay(500)
        assertEquals("still paused", "Pause", link.status.value?.state)

        link.resume()                                  // in Pause: sends `resume`
        link.waitState("Run")
        link.feedHold()
        link.waitState("Hold")
        link.resume()                                  // in Hold: sends `~`
        withTimeout(15_000) {
            while (link.status.value?.let { it.isIdle && kotlin.math.abs(it.mpos[0] - (x0 - 15)) < 1e-3 } != true) delay(20)
        }
        link.disconnect()
    }

    companion object {
        val FILE = """
G91
G0 X-5
M600
G0 X-5
G1 X-5 F60
G90
M30
""".trimStart('\n').replace("\n", "\r\n")
    }
}
