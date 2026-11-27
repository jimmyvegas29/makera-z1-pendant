package com.z1pendant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.z1pendant.hotkeys.BuiltIn
import com.z1pendant.hotkeys.BuiltinHost
import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.hotkeys.HotkeyJson
import com.z1pendant.hotkeys.HotkeyRunner
import com.z1pendant.hotkeys.Trigger
import com.z1pendant.jog.JogEngine
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineName
import com.z1pendant.link.MachineStatus
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.SoftLimits
import com.z1pendant.link.makera.Discovery
import com.z1pendant.link.makera.MakeraLink
import com.z1pendant.platform.PLog
import com.z1pendant.platform.Platform
import com.z1pendant.platform.nowMs
import com.z1pendant.platform.wallClockHms
import com.z1pendant.temp.boxTemp
import com.z1pendant.temp.spindleTemp
import com.z1pendant.util.Fmt
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The app's state and actions, shared by Android and iOS. [platform] supplies preferences
 * (Android: SharedPreferences "z1pendant"), the hotkeys.json file, haptics and the discovery lock.
 */
class AppViewModel(private val platform: Platform) : ViewModel() {
    private val prefs = platform.prefs
    val haptics: Haptics = platform.haptics
    val link = MakeraLink()

    // ---- UI state -------------------------------------------------------------------------
    /** Dial mode steps, and jog-pad mode steps (single presses, so bigger ones are useful). */
    val dialSteps = listOf(0.005, 0.01, 0.1, 1.0, 5.0)
    val padSteps = listOf(0.01, 0.1, 1.0, 5.0, 10.0, 25.0)
    /** Jog pad (−X/+X/−Y/+Y, Z and A buttons) shown instead of the dial. */
    val jogPad = MutableStateFlow(prefs.getBoolean("jogPad", false))
    /** The step list of the current mode; each mode remembers its own selected step. */
    val steps: List<Double> get() = if (jogPad.value) padSteps else dialSteps
    private fun stepKey() = if (jogPad.value) "padStepIndex" else "stepIndex"
    val axes = listOf('X', 'Y', 'Z', 'A')
    val axis = MutableStateFlow(prefs.getString("axis", "X")!!.first())
    val stepIndex = MutableStateFlow(prefs.getInt(stepKey(), 2).coerceIn(0, steps.lastIndex))
    val target = MutableStateFlow(loadTarget())
    val discovered = MutableStateFlow<List<Pair<MachineTarget, Boolean>>>(emptyList())
    val scanning = MutableStateFlow(false)
    val snack = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Files on the Z1's SD card (the Files screen). */
    val files = com.z1pendant.files.FilesController(link, viewModelScope, prefs) { snack.tryEmit(it) }
    val console = MutableStateFlow<List<String>>(emptyList())
    /** App updates from GitHub (Settings › App, and the dialog at start). */
    val updates = com.z1pendant.update.UpdateController(prefs, platform.updater, viewModelScope, log = { log(it) }, busy = {
        val st = link.status.value
        when {
            st?.job != null -> "A job is running. Update after it finishes: installing closes the app."
            st != null && !st.isIdle && !st.isAlarm -> "The machine is moving. Update when it's idle: installing closes the app."
            else -> null
        }
    })
    val confirmFor = MutableStateFlow<Hotkey?>(null)

    // ---- spindle ----
    /** The spindle start box is open (the Spindle tile or a bound button, while it's off). */
    val spindleAsk = MutableStateFlow(false)
    /** The stop box is open: stopping always asks too (a job may be using the spindle; we can't tell). */
    val spindleStopAsk = MutableStateFlow(false)
    /** RPM last started with; the start box opens holding it. */
    val spindleRpm = MutableStateFlow(prefs.getInt("spindleRpm", 10_000).coerceIn(1, SPINDLE_MAX_RPM))
    /** nowMs() of the last M3 we sent (0 = none since the last M5): shows the tile as on until the RPM reads back. */
    @Volatile private var spindleStartAt = 0L

    /** Running = the machine reports RPM, or we just started it and it's spinning up. */
    fun spindleRunning(st: MachineStatus?): Boolean =
        (st?.spindleRpm ?: 0.0) > 0.5 || (spindleStartAt > 0 && nowMs() - spindleStartAt < SPINDLE_PENDING_MS)

    /** Why a job can't be set up now (the Run screen only opens on an Idle machine), or null. */
    fun runBlock(): String? {
        val st = link.status.value
        return when {
            link.conn.value != ConnState.Connected -> "Run needs the Z1 connected."
            st == null -> "No status from the Z1 yet."
            st.job != null -> "A job is running."
            !st.isIdle -> "Run needs the Z1 Idle (it's ${st.state})."
            else -> null
        }
    }

    /** Why the spindle can't start at [rpm] now, or null. Checked before anything is sent. */
    fun spindleBlock(rpm: Int?): String? {
        val st = link.status.value
        val tool = st?.tool
        return when {
            link.conn.value != ConnState.Connected -> "Not connected"
            st == null -> "No machine status"
            !st.isIdle -> "Machine is ${st.state}, not Idle"
            tool != null && isProbeTool(tool) -> "The probe is loaded (T$tool). Load a cutting tool first."
            tool != null && tool < 0 -> "No tool loaded (empty collet). Load a tool first."
            // Anything else the stock Z1 firmware won't spin: M3 halts unless 1 <= T <= 999
            // (Makera's makeraz1firmware SpindleControl; the community firmware allows < 100000).
            tool != null && tool >= 1_000 -> "Tool T$tool can't run the spindle. Load a cutting tool first."
            rpm == null || rpm < 1 -> "Enter an RPM"
            rpm > SPINDLE_MAX_RPM -> "The Z1 spindle tops out at ${Fmt.thousands(SPINDLE_MAX_RPM)} RPM"
            else -> null
        }
    }

    /** Start box → M3. */
    fun startSpindle(rpm: Int) {
        spindleBlock(rpm)?.let { snack.tryEmit(it); haptics.thud(); return }
        haptics.press()
        spindleRpm.value = rpm; prefs.edit { putInt("spindleRpm", rpm) }
        spindleStartAt = nowMs()
        ownMotion.sent(nowMs())               // the firmware reads Run while the spindle turns: that's ours
        log("▶ spindle on, $rpm RPM")
        link.dialect.spindleOn(rpm).forEach { link.sendLine(it) }
        spindleAsk.value = false
    }

    /**
     * Spindle override slider (drawer), in RPM. null = slider off (not connected, spindle not
     * running, or no programmed RPM reported).
     */
    fun spindleSlide(st: MachineStatus?): SpindleSlide? {
        if (link.conn.value != ConnState.Connected || st == null || !spindleRunning(st)) return null
        val target = st.spindleTarget?.takeIf { it > 0 } ?: return null
        return spindleSlideFor(target, st.spindleOverride ?: 100.0)
    }

    /** Release on the slider → M223 S<the % of the programmed RPM that gives [rpm]>. */
    fun setSpindleRpm(rpm: Int) {
        val slide = spindleSlide(link.status.value) ?: return
        log("▶ spindle override, ${Fmt.thousands(slide.clamp(rpm))} RPM")
        link.dialect.spindleOverride(slide.percentFor(rpm)).forEach { link.sendLine(it) }
    }
    /** − / + (and the bar, via [setSpindleRpm]) → M223 S<percent>. */
    fun setSpindlePercent(p: Double) {
        val slide = spindleSlide(link.status.value) ?: return
        log("▶ spindle override ${Fmt.trimmed2(p)} % (${Fmt.thousands(kotlin.math.round(slide.target * p / 100).toInt())} RPM)")
        link.dialect.spindleOverride(p).forEach { link.sendLine(it) }
    }
    fun resetSpindleOverride() = link.dialect.spindleOverride(100.0).forEach { link.sendLine(it) }

    /** The tool the start box's wheel offers first: the last one set from it (T1 the first time). */
    val lastSetTool = MutableStateFlow(prefs.getInt("lastSetTool", 1).coerceIn(1, 99))

