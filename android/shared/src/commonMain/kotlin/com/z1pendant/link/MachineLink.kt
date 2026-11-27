package com.z1pendant.link

import com.z1pendant.util.Fmt
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the UI, jog engine and hotkeys need from a machine. The Makera (stock firmware,
 * TCP 2222) backend implements it today; a LinuxCNC backend (CM4 host) can implement it later
 * without touching the rest of the app.
 */
interface MachineLink {
    val conn: StateFlow<ConnState>
    val status: StateFlow<MachineStatus?>
    /** Text the machine sends that isn't a status reply ("ok", errors, alarms, version). */
    val messages: SharedFlow<String>
    /** What went out, as it was sent: command lines and real-time commands (hold, resume, reset). */
    val sent: SharedFlow<String>
    /** Round-trip time of the last status query, ms. */
    val latencyMs: StateFlow<Int?>

    fun connect(target: MachineTarget)
    fun disconnect()

    /**
     * Queue one command line. Lines go out in order; [onAck] runs when the machine answers it.
     * [quiet] lines (the dial's jog moves, ~25 a second) and their "ok"s stay out of [sent]/[messages].
     */
    fun sendLine(line: String, quiet: Boolean = false, onAck: ((ok: Boolean, reply: String) -> Unit)? = null)
    /** Lines sent but not yet answered. */
    val pendingLines: Int

    fun feedHold()
    /** Carry on after a feed hold, or after a pause the file asked for (M600 on the Z1). */
    fun resume()
    /** Abort: stop motion now, flush queues. Machine needs unlock afterwards. */
    fun reset()

    /** Status poll rate; raised while jogging. */
    fun setPollIntervalMs(ms: Long)

    /** Machine outputs the backend can read back, e.g. "light" -> true. Missing key = unknown. */
    val outputs: StateFlow<Map<String, Boolean>>
    /** Ask the machine for fresh output states now (also refreshed periodically). */
    fun refreshOutputs()
    /** Show an output as [on] right away (after sending its command), until the next read-back. */
    fun assumeOutput(name: String, on: Boolean)

    /** The running job's file, once [queryJobFile] has been answered (null = none / unknown). */
    val jobFile: StateFlow<JobFile?> get() = NoJobFile
    /** Ask which file is playing (answered by the Wi-Fi board, never reaches the motion controller). */
    fun queryJobFile() {}
    /** A file on the machine's SD card as text, or null if it can't be read. */
    suspend fun downloadFile(path: String): String? = null
    /** The same, reporting packets read of the total as it goes. */
    suspend fun downloadFile(path: String, onProgress: (done: Int, total: Int) -> Unit): String? = downloadFile(path)

    // ---- files on the machine's SD card (answered by the Wi-Fi board; nothing moves) ----
    /** A folder's entries, or null if it couldn't be read. A missing folder reads as empty on the Z1. */
    suspend fun listDir(path: String): List<RemoteEntry>? = null
    /**
     * The longest file command line (`mv <from> <to>`, `rm <path>`, …) the machine takes, in bytes. The Z1's
     * Wi-Fi board breaks on longer ones: it acts on leftovers of an earlier command instead (measured
     * 2026-11-23: 128 works, 129 doesn't). The link refuses longer lines; FilesController works round it.
     */
    val fsLineMax: Int get() = Int.MAX_VALUE
    /** The rest return null when done, or the reason it failed. */
    suspend fun makeDir(path: String): String? = "not supported"
    suspend fun rename(from: String, to: String): String? = "not supported"
    /** A folder is deleted with everything inside. */
    suspend fun delete(path: String): String? = "not supported"
    /**
     * Copy [data] to [path], replacing a file of the same name. [onProgress] gets bytes sent and the total,
     * and returns false to cancel. Busy (another app transferring) is retried for up to 15 s.
     */
    suspend fun uploadFile(path: String, data: ByteArray,
                           onProgress: (sent: Long, total: Long) -> Boolean = { _, _ -> true }): String? = "not supported"
    /** The start of a file: read until [enough] says so (or [maxBytes]), then stop the transfer. */
    suspend fun readFileHead(path: String, maxBytes: Int = 65536, enough: (String) -> Boolean = { false }): String? = null

    /** Backend-specific commands, so built-ins don't hard-code one dialect. */
    val dialect: Dialect
}

/** `|P:` while a job plays: current file line (1-based, counting comments), percent of the file read, seconds run. */
data class JobProgress(val line: Long, val percent: Int, val elapsedS: Long)

/** The running job's file on the machine (from a 0xB7 query): path on the SD card and its md5. */
data class JobFile(val path: String, val md5: String) {
    val name get() = path.substringAfterLast('/')
}

private val NoJobFile = kotlinx.coroutines.flow.MutableStateFlow<JobFile?>(null)

/** One entry of a folder on the SD card. [modified] is the Z1's UTC time stamp, "YYYYMMDDhhmmss". */
data class RemoteEntry(val name: String, val isDir: Boolean, val size: Long, val modified: String)