    /** The firmware won't spin with the tool it has (probe, empty collet, or out of range). */
    fun spindleToolProblem(st: MachineStatus?): Boolean =
        st?.tool?.let { isProbeTool(it) || it < 0 || it >= 1_000 } == true

    /** Start box → M493.2 T[n] (no motion). */
    fun setTool(n: Int) {
        haptics.press()
        lastSetTool.value = n; prefs.edit { putInt("lastSetTool", n) }
        log("▶ set tool T$n")
        link.dialect.setTool(n).forEach { link.sendLine(it) }
    }

    // ---- job ----
    /** A job file playing on the machine (from `|P:`), or null. */
    val job = MutableStateFlow<JobView?>(null)
    /** Tools of the running job's file (read from the file once per md5), or null while unknown. */
    val jobTools = MutableStateFlow<JobTools?>(null)
    private val toolCache = HashMap<String, JobTools>()
    private var toolsFor: String? = null

    private var noJobCount = 0
    /** A job we were watching when the link dropped: if it's gone when we're back, it ended meanwhile. */
    private var watchedBeforeDrop = false

    private val jobPace = JobPace()
    /**
     * A new job was seen (not a reconnect to one already watched): MainScreen opens the drawer fully
     * expanded on the job, once, and clears this. The user can drag it down; it stays down.
     */
    val jobStarted = MutableStateFlow(false)

    // ---- temperatures ----
    private val tempTrace = com.z1pendant.temp.TempTrace()
    /** The spindle and box temperatures over the current (or last) job, for the job panel's graph. */
    val temps = MutableStateFlow<com.z1pendant.temp.TempView?>(null)
    /** The Z1's limits (it halts above them): the SD config's, else the built-in config's. */
    val tempLimits = MutableStateFlow(com.z1pendant.temp.TempLimits.DEFAULT)
    /**
     * The machine's soft limits, read on connect (M211, else the config's mins; see SoftLimits), so a
     * Carvera Air isn't held to a Z1's travel. The jog clamp, typed moves and Run's bed use them.
     */
    val softLimits = MutableStateFlow(SoftLimits.Z1)
    private var softLimitsJob: Job? = null
    /** During a job, the reading that's within 5 °C of its limit (banner, buzz, pulsing line). */
    val tempWarn = MutableStateFlow<com.z1pendant.temp.TempWarn?>(null)
    /** The last status before the machine went into Alarm: the temperatures and spindle at that moment. */
    val beforeAlarm = MutableStateFlow<MachineStatus?>(null)
    /** Why it halted, from the Z1's own ERROR line (9 spindle overheated, 14 box overheated, 41 spindle driver). */
    val haltFromMessage = MutableStateFlow<Int?>(null)
    /** Where in the temperature trace the machine went into Alarm (seconds), for the alarm sheet's graph. */
    val alarmAtS = MutableStateFlow<Double?>(null)
    /** The temperature in the Z1's "… overheated, max - 75.0, current - 75.1" line. */
    val haltTemp = MutableStateFlow<Double?>(null)
    private val overheatRe = Regex("""current\s*-\s*([-\d.]+)""")

    /** Once per status: record the job's temperatures, and warn near a limit. Runs after [trackJob]. */
    private fun trackTemps(st: MachineStatus?) {
        if (st == null) return
        val now = nowMs()
        // The trace keeps running after the job only through an Alarm (the overheat sheet shows it cooling).
        if (tempTrace.active && job.value == null && !st.isAlarm) tempTrace.stop()
        if (tempTrace.onStatus(now, st)) temps.value = tempTrace.view()
        val w = if (job.value != null) com.z1pendant.temp.TempTrace.warn(st.spindleTemp, st.boxTemp, tempLimits.value, tempWarn.value) else null
        if (w != null && tempWarn.value != w) {
            haptics.thud()
            log("· ${if (w == com.z1pendant.temp.TempWarn.SPINDLE) "spindle ${st.spindleTemp}" else "box ${st.boxTemp}"} °C: close to the Z1's limit")
        }
        tempWarn.value = w
    }

    /** The limits from the SD config; the built-in values where the SD config has none. */
    private suspend fun readTempLimits() {
        val d = com.z1pendant.temp.TempLimits.DEFAULT
        val s = run.readConfig("temperature_control.spindle.max_temp")
        val b = run.readConfig("temperature_control.power.max_temp")
        tempLimits.value = com.z1pendant.temp.TempLimits(s ?: d.spindle, b ?: d.box)
        log("· temperature limits: spindle ${tempLimits.value.spindle} °C" + (if (s == null) " (built-in)" else "") +
            ", box ${tempLimits.value.box} °C" + (if (b == null) " (built-in)" else ""))
    }

    /** Link lost: forget the job (it may end, or another may start, before we're back). */
    private fun forgetJob() {
        if (job.value != null) watchedBeforeDrop = true
        job.value = null; jobTools.value = null; toolsFor = null; noJobCount = 0
    }

    private fun trackJob(st: MachineStatus?) {
        // The Wi-Fi board's canned fallback status (P:1234,50,1200) says nothing about the job.
        if (st != null && "|P:1234,50,1200" in st.raw) return
        val p = st?.job
        if (p == null) {
            // Over only once |P: has been gone for 3 statuses in a row (not one dropped reply),
            // whether we saw it end or it ended while we were away.
            if ((job.value != null || watchedBeforeDrop) && st != null && ++noJobCount >= 3) {
                job.value = null; watchedBeforeDrop = false; jobStarted.value = false; jobEnded(st)
            }
            return
        }
        noJobCount = 0
        val rejoined = watchedBeforeDrop
        watchedBeforeDrop = false                                  // back into a running job: leave its overrides alone
        if (job.value == null) {                                   // a job started (or we joined one): which file?
            link.queryJobFile(); jobPace.reset()
            if (!rejoined) jobStarted.value = true                 // the drawer opens expanded once (MainScreen)
            // A new job's temperatures start over; back into the same one after a dropped link, they carry on.
            if (!rejoined || temps.value == null) { tempTrace.reset(nowMs()); temps.value = tempTrace.view() } else tempTrace.resume()
        }
        val f = link.jobFile.value
        val tools = jobTools.value?.takeIf { f != null && f.md5 == toolsFor }
        tools?.time?.let { jobPace.update(p.line, p.elapsedS, it, st?.feedOverride) }
        job.value = JobView(p, f, tools?.lines, tools?.time, jobPace.value, st?.feedOverride)
        // Once per file (md5): read its tools.
        if (f != null && f.md5 != toolsFor) {
            toolsFor = f.md5
            jobTools.value = toolCache[f.md5]
            if (jobTools.value == null) viewModelScope.launch {
                val text = link.downloadFile(f.path) ?: return@launch
                val t = JobFileParser.parse(text)
                toolCache[f.md5] = t
                if (toolsFor == f.md5) jobTools.value = t
            }
        }
    }

    /**
     * A job finished (or was stopped): put feed and spindle overrides back to 100 %, so an override
     * set during this job (or left over from before) doesn't carry into the next one. The firmware
     * keeps them until changed.
     */
    private fun jobEnded(st: MachineStatus) {
        val d = link.dialect
        val fro = st.feedOverride; val sro = st.spindleOverride
        if (fro != null && kotlin.math.abs(fro - 100) > 0.05) { log("▶ job ended: feed override back to 100 %"); d.feedOverride(100).forEach { link.sendLine(it) } }
        if (sro != null && kotlin.math.abs(sro - 100) > 0.05) { log("▶ job ended: spindle override back to 100 %"); d.spindleOverride(100.0).forEach { link.sendLine(it) } }
        // The job's leveling grid must not outlive it: the firmware applies a grid to every move
        // (jogs too) until it's cleared. Cancel job clears it itself (after the alarm is cleared).
        if (st.mesh != null && !stopping && !st.isAlarm) { log("▶ job ended: leveling grid cleared (M370)"); link.sendLine("M370") }
    }

    /**
     * A leveling grid was found active on connect, with no job running: its largest correction (mm).
     * The popup asks whether to clear it. Kept → not asked again until the next connect.
     */
    val meshPrompt = MutableStateFlow<Double?>(null)
    fun keepMesh() { meshPrompt.value = null; log("· leveling grid kept") }
    fun clearMesh() { meshPrompt.value = null; log("▶ leveling grid cleared (M370)"); link.sendLine("M370") }

    /**
     * After connecting: three statuses in (so a job's |P: is reliably seen), Idle, no job, a grid → read
     * the grid itself (`M375.1`) and ask only if it holds values. Right after power-on the Z1 can report
     * `|O:` with an empty grid (every point "nan", seen 2026-11-22), which corrects nothing. If the grid
     * can't be read, ask anyway with the status's figure.
     */
    private suspend fun checkMeshOnConnect() {
        val seen = mutableListOf<MachineStatus>()
        kotlinx.coroutines.withTimeoutOrNull(5_000) {
            link.status.first { st -> if (st != null && seen.lastOrNull()?.atMs != st.atMs) seen += st; seen.size >= 3 }
        }
        val st = seen.lastOrNull() ?: return
        if (seen.any { it.job != null } || !st.isIdle) return
        val reported = st.mesh ?: return
        val grid = run.readMesh()
        val largest = if (grid != null) com.z1pendant.run.Mesh.largest(grid) else reported
        if (largest == null) { log("· machine reports a leveling grid, but it's empty (no points): not asking"); return }
        if (link.status.value?.job != null) return               // a job started meanwhile
        meshPrompt.value = largest
        log("· leveling grid active on connect (largest correction ${com.z1pendant.util.Fmt.fixed(largest, 3)} mm)")
    }

    /** Stop box → M5. */
    fun stopSpindle() {
        haptics.press()
        spindleStartAt = 0L
        log("▶ spindle off")
        link.dialect.spindleOff().forEach { link.sendLine(it) }
        spindleStopAsk.value = false
    }

    /** Both ways ask: off → the start box, running → the stop box. */
    private fun spindleToggle(): List<String>? {
        if (spindleRunning(link.status.value)) spindleStopAsk.value = true else spindleAsk.value = true
        return null
    }
    /** Bound buttons and volume-jog only act while the main screen is showing. */
    @Volatile var onMainScreen = true
    val editMode = MutableStateFlow(false)
    /** Hotkey grid expanded (dial hidden, every hotkey shown). */
    val hotkeysExpanded = MutableStateFlow(false)
    /** Bottom drawer with feed override + Hold/Resume/Reset pulled up. */
    val controlsOpen = MutableStateFlow(false)
    /** The pendant's own motion, which doesn't open the drawer by itself. */
    val ownMotion = OwnMotion()
    /** Whether the machine's current motion is the pendant's own; re-checked on every status (see init). */
    val ownMotionActive = MutableStateFlow(false)
    /** While arranging: the working order (ids). Saved by [saveArrangement]. */
    val arrangeOrder = MutableStateFlow<List<String>?>(null)

    // settings
    val hapticStrength = MutableStateFlow(prefs.getFloat("haptic", 0.7f))
    val volumeJog = MutableStateFlow(prefs.getBoolean("volumeJog", true))
    val keepScreenOn = MutableStateFlow(prefs.getBoolean("keepScreenOn", true))
    val tickMs = MutableStateFlow(prefs.getLong("tickMs", 40))
    val degPerClick = MutableStateFlow(prefs.getFloat("degPerClick", 12f))
    val softClamp = MutableStateFlow(prefs.getBoolean("softClamp", true))
    /** The readout's zero buttons need a 1.5 s hold instead of a tap (off by default). */
    val zeroHold = MutableStateFlow(prefs.getBoolean("zeroHold", false))
    /** Hide the machine's serial number wherever its name is shown (screen recordings). */
    val hideSerial = MutableStateFlow(prefs.getBoolean("hideSerial", false))

    // hotkeys
    private val hotkeyFile = platform.hotkeyFile
    val hotkeys = MutableStateFlow(loadHotkeys())
    /** Non-null while the binding dialog waits for a button press. */
    val capture = MutableStateFlow<((Trigger) -> Unit)?>(null)

    val jog = JogEngine(link, viewModelScope, onDrop = { haptics.thud() }, onLimit = { haptics.limit() },
        onJogDone = { moved ->
            moved.forEach { (a, d) ->
                log("↕ jog $a ${Fmt.fixed(d, 3, plus = true)} ${if (a == 'A') "°" else "mm"} (${if (jogPad.value) "pad" else "dial"})")
            }
        })

    private val host = object : BuiltinHost {
        override fun runBuiltin(b: BuiltIn): List<String>? = builtin(b)
        override fun beforeGcode() { /* nothing to restore: jogs never change the modal state (G53 moves) */ }
        override fun setJogEnabled(on: Boolean) { jog.enabled = on; if (!on) jog.cancelPending() }
    }
    val runner = HotkeyRunner(link, viewModelScope, host) { id -> hotkeys.value.firstOrNull { it.id == id } }

    /** 3D probing: setup, probe in / calibrated this session, and runs (Makera's routine or Measure only). */
    val probe = com.z1pendant.probe.ProbeController(link, viewModelScope, prefs,
        onSend = { ownMotion.sent(nowMs()) },
        setJogEnabled = { on -> jog.enabled = on; if (!on) jog.cancelPending() })

    /** Running a job: origin, laser probe checks, assist modes, start (the Run flow). */
    val run = com.z1pendant.run.RunController(link, viewModelScope, prefs, probe,
        onSend = { ownMotion.sent(nowMs()) }, log = { log(it) },
        bed = { softLimits.value.bed ?: com.z1pendant.link.Bed.Z1 })
    /** Custom anchors (Settings › Anchors; Run's Custom anchor origin), saved per machine. */
    val anchors = com.z1pendant.run.AnchorsController(link, viewModelScope, prefs, target, log = { log(it) })
    /** Files is open to pick a job to run (the Run tile). */
    val runPick = MutableStateFlow(false)

    init {
        haptics.strength = hapticStrength.value
        jog.axis = axis.value
        jog.step = steps[stepIndex.value]
        applyJogConfig()
        jog.start()
        updates.startupCheck()
        viewModelScope.launch {
            link.messages.collect { m ->
                log("← $m")
                if (m.trimStart().startsWith("ALARM", ignoreCase = true)) lastAlarm.value = m.trim().substringAfter(':').trim().ifEmpty { null }
                // The Z1's own words for a halt (seen 2026-11-22), in case the status has no |H: code.
                when {
                    "Spindle overheated" in m -> { haltFromMessage.value = 9; haltTemp.value = overheatRe.find(m)?.groupValues?.get(1)?.toDoubleOrNull() }
                    "Power cabinet overheated" in m -> { haltFromMessage.value = 14; haltTemp.value = overheatRe.find(m)?.groupValues?.get(1)?.toDoubleOrNull() }
                    "Spindle alarm triggered" in m -> haltFromMessage.value = 41
                }
                if (m.startsWith("Done ATC")) tempTrace.onToolChangeDone(nowMs())
            }
        }
        // The alarm sheet opens by itself when the machine goes into Alarm (or is in it when the app
        // connects) while the main screen is up, except after our own Stop, which clears it anyway.
        viewModelScope.launch {
            var was = false
            var prev: MachineStatus? = null
            link.status.collect { st ->
                val alarm = st?.isAlarm == true
                if (alarm && !was) {
                    beforeAlarm.value = prev
                    alarmAtS.value = if (temps.value != null) tempTrace.secondsAt(nowMs()) else null
                }
                if (alarm && !was && !stopping && onMainScreen) alarmSheet.value = true
                if (!alarm && was) { lastAlarm.value = null; alarmSheet.value = false; haltFromMessage.value = null }
                was = alarm
                if (st != null) prev = st
            }
        }
        viewModelScope.launch { link.sent.collect { m -> log("→ $m") } }
        // Own motion is tracked here, once per status, rather than while the screen redraws.
        viewModelScope.launch {
            link.status.collect { st ->
                trackJob(st)
                trackTemps(st)
                ownMotionActive.value = ownMotion.isOwn(st?.state, link.conn.value == ConnState.Connected, nowMs(),
                    hotkeyRunning = runner.running.value != null, lastJogAt = jog.lastJogAt)
            }
        }
        viewModelScope.launch { runner.lastError.collect { it?.let { e -> snack.tryEmit(e); runner.lastError.value = null } } }
        viewModelScope.launch {
            var prev: ConnState? = null
            link.conn.collectLatest { c ->
                log("· link $c")
                // A stall that clears is still the same connection: keep the job, and don't ask about
                // the mesh again (that's for a fresh connection).
                val resumed = c == ConnState.Connected && prev == ConnState.Stalled
                prev = c
                if (c != ConnState.Connected) jog.cancelPending()
                if (c != ConnState.Connected && c != ConnState.Stalled) softLimitsJob?.cancel()
                if (c != ConnState.Connected && c != ConnState.Stalled) { forgetJob(); meshPrompt.value = null }
                if (c == ConnState.Connected) {
                    if (!resumed) {
                        launch { checkMeshOnConnect(); readTempLimits() }
                        softLimitsJob?.cancel(); softLimitsJob = launch { readSoftLimits() }
                    }
                    lookUpName()
                }
            }
        }
        target.value?.let { if (prefs.getBoolean("autoConnect", true)) link.connect(it) }
    }