data class MachineTarget(val name: String, val host: String, val port: Int)

/**
 * [Stalled]: connected, but the machine stopped answering status queries. Nothing is sent until it
 * answers again; after a longer silence the link gives up ([Lost]) and reconnects.
 */
enum class ConnState { Disconnected, Connecting, Connected, Stalled, Lost }

data class MachineStatus(
    val state: String,                 // Idle, Run, Hold, Alarm, Home, Pause, Wait, Tool, Sleep
    val mpos: List<Double>,            // X Y Z A (B)
    val wpos: List<Double>,
    val feedOverride: Double?,         // %
    val spindleRpm: Double?,
    /** Homed state if the backend reports one; null on stock Makera firmware (it doesn't). */
    val homed: Boolean?,
    val raw: String,
    val atMs: Long,                    // nowMs() when received
    /** Active tool number from `|T:tool,offset,…|`; null if not reported. */
    val tool: Int? = null,
    /** Programmed spindle RPM (`|S:current,target,override,…|` field 2); the default 10000 while off. */
    val spindleTarget: Double? = null,
    /** Spindle speed override, % (M223). */
    val spindleOverride: Double? = null,
    /** A job file is playing: `|P:line,percent,elapsed s|` (only then; it stays through tool changes and pauses). */
    val job: JobProgress? = null,
    /** Tool the machine is asking for in a tool change (`|T:` field 3); and the active tool's length offset. */
    val toolTarget: Int? = null,
    val tlo: Double? = null,
    /** Feed actually moving at / programmed, mm/min (`|F:actual,set,override|`). */
    val feedActual: Double? = null,
    val feedSet: Double? = null,
    /** The raw `|S:` fields (current, target, override, then the assist modes and temperatures). */
    val sFields: List<String>? = null,
    /** A leveling grid is active: `|O:` (the grid's largest correction, mm). */
    val mesh: Double? = null,
    /** Why the machine halted (`|H:`, only while halted): 13 e-stop, 3 probe fail, … (see haltText). */
    val halt: Int? = null,
) {
    val isIdle get() = state.equals("Idle", ignoreCase = true)
    val isAlarm get() = state.equals("Alarm", ignoreCase = true)
}

interface Dialect {
    fun home(): List<String>
    fun unlock(): List<String>
    fun zeroAxes(axes: String): List<String>       // e.g. "X", "XYZ"
    /** Make the current position read [values] in work coordinates (undo of a zero). */
    fun setWorkPosition(values: Map<Char, Double>): List<String>
    fun feedOverride(percent: Int): List<String>
    fun light(on: Boolean): List<String>
    fun vacuum(on: Boolean): List<String>
    fun spindleOn(rpm: Int): List<String>
    fun spindleOff(): List<String>
    /** "Tool [n] is in the spindle": no motion; the firmware saves it, and marks its length not measured. */
    fun setTool(n: Int): List<String>
    /** Spindle speed override, % of the programmed RPM (two decimals). */
    fun spindleOverride(percent: Double): List<String>
    /**
     * One jog move to machine position [machinePos] on [axis]. Must not change the machine's modal
     * state (no G91), so nothing is left behind if the link drops mid-jog.
     */
    fun jogTo(axis: Char, machinePos: Double, feedMmMin: Double): String
}

object GrblishDialect : Dialect {
    override fun home() = listOf("\$H")
    override fun unlock() = listOf("\$X")
    override fun zeroAxes(axes: String) = listOf("G10L20P0" + axes.map { "${it}0" }.joinToString(""))
    override fun setWorkPosition(values: Map<Char, Double>) =
        listOf("G10L20P0" + values.entries.joinToString("") { (a, v) -> a + fmt(v) })
    override fun feedOverride(percent: Int) = listOf("M220S$percent")
    // Z1 config: switch.light.input_on_command M821 / off M822 (confirmed on the machine 2026-11-13).
    override fun light(on: Boolean) = listOf(if (on) "M821" else "M822")
    // Z1 config: switch.extendout (P2.2, the vacuum / chip-clear output) on M851 / off M852.
    override fun vacuum(on: Boolean) = listOf(if (on) "M851" else "M852")
    override fun spindleOn(rpm: Int) = listOf("M3 S$rpm")
    override fun spindleOff() = listOf("M5")
    // M493.2 T = set the current tool (Makera's app sends it too; community ATCHandler saves it to EEPROM).
    override fun setTool(n: Int) = listOf("M493.2 T$n")
    override fun spindleOverride(percent: Double) = listOf("M223 S${Fmt.trimmed2(percent)}")
    // G53 = machine coordinates for this line only; G90/G91 and the work offset stay as they are.
    override fun jogTo(axis: Char, machinePos: Double, feedMmMin: Double) =
        "G53G1$axis${fmt(machinePos)}F${Fmt.fixed(feedMmMin, 0)}"

    fun fmt(v: Double) = Fmt.trimmed4(v)
}