    private fun applyJogConfig() {
        val sl = softLimits.value
        val limits = jog.config.limits.mapValues { (a, l) ->
            if (a in "XYZ") l.copy(softMin = sl.min[a], softMax = sl.max[a]) else l
        }
        jog.config = jog.config.copy(tickMs = tickMs.value, softLimitClamp = softClamp.value && sl.enabled, limits = limits)
    }

    /**
     * Asks the machine its soft limits once it's Idle (the firmware turns G-code away while it's
     * alarmed, and a job may be running when we connect). M211 first; no report, then the config's
     * mins; nothing at all, the Z1's.
     */
    private suspend fun readSoftLimits() {
        link.status.first { it != null && it.job == null && it.isIdle }
        // The report comes as several lines (see SoftLimits): collect up to the "ok". If the "ok"
        // never shows, use what did come in.
        val report = SoftLimits.Report()
        kotlinx.coroutines.withTimeoutOrNull(3000) {
            kotlinx.coroutines.coroutineScope {
                val answer = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    link.messages.first { report.add(it) }
                }
                link.sendLine(SoftLimits.COMMAND)
                answer.await()
            }
        }
        val got = report.result() ?: SoftLimits.fromConfig(
            run.readConfig("soft_endstop.x_min"), run.readConfig("soft_endstop.y_min"),
            run.readConfig("soft_endstop.z_min"), null,
        )
        softLimits.value = got
        applyJogConfig()
        log("· soft limits: ${got.describe()}")
    }

    // ---- connection -----------------------------------------------------------------------
    private fun loadTarget(): MachineTarget? {
        val h = prefs.getString("host", null) ?: return null
        return MachineTarget(prefs.getString("name", h)!!, h, prefs.getInt("port", 2222))
    }

    fun connect(t: MachineTarget) {
        prefs.edit { putString("host", t.host); putInt("port", t.port); putString("name", t.name)
            putBoolean("autoConnect", true) }
        target.value = t
        link.connect(t)
    }

    /**
     * Reached by address: ask the machine its name (see MachineName) so the header shows it, as after
     * a scan. Kept with the target, so the next start shows it straight away. No answer: the address
     * stays as the name.
     */
    private suspend fun lookUpName() {
        val t = target.value ?: return
        if (!MachineName.needsLookup(t)) return
        val name = kotlinx.coroutines.withTimeoutOrNull(4000) {
            kotlinx.coroutines.coroutineScope {
                val answer = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    link.messages.first { MachineName.parse(it) != null }
                }
                link.sendLine(MachineName.COMMAND)
                MachineName.parse(answer.await())
            }
        } ?: return
        if (target.value?.host != t.host) return
        log("· machine name $name")
        prefs.edit { putString("name", name) }
        target.value = t.copy(name = name)
    }

    fun disconnect() {
        prefs.edit { putBoolean("autoConnect", false) }
        link.disconnect()
    }

    private var scanJob: Job? = null
    fun scan() {
        scanJob?.cancel()
        discovered.value = emptyList()
        scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                Discovery.scan(3500, platform::holdDiscoveryLock) { t, busy -> discovered.value = discovered.value + (t to busy) }
            } catch (e: Exception) {
                snack.tryEmit("Scan failed: ${e.message}")
            } finally { scanning.value = false }
        }
    }

    // ---- jog --------------------------------------------------------------------------------
    fun selectAxis(a: Char) { axis.value = a; jog.axis = a; prefs.edit { putString("axis", a.toString()) } }
    fun selectStep(i: Int) {
        val j = i.coerceIn(0, steps.lastIndex)
        stepIndex.value = j; jog.step = steps[j]; prefs.edit { putInt(stepKey(), j) }
    }

    // ---- jog pad ------------------------------------------------------------------------------
    fun setJogPad(on: Boolean) {
        if (jogPad.value == on) return
        jog.cancelPending()
        jogPad.value = on
        prefs.edit { putBoolean("jogPad", on) }
        val j = prefs.getInt(stepKey(), 2).coerceIn(0, steps.lastIndex)
        stepIndex.value = j; jog.step = steps[j]
        if (!on) jog.axis = axis.value            // the dial jogs the selected axis again
        haptics.press()
    }

    /**
     * One pad press (or one repeat while held): one step on [a] in direction [dir]. The pressed
     * axis also becomes the selected one, so going back to the dial (or the volume keys) carries
     * on with the axis you were just jogging, not an older selection.
     */
    fun padJog(a: Char, dir: Int) {
        if (axis.value != a || jog.axis != a) selectAxis(a)
        jog.step = steps[stepIndex.value]
        wheelClicks(dir)
    }

    /**
     * While a pad button is held it repeats. The repeat rate keeps the speed at or below the
     * axis's max rate (step / max speed), and no faster than every 120 ms, so the jog engine
     * never has to throw clicks away.
     */
    fun padRepeatMs(a: Char): Long {
        val vmax = (jog.config.limits[a]?.maxRateMmMin ?: 1000.0) / 60.0          // mm/s (°/s for A)
        return maxOf(120L, (steps[stepIndex.value] / vmax * 1000.0).toLong())
    }

    /** A held pad button starts repeating: its speed is known exactly, one step per [padRepeatMs]. */
    fun padHoldStarted(a: Char) { jog.holdRate = 1000.0 / padRepeatMs(a) }

    /** Button let go after it had started repeating: drop clicks not yet sent, so it stops. */
    fun padReleased() { jog.holdRate = null; jog.cancelPending() }
    private var detents = 0
    /** One handwheel detent: tick, with a firmer click every 10th. */
    fun wheelClicks(n: Int) {
        jog.click(n)
        detents += 1
        if (detents % 10 == 0) haptics.majorTick() else haptics.tick()
    }

    /** Test hook: [count] jog-pad taps on [a] in direction [dir], [gapMs] apart, at pad step [stepIdx]. */
    fun testPadTaps(a: Char, dir: Int, count: Int, gapMs: Long, stepIdx: Int) {
        viewModelScope.launch {
            setJogPad(true); selectStep(stepIdx)
            PLog.i("JogTest", "pad taps $a dir=$dir x$count gap=${gapMs}ms step=${steps[stepIndex.value]}")
            repeat(count) { k -> if (k > 0) kotlinx.coroutines.delay(gapMs); padJog(a, dir) }
        }
    }

    /** Test hook: [n] clicks spread evenly over [ms] (sign = direction). */
    fun viewModelScopeLaunchBurst(n: Int, ms: Long) {
        viewModelScope.launch {
            val count = kotlin.math.abs(n); val dir = if (n < 0) -1 else 1
            val start = nowMs()
            PLog.i("JogTest", "burst start n=$n ms=$ms step=${steps[stepIndex.value]}")
            for (k in 1..count) {
                val due = start + ms * k / count
                val wait = due - nowMs()
                if (wait > 0) kotlinx.coroutines.delay(wait)
                wheelClicks(dir)
            }
            PLog.i("JogTest", "burst end")
        }
    }

    fun sendConsole(line: String) {
        if (line.isBlank()) return
        ownMotion.sent(nowMs())             // typed by you: any motion it starts is the pendant's own
        link.sendLine(line.trim())          // the link logs it when it actually goes out
    }

    /** Console line with a time stamp. Everything sent (except dial jogs) and every reply. */
    private fun log(s: String) {
        val line = wallClockHms() + " " + s
        console.update { (it + line).takeLast(500) }
    }

    fun zero(axes: String) = zeroLines(axes).forEach { link.sendLine(it) }

    // ---- zero / undo (like a DRO's zero + recall) ---------------------------------------------
    /** Work value an axis had before it was zeroed, and the machine position it was zeroed at. */
    data class ZeroUndo(val prevWork: Double, val machineAt: Double)
    val zeroUndo = MutableStateFlow<Map<Char, ZeroUndo>>(emptyMap())

    /** Undo is offered while the axis still reads 0.000 and hasn't moved since it was zeroed. */
    fun canRestoreZero(a: Char, st: MachineStatus?): Boolean {
        val u = zeroUndo.value[a] ?: return false
        val i = axes.indexOf(a)
        val w = st?.wpos?.getOrNull(i) ?: return false
        val m = st.mpos.getOrNull(i) ?: return false
        return kotlin.math.abs(w) < 0.0005 && kotlin.math.abs(m - u.machineAt) < 0.0015
    }

    /**
     * Zero [which] (e.g. "XY"), or, if every one of them can be restored (pressed again with nothing moved),
     * put their previous work values back with G10 L20. Used by the zero buttons and the Zero
     * hotkeys alike.
     */
    private fun zeroLines(which: String): List<String> {
        val st = link.status.value
        val list = which.toList()
        if (list.isNotEmpty() && list.all { canRestoreZero(it, st) }) {
            val prev = list.associateWith { zeroUndo.value.getValue(it).prevWork }
            zeroUndo.value = zeroUndo.value - list.toSet()
            log("↺ restore " + prev.entries.joinToString(" ") { (a, v) -> "$a ${Fmt.fixed(v, 3)}" })
            return link.dialect.setWorkPosition(prev)
        }
        val m = zeroUndo.value.toMutableMap()
        for (a in list) {
            val i = axes.indexOf(a)          // the X/Y/Z/A list, not [which]
            val w = st?.wpos?.getOrNull(i); val mp = st?.mpos?.getOrNull(i)
            if (w != null && mp != null) m[a] = ZeroUndo(w, mp) else m.remove(a)
        }
        zeroUndo.value = m
        return link.dialect.zeroAxes(which)
    }

    // ---- typed move (long-press a readout row) ------------------------------------------------
    /** The lines a typed move would send, or why it can't run. */
    data class MovePlan(val lines: List<String>, val error: String?, val endWork: Double? = null)

    /**
     * [relative] false = go to work position [value]; true = move by [value]. Rapid (G0).
     * Refuses targets past the soft limits (the stock firmware halts on those) when soft-limit
     * clamping is on, the same switch the dial uses.
     */
    fun planMove(axis: Char, value: Double, relative: Boolean): MovePlan {
        val st = link.status.value ?: return MovePlan(emptyList(), "No status from the machine yet")
        val i = axes.indexOf(axis)
        val m = st.mpos.getOrNull(i); val w = st.wpos.getOrNull(i)
        if (m == null || w == null) return MovePlan(emptyList(), "No $axis position from the machine")
        val endM = if (relative) m + value else value + (m - w)
        val endW = endM - (m - w)
        val lim = jog.config.limits[axis]
        if (softClamp.value && softLimits.value.enabled && st.homed != false && lim != null) {
            val lo = lim.softMin; val hi = lim.softMax
            if ((lo != null && endM < lo - 1e-6) || (hi != null && endM > hi + 1e-6)) {
                fun f(d: Double?) = d?.let { Fmt.fixed(it, 3) } ?: "…"
                return MovePlan(emptyList(), "Past the soft limit: would end at machine $axis ${f(endM)} " +
                    "(allowed ${f(lo)} to ${f(hi)})", endW)
            }
        }
        return MovePlan(moveLines(axis, value, relative, endM), null, endW)
    }

    fun runMove(axis: Char, value: Double, relative: Boolean) {
        val st = link.status.value
        val why = when {
            link.conn.value != ConnState.Connected -> "not connected"
            st == null || !st.isIdle -> "machine is ${st?.state ?: "unknown"}, not Idle"
            // Move by is computed from the reported position: it must be a current one.
            relative && nowMs() - st.atMs > FRESH_STATUS_MS -> "no fresh position from the machine"
            runner.running.value != null -> "“${runner.running.value!!.label}” is still running"
            else -> planMove(axis, value, relative).error
        }
        if (why != null) { snack.tryEmit("Move $axis: $why"); haptics.thud(); return }
        haptics.press()
        log("▶ move $axis: ${if (relative) "by" else "to"} $value")
        ownMotion.sent(nowMs())
        planMove(axis, value, relative).lines.forEach { link.sendLine(it) }
    }
    // ---- bed map ("Go to") --------------------------------------------------------------------
    /** The map's grid (5 × 5 / 9 × 9 / 17 × 17), remembered. */
    val mapGrid = MutableStateFlow(com.z1pendant.map.MapGrid.entries.firstOrNull { it.name == prefs.getString("map.grid", null) }
        ?: com.z1pendant.map.MapGrid.G9)
    fun setMapGrid(g: com.z1pendant.map.MapGrid) { mapGrid.value = g; prefs.edit { putString("map.grid", g.name) } }
    /** The move the map started (null = none); the map shows its steps and Stop. */
    val mapMove = MutableStateFlow<com.z1pendant.map.MapMove?>(null)
    private var mapJob: Job? = null

    /** Why the map can't move now (null = it can). */
    fun mapBlocked(): String? {
        val st = link.status.value
        return when {
            link.conn.value != ConnState.Connected -> "Not connected."
            st == null || !st.isIdle -> "The machine is ${st?.state ?: "not reporting"}: it moves when it's Idle."
            runner.running.value != null -> "“${runner.running.value!!.label}” is still running."
            mapMove.value?.let { it.stage != com.z1pendant.map.MapMove.Stage.DONE && it.error == null } == true -> "Still moving."
            else -> null
        }
    }

    /**
     * Move to [t]: with [safeZ], Z all the way up, then X/Y; without, X/Y at the current height
     * (machine coordinates). The steps follow the reported position: Lift until Z is up, Move until
     * it's Idle at the target.
     */
    fun mapGo(t: com.z1pendant.map.MapTarget, safeZ: Boolean = true) {
        mapBlocked()?.let { snack.tryEmit("Go to: $it"); haptics.thud(); return }
        haptics.press()
        log("▶ map: go to ${t.label} (machine X ${Fmt.fixed(t.x, 3)} Y ${Fmt.fixed(t.y, 3)}, ${if (safeZ) "Z up first" else "Z stays"})")
        ownMotion.sent(nowMs())
        com.z1pendant.map.BedMap.moveLines(t, safeZ).forEach { link.sendLine(it) }
        val safe = com.z1pendant.map.BedMap.SAFE_Z
        mapMove.value = com.z1pendant.map.MapMove(t, if (safeZ) com.z1pendant.map.MapMove.Stage.LIFT else com.z1pendant.map.MapMove.Stage.MOVE, safeZ = safeZ)
        mapJob?.cancel()
        mapJob = viewModelScope.launch {
            val t0 = nowMs(); var moved = false
            while (true) {
                kotlinx.coroutines.delay(60)
                val st = link.status.value ?: continue
                val cur = mapMove.value ?: return@launch
                if (link.conn.value != ConnState.Connected) { mapMove.value = cur.copy(error = "The link to the machine dropped."); return@launch }
                if (st.isAlarm) { mapMove.value = cur.copy(error = "The machine stopped (Alarm)."); return@launch }
                if (!st.isIdle) moved = true
                if (cur.stage == com.z1pendant.map.MapMove.Stage.LIFT && kotlin.math.abs(st.mpos[2] - safe) < 0.01)
                    mapMove.value = cur.copy(stage = com.z1pendant.map.MapMove.Stage.MOVE)
                val there = kotlin.math.abs(st.mpos[0] - t.x) < 0.01 && kotlin.math.abs(st.mpos[1] - t.y) < 0.01
                if (st.isIdle && there && link.pendingLines == 0) {
                    mapMove.value = cur.copy(stage = com.z1pendant.map.MapMove.Stage.DONE); haptics.press(); return@launch
                }
                // Idle somewhere else after it moved (or never moving): the machine didn't take it.
                if (st.isIdle && link.pendingLines == 0 && (moved || nowMs() - t0 > 4000) && !there && nowMs() - t0 > 1500) {
                    mapMove.value = cur.copy(error = "It stopped short of ${t.label}."); return@launch
                }
            }
        }
    }

    /** Stop on the map: halt at once (reset, like probing's Stop). Unlock afterwards. */
    fun mapStop() { mapJob?.cancel(); mapMove.value = mapMove.value?.copy(error = "Stopped."); haptics.press(); link.reset() }
    /** The map closed, or a new target was picked: forget the last move. */
    fun mapClear() { if (mapMove.value?.let { it.stage == com.z1pendant.map.MapMove.Stage.DONE || it.error != null } != false) mapMove.value = null }

    fun setFeedOverride(p: Int) = link.dialect.feedOverride(p.coerceIn(10, 300)).forEach { link.sendLine(it) }
    fun hold() { haptics.press(); link.feedHold() }
    fun resume() { haptics.press(); link.resume() }
    fun reset() { haptics.press(); runner.cancel(); jog.cancelPending(); link.reset() }
    /** The drawer's Reset asks first (ResetDialog): it's an emergency stop, not a pause. */
    val resetAsk = MutableStateFlow(false)
    fun confirmReset() { resetAsk.value = false; stopAndClear() }

    /** The Z1's last "ALARM: …" line (kept until it leaves Alarm). */
    val lastAlarm = MutableStateFlow<String?>(null)
    /** The alarm sheet is up (tap the Alarm status, or an alarm on the main screen). */
    val alarmSheet = MutableStateFlow(false)
    @Volatile private var stopping = false

    private fun unlockNow() = link.dialect.unlock().forEach { link.sendLine(it) }

    /**
     * Stop, as Makera Studio's Stop does it (captured 2026-11-17): the halt byte, which ends a job
     * ("Aborted by halt" → Alarm), then `$X` once the Z1 is in Alarm, so it's ready to move again
     * and the job is gone. The drawer then closes back to the main screen.
     * Cancelling a job also clears the leveling grid (`M370`): the firmware applies a grid to every
     * move until it's cleared, jogs included, so the job's mesh must not outlive the job.
     */
    fun stopAndClear() {
        stopping = true
        val hadJob = link.status.value?.job != null
        reset()
        viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(2_000) { link.status.first { it?.isAlarm == true } }
            kotlinx.coroutines.delay(300)
            unlockNow()
            if (hadJob) { log("▶ job cancelled: leveling grid cleared (M370)"); link.sendLine("M370") }
            kotlinx.coroutines.delay(1_500)
            stopping = false
            controlsOpen.value = false
        }
    }

    /**
     * Clear alarm. If the Z1 still has the job open (an e-stop halts it but the file stays open), end
     * it the way Stop does; otherwise just `$X`.
     */
    fun clearAlarm() {
        alarmSheet.value = false
        if (link.status.value?.job != null) stopAndClear() else { haptics.press(); unlockNow() }
    }

    private fun builtin(b: BuiltIn): List<String>? {
        val d = link.dialect
        val fro = link.status.value?.feedOverride?.toInt() ?: 100
        return when (b) {
            BuiltIn.HOME -> d.home()
            BuiltIn.UNLOCK -> d.unlock()
            // Zero hotkeys toggle like the zero buttons: pressed again with nothing moved = undo.
            BuiltIn.ZERO_SELECTED -> zeroLines(axis.value.toString())
            BuiltIn.ZERO_X -> zeroLines("X"); BuiltIn.ZERO_Y -> zeroLines("Y")
            BuiltIn.ZERO_Z -> zeroLines("Z"); BuiltIn.ZERO_A -> zeroLines("A")
            BuiltIn.ZERO_XY -> zeroLines("XY"); BuiltIn.ZERO_XYZ -> zeroLines("XYZ")
            BuiltIn.HOLD -> { link.feedHold(); null }
            BuiltIn.RESUME -> { link.resume(); null }
            BuiltIn.RESET -> { reset(); null }
            BuiltIn.JOG_PLUS -> { jog.click(1); null }
            BuiltIn.JOG_MINUS -> { jog.click(-1); null }
            BuiltIn.NEXT_AXIS -> { selectAxis(axes[(axes.indexOf(axis.value) + 1) % axes.size]); null }
            BuiltIn.AXIS_X -> { selectAxis('X'); null }; BuiltIn.AXIS_Y -> { selectAxis('Y'); null }
            BuiltIn.AXIS_Z -> { selectAxis('Z'); null }; BuiltIn.AXIS_A -> { selectAxis('A'); null }
            BuiltIn.STEP_UP -> { selectStep(stepIndex.value + 1); null }
            BuiltIn.STEP_DOWN -> { selectStep(stepIndex.value - 1); null }
            BuiltIn.FEED_UP -> d.feedOverride((fro + 10).coerceAtMost(300))
            BuiltIn.FEED_DOWN -> d.feedOverride((fro - 10).coerceAtLeast(10))
            BuiltIn.FEED_100 -> d.feedOverride(100)
            BuiltIn.LIGHT_TOGGLE -> toggleOutput("light") { d.light(it) }
            BuiltIn.VACUUM_TOGGLE -> toggleOutput("vacuum") { d.vacuum(it) }
            BuiltIn.SPINDLE_TOGGLE -> spindleToggle()
            BuiltIn.RUN_JOB -> { runPick.value = true; null }
        }
    }

    /** Flip an on/off output. The tile flips now; the diagnose read-back confirms it. Unknown = turn on. */
    private fun toggleOutput(name: String, cmd: (Boolean) -> List<String>): List<String> {
        val on = link.outputs.value[name] == true
        link.assumeOutput(name, !on)
        viewModelScope.launch { kotlinx.coroutines.delay(1200); link.refreshOutputs() }
        return cmd(!on)
    }

    // ---- hotkeys ------------------------------------------------------------------------------
    private fun loadHotkeys(): List<Hotkey> = shownFirst(addRunTile(try {
        if (hotkeyFile.exists()) migrate(HotkeyJson.import(hotkeyFile.readText())) else Hotkey.defaults()
    } catch (_: Exception) { Hotkey.defaults() }))

    /** The Run tile arrived after people had their own hotkeys: add it once, first in the grid. */
    private fun addRunTile(l: List<Hotkey>): List<Hotkey> {
        if (prefs.getBoolean("runTileAdded", false)) return l
        prefs.edit { putBoolean("runTileAdded", true) }
        if (l.any { h -> h.steps.any { (it as? com.z1pendant.hotkeys.Step.Builtin)?.action == BuiltIn.RUN_JOB } }) return l
        val out = listOf(Hotkey.defaults().first()) + l
        if (hotkeyFile.exists()) hotkeyFile.writeText(HotkeyJson.export(out))
        return out
    }

    /** Grid order rule: hotkeys shown in the grid always come first, hidden (button-only) ones after. */
    private fun shownFirst(l: List<Hotkey>) = l.filter { it.showOnMain } + l.filterNot { it.showOnMain }

    /**
     * Old defaults had separate on/off hotkeys: "Light on" (M821) + "Light off" (M822), and
     * "Vacuum on" (M851) + "Vacuum off" (M852). Fold each pair into one toggle, kept where the
     * "on" one was, with both sets of button bindings. Shown in the grid if either one was.
     */
    private fun migrate(list: List<Hotkey>): List<Hotkey> {
        fun isOnly(h: Hotkey, code: String) = h.steps.size == 1 && (h.steps[0] as? com.z1pendant.hotkeys.Step.GCode)?.text?.trim() == code
        fun fold(l: List<Hotkey>, onLabel: String, onCode: String, offLabel: String, offCode: String,
                 label: String, action: BuiltIn): List<Hotkey> {
            val on = l.indexOfFirst { it.label == onLabel && isOnly(it, onCode) }
            val off = l.indexOfFirst { it.label == offLabel && isOnly(it, offCode) }
            if (on < 0 || off < 0) return l
            val toggle = l[on].copy(label = label, kind = com.z1pendant.hotkeys.Kind.BUILTIN,
                steps = listOf(com.z1pendant.hotkeys.Step.Builtin(action)),
                showOnMain = l[on].showOnMain || l[off].showOnMain,
                triggers = l[on].triggers + l[off].triggers)
            return l.mapIndexedNotNull { i, h -> when (i) { on -> toggle; off -> null; else -> h } }
        }
        // Old "Z up" / "Go to XY0" defaults raised Z with G53 G0 Z0, which is past the soft limit
        // (top = -1) and halts the machine. Rewrite exactly that line to the safe height.
        val zFix = Regex("""^\s*G53\s*G0\s*Z0(\.0*)?\s*$""", RegexOption.IGNORE_CASE)
        fun fixZ(h: Hotkey) = h.copy(steps = h.steps.map { st ->
            if (st is com.z1pendant.hotkeys.Step.GCode)
                st.copy(text = st.text.lines().joinToString("\n") { if (zFix.matches(it)) "G53 G0 Z${Hotkey.SAFE_Z}" else it })
            else st
        })
        // The old "Spindle stop" default (M5 only) becomes the Spindle toggle tile, in the same place,
        // shown in the grid (a hidden stop button was easy to leave hidden; the toggle is a tile).
        fun spindle(h: Hotkey) = if (h.label == "Spindle stop" && isOnly(h, "M5"))
            h.copy(label = "Spindle", icon = "mi:Cyclone", kind = com.z1pendant.hotkeys.Kind.BUILTIN,
                steps = listOf(com.z1pendant.hotkeys.Step.Builtin(BuiltIn.SPINDLE_TOGGLE)), confirm = false,
                onlyIdle = false, showOnMain = true)
            else h
        val out = fold(fold(list, "Light on", "M821", "Light off", "M822", "Light", BuiltIn.LIGHT_TOGGLE),
            "Vacuum on", "M851", "Vacuum off", "M852", "Vacuum", BuiltIn.VACUUM_TOGGLE).map(::fixZ).map(::spindle)
        if (out != list) hotkeyFile.writeText(HotkeyJson.export(out))
        return out
    }

    private fun saveHotkeys(all: List<Hotkey>) {
        val list = shownFirst(all)
        hotkeys.value = list
        viewModelScope.launch(Dispatchers.IO) { hotkeyFile.writeText(HotkeyJson.export(list)) }
    }

    fun upsertHotkey(h: Hotkey) {
        val l = hotkeys.value.toMutableList()
        val i = l.indexOfFirst { it.id == h.id }
        if (i >= 0) l[i] = h else l.add(h)
        saveHotkeys(l)
    }
    fun deleteHotkey(id: String) = saveHotkeys(hotkeys.value.filterNot { it.id == id })
    fun moveHotkey(id: String, by: Int) {
        val l = hotkeys.value.toMutableList()
        val i = l.indexOfFirst { it.id == id }; if (i < 0) return
        val j = (i + by).coerceIn(0, l.lastIndex)
        l.add(j, l.removeAt(i)); saveHotkeys(l)
    }
    fun resetHotkeys() = saveHotkeys(Hotkey.defaults())

    fun startArranging() { arrangeOrder.value = hotkeys.value.map { it.id }; editMode.value = true }
    fun moveInArrangement(id: String, to: Int) {
        // Same order the grid shows (shown first), so `to` means the slot under the finger.
        // A shown tile stays among the shown ones, a hidden tile among the hidden ones.
        val byId = hotkeys.value.associateBy { it.id }
        val l = (arrangeOrder.value ?: return).let { ids ->
            ids.filter { byId[it]?.showOnMain == true } + ids.filterNot { byId[it]?.showOnMain == true }
        }.toMutableList()
        val from = l.indexOf(id); if (from < 0) return
        val nShown = l.count { byId[it]?.showOnMain == true }
        val range = if (byId[id]?.showOnMain == true) 0 until nShown else nShown..l.lastIndex
        if (range.isEmpty()) return
        val dest = to.coerceIn(range.first, range.last)
        if (dest == from) return                       // dragged past the shown/hidden boundary: stays put
        l.add(dest, l.removeAt(from))
        arrangeOrder.value = l
        haptics.tick()
    }
    fun saveArrangement() {
        val order = arrangeOrder.value
        if (order != null) {
            val byId = hotkeys.value.associateBy { it.id }
            val sorted = order.mapNotNull { byId[it] } + hotkeys.value.filter { it.id !in order }
            saveHotkeys(sorted)
        }
        arrangeOrder.value = null; editMode.value = false
    }
    fun exportHotkeys(): String = HotkeyJson.export(hotkeys.value)
    fun importHotkeys(text: String) {
        try {
            val l = HotkeyJson.import(text); saveHotkeys(l); snack.tryEmit("Imported ${l.size} hotkeys")
        } catch (e: Exception) { snack.tryEmit("Import failed: ${e.message}") }
    }

    /** Tap on a tile, a bound button, or the editor's Test button. */
    fun requestRun(h: Hotkey, test: Boolean = false) {
        runner.blockReason(h)?.let { snack.tryEmit("${h.label}: $it"); haptics.thud(); return }
        // The spindle tile asks with its own start box, never the generic one.
        if (h.confirm && !test && !isSpindleToggle(h)) { confirmFor.value = h; return }
        haptics.press()
        runNow(h)
    }
    private fun isSpindleToggle(h: Hotkey) = h.steps.size == 1 &&
        (h.steps[0] as? com.z1pendant.hotkeys.Step.Builtin)?.action == BuiltIn.SPINDLE_TOGGLE
    fun confirmRun() { confirmFor.value?.let { haptics.press(); runNow(it) }; confirmFor.value = null }
    /** Console marks which hotkey the commands that follow belong to. */
    private fun runNow(h: Hotkey) { log("▶ hotkey: ${h.label}"); runner.run(h) }

    // ---- settings -----------------------------------------------------------------------------
    fun setHaptic(v: Float) { hapticStrength.value = v; haptics.strength = v; prefs.edit { putFloat("haptic", v) } }
    fun setVolumeJog(v: Boolean) { volumeJog.value = v; prefs.edit { putBoolean("volumeJog", v) } }
    fun setKeepScreenOn(v: Boolean) { keepScreenOn.value = v; prefs.edit { putBoolean("keepScreenOn", v) } }
    fun setTickMs(v: Long) { tickMs.value = v; prefs.edit { putLong("tickMs", v) }; applyJogConfig() }
    fun setDegPerClick(v: Float) { degPerClick.value = v; prefs.edit { putFloat("degPerClick", v) } }
    fun setHideSerial(v: Boolean) { hideSerial.value = v; prefs.edit { putBoolean("hideSerial", v) } }
    fun setZeroHold(v: Boolean) { zeroHold.value = v; prefs.edit { putBoolean("zeroHold", v) } }
    fun setSoftClamp(v: Boolean) { softClamp.value = v; prefs.edit { putBoolean("softClamp", v) }; applyJogConfig() }

    // ---- physical buttons -------------------------------------------------------------------------
    private class Held(val downAt: Long, var longFired: Boolean = false)
    private val held = HashMap<Int, Held>()
    private val longMs = 500L

    private fun deviceName(e: KeyInput): String? = e.deviceName

    private fun keyLabel(e: KeyInput): String = e.label

    private fun matches(t: Trigger, e: KeyInput) = t.keyCode == e.keyCode && (t.device == null || t.device == deviceName(e))

    private fun bindings(e: KeyInput, long: Boolean) =
        hotkeys.value.filter { h -> h.triggers.any { it.longPress == long && matches(it, e) } }

    /** Called for every hardware key event (Android: Activity.dispatchKeyEvent). Returns true if consumed. */
    fun onKey(e: KeyInput): Boolean {
        val code = e.keyCode
        if (code == KeyCodes.BACK || code == KeyCodes.HOME || code == KeyCodes.POWER) return false
        val cap = capture.value
        if (cap != null) {
            when (e.action) {
                KeyAction.Down -> if (e.repeatCount == 0) held[code] = Held(e.downTime) else if (e.isLongPress) held[code]?.longFired = true
                KeyAction.Up -> {
                    val h = held.remove(code)
                    // Event timestamps, not the wall clock: works for injected events and laggy BT remotes.
                    val long = h != null && (h.longFired || e.eventTime - e.downTime >= longMs)
                    val dev = deviceName(e)
                    cap(Trigger(code, long, if (isVolume(code)) null else dev,
                        keyLabel(e) + (if (long) " (hold)" else "") + (dev?.takeIf { !isVolume(code) }?.let { " · $it" } ?: "")))
                    capture.value = null
                    haptics.press()
                }
                KeyAction.Other -> {}
            }
            return true
        }

        if (!onMainScreen) return false          // editor / settings / console: keys behave normally
        val longs = bindings(e, true)
        val shorts = bindings(e, false)
        val volume = isVolume(code)
        val volumeDefault = volume && volumeJog.value && shorts.isEmpty()
        if (longs.isEmpty() && shorts.isEmpty() && !volumeDefault) {
            // Swallow unbound gamepad buttons on the main screen: B would otherwise act as Back.
            return e.isGamepadButton
        }

        fun fireShort() {
            if (shorts.isNotEmpty()) shorts.forEach { requestRun(it) }
            else if (volumeDefault) { jog.click(if (code == KeyCodes.VOLUME_UP) 1 else -1); haptics.tick() }
        }

        when (e.action) {
            KeyAction.Down -> {
                if (e.repeatCount == 0) {
                    held[code] = Held(e.downTime)
                    if (longs.isEmpty()) fireShort()
                } else {
                    val h = held[code] ?: return true
                    if (longs.isNotEmpty()) {
                        if (!h.longFired && (e.isLongPress || e.eventTime - e.downTime >= longMs)) { h.longFired = true; longs.forEach { requestRun(it) } }
                    } else if (volumeDefault) fireShort()          // holding a volume key keeps jogging, one click per repeat
                }
            }
            KeyAction.Up -> {
                val h = held.remove(code)
                if (longs.isNotEmpty() && h != null && !h.longFired) fireShort()
            }
            KeyAction.Other -> {}
        }
        return true
    }

    private fun isVolume(c: Int) = c == KeyCodes.VOLUME_UP || c == KeyCodes.VOLUME_DOWN

    override fun onCleared() { jog.stop(); link.disconnect() }

    companion object {
        /** A typed Move by needs a status at most this old (the machine is polled every 200 ms when idle). */
        const val FRESH_STATUS_MS = 1000L

        /**
         * G-code for a typed move. Go to: work position [value] (`G90 G0`). Move by: the machine
         * position it ends at, [endMachine], as `G53 G0` (machine coordinates for this line only),
         * so no G91 is ever left behind if the link drops before a G90 could follow.
         * Numbers: BigDecimal(v).setScale(4, HALF_UP).stripTrailingZeros().
         */
        fun moveLines(axis: Char, value: Double, relative: Boolean, endMachine: Double): List<String> =
            if (relative) listOf("G53 G0 $axis${Fmt.scaledPlain(endMachine, 4)}")
            else listOf("G90 G0 $axis${Fmt.scaledPlain(value, 4)}")
    }
}

/** Makera Z1 spindle: 150 W, 0–13,000 RPM (Makera's spec). */
const val SPINDLE_MAX_RPM = 13_000
/** The start box's preset RPMs; the top one is the spindle's max. */
val SPINDLE_PRESETS = listOf(4_000, 6_000, 8_000, 10_000, 12_000, SPINDLE_MAX_RPM)
/** After an M3, the tile shows "on" this long while the RPM is still reading 0 (spin-up, status lag). */
const val SPINDLE_PENDING_MS = 10_000L

/** Spindle override slider in RPM, for programmed RPM [target] at override [pct] %. */
data class SpindleSlide(val target: Double, val pct: Double, val lo: Int, val hi: Int) {
    /** What the spindle is set to now: programmed × override. */
    val now: Int get() = kotlin.math.round(target * pct / 100).toInt()
    fun clamp(rpm: Int) = rpm.coerceIn(lo, hi)
    /** Override % that gives [rpm], to 0.01 % (lands within ~1 RPM of the drag). */
    fun percentFor(rpm: Int) = kotlin.math.round(clamp(rpm) / target * 10000) / 100
}

/**
 * Bottom: half the programmed RPM. Top: the spindle's max, or 200 % of programmed, whichever is
 * lower. 50–200 % is the stock Z1 firmware's own clamp on M223 (makeraz1firmware SpindleControl;
 * the community firmware allows 10–300 %) and Makera's slider range.
 */
fun spindleSlideFor(target: Double, pct: Double) = SpindleSlide(target, pct,
    lo = kotlin.math.round(target * 0.5).toInt(),
    hi = kotlin.math.round(minOf(SPINDLE_MAX_RPM.toDouble(), target * 2.0)).toInt())

/**
 * Probe tool numbers (community firmware ATCHandler: "a 3-axis probe must be installed (tool 0,
 * 9999 or tool >= 999990)"); -1 = empty collet. Spinning a probe would wreck it, so the spindle
 * start box refuses these (the stock firmware's own check, 1 <= T <= 999, already stops 9999 and up).
 */
fun isProbeTool(t: Int) = t == 0 || t == 9999 || t >= 999_990

/** What the drawer shows about a running job. */
data class JobView(val progress: com.z1pendant.link.JobProgress, val file: com.z1pendant.link.JobFile?,
                   val totalLines: Int? = null,
                   /** The file's estimated time per line, once it's read (JobTime.kt). */
                   val time: JobTime? = null,
                   /** Real ÷ estimated over the last few minutes (JobPace), 1 until there's enough. */
                   val pace: Double = 1.0,
                   val feedOverride: Double? = null) {
    /**
     * Percent done, once the file is in: the estimated time up to the line being cut ÷ the whole
     * job's estimate (by lines if there's no estimate). 0 while the Z1 says line 0 (it hasn't cut
     * anything: the first tool change), 100 once nothing estimated is left (the park at the end).
     * The firmware's own percent is of the file's *bytes read*, and it reads ahead (planner queue +
     * the Wi-Fi board's buffer): a short file with a long comment header starts at 44 %, and near the
     * end it says 100 while the last moves still run. It's only the fallback until the file is in.
     */
    val percent: Int get() {
        if (progress.line <= 0) return 0
        val n = totalLines?.takeIf { it > 0 } ?: return progress.percent
        time?.takeIf { it.total > 0.5 }?.let { t ->
            val left = t.total - t.at(progress.line)
            return if (left < 0.05) 100 else (100 * t.at(progress.line) / t.total).toInt().coerceIn(0, 99)
        }
        return (progress.line * 100 / n).toInt().coerceIn(0, 99)
    }

    /**
     * Time left. With the file read: the estimated time of the lines still to run (feed moves at the
     * current feed override) × the pace measured so far. Before that, a rough guess from the pace:
     * elapsed × (left ÷ done), by lines when known, else by the firmware's %.
     */
    val leftS: Long? get() {
        time?.let { jt -> return (jt.leftAfter(progress.line, feedOverride) * pace).toLong() }
        val t = totalLines
        if (t != null && t > 0 && progress.line > 0) return progress.elapsedS * (t - progress.line).coerceAtLeast(0) / progress.line
        return progress.percent.takeIf { it in 1..99 }?.let { progress.elapsedS * (100 - it) / it }
    }
}
