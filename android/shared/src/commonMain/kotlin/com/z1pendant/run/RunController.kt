package com.z1pendant.run

import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.platform.PLog
import com.z1pendant.platform.Prefs
import com.z1pendant.probe.ProbeController
import com.z1pendant.probe.ProbeLength
import com.z1pendant.probe.ProbeTool
import com.z1pendant.probe.ToolAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Run flow (mockup: pendant/mockups/run_mock.html, round 2): pick a file, set the origin, the
 * pre-run checks (Probe Z, margin scan, leveling, all with the laser probe), the assist modes, then
 * start the job the way Makera Studio does. Nothing here moves the machine except Scan now, the
 * laser probe check (a tool change or a setter calibration) and Start, each after a tap.
 */
/** The anchor bracket's arms (the machine's `coordinate.anchor_width` / `anchor_length`, config.txt). */
const val ANCHOR_WIDTH = 15.0; const val ANCHOR_LENGTH = 100.0

class RunController(
    private val link: MachineLink,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val probe: ProbeController,
    /** Something that may move the machine was sent (own-motion tracking). */
    private val onSend: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** The machine's X/Y travel (its soft limits as read on connect; a Z1's until then). */
    val bed: () -> com.z1pendant.link.Bed = { com.z1pendant.link.Bed.Z1 },
) {
    sealed class Load {
        data class Loading(val done: Int, val total: Int) : Load()
        data class Ready(val extents: Extents?, val cutsBeforeM6: Boolean,
                         /** Estimated machine time (JobTime.kt), for "about 13 min" before start. */
                         val time: com.z1pendant.JobTime? = null,
                         /** The file's tool changes and descriptions, for the Run page's tool list. */
                         val tools: com.z1pendant.JobTools? = null) : Load()
        data class Failed(val why: String) : Load()
    }

    enum class LaserPhase { NONE, CHECKING, OK, WRONG, FAILED }
    data class Laser(val phase: LaserPhase, val note: String? = null, val other: ProbeTool? = null)

    companion object {
        /**
         * The laser probe check once the setter routine has finished ("Done ATC"). Only a length that
         * matches the laser probe's (or its first-ever reading, which is then learned) lets the job go
         * on: the margin scan and Z probe trust that the laser probe is what's in. No reading at all
         * (the setter touch didn't come through) is a failed check, not a pass.
         */
        fun laserVerdict(c: ProbeLength.Check?): Laser = when {
            c == null -> Laser(LaserPhase.FAILED, "the setter reading didn't come through, so it couldn't tell which probe is in. Measure it again.")
            c.result == ProbeLength.Result.MATCH ->
                Laser(LaserPhase.OK)   // within the margin: how far off isn't worth showing (Jimmy, 2026-11-18)
            c.result == ProbeLength.Result.LEARNED -> Laser(LaserPhase.OK, "its length is now learned")
            c.result == ProbeLength.Result.LOOKS_LIKE -> Laser(LaserPhase.WRONG, "${c.other?.label} detected, not the laser probe.", c.other)
            else -> Laser(LaserPhase.WRONG, "Its length doesn't match the laser probe's.")
        }
    }

    enum class ScanPhase { NONE, RUNNING, DONE, FAILED }
    data class Scan(val phase: ScanPhase, val note: String? = null)

    /** The Run screen is open. */
    val active = MutableStateFlow(false)
    val path = MutableStateFlow<String?>(null)
    /** 0 Origin, 1 Checks, 2 Assist, 3 Run. */
    val step = MutableStateFlow(0)
    val setup = MutableStateFlow(RunSetup())
    val load = MutableStateFlow<Load>(Load.Loading(0, 0))
    val camOffset = MutableStateFlow<CamOffset?>(null)
    /** Anchor 1 in machine coordinates, read from the machine's config (`coordinate.anchor1_x/y`). */
    val anchor = MutableStateFlow<Pair<Double, Double>?>(null)
    /** The setup whose origin was last sent (null = none sent this run). */
    val applied = MutableStateFlow<RunSetup?>(null)
    /** The 3D probe wizard ran from here. */
    val probed3d = MutableStateFlow(false)
    val laser = MutableStateFlow(Laser(LaserPhase.NONE))
    /** The "needs the laser probe" sheet is open. */
    val laserAsk = MutableStateFlow(false)
    val scan = MutableStateFlow(Scan(ScanPhase.NONE))
    /** Why Next / Scan now / Start didn't go (shown under the buttons). */
    val error = MutableStateFlow<String?>(null)
    /** The job panel's mesh chip was tapped. */
    val meshShow = MutableStateFlow(false)
    /** Set once the job was started: the screen closes. */
    val started = MutableStateFlow(false)

    private var afterLaser: (() -> Unit)? = null
    private var loadJob: Job? = null
    private var scanSawStart = false

    init {
        scope.launch { link.messages.collect { onMessage(it) } }
        scope.launch {
            kotlinx.coroutines.flow.combine(probe.toolAction, probe.lengthCheck) { a, c -> a to c }.collect { (a, c) -> onProbe(a, c) }
        }
        scope.launch {
            link.status.collect { st ->
                // The laser probe check holds only while the machine still says T0.
                if (laser.value.phase == LaserPhase.OK && st?.tool != null && st.tool != 0) laser.value = Laser(LaserPhase.NONE)
            }
        }
        scope.launch { link.conn.collect { if (it != ConnState.Connected) { anchor.value = null; if (laser.value.phase == LaserPhase.OK) laser.value = Laser(LaserPhase.NONE) } } }
    }

    // ---- open / close ---------------------------------------------------------------------------
    /** Open the Run screen for [file] (a path on the SD card). Reads the whole file for its toolpath box. */
    fun open(file: String) {
        path.value = file; step.value = 0; error.value = null; started.value = false
        applied.value = null; probed3d.value = false; scan.value = Scan(ScanPhase.NONE)
        laser.value = Laser(LaserPhase.NONE); laserAsk.value = false; afterLaser = null
        camOffset.value = null
        setup.value = RunSetup(
            // Scan and leveling at start begin off on every run (Jimmy, 2026-11-19); the numbers are remembered.
            gridX = prefs.getInt("run.gridX", 3), gridY = prefs.getInt("run.gridY", 3),
            clearance = prefs.getFloat("run.clearance", 5f).toDouble(),
            levelInset = prefs.getFloat("run.inset", 3f).toDouble(),
            levelBelow = levelBelow.value,
        )
        active.value = true
        load.value = Load.Loading(0, 0)
        loadJob?.cancel()
        loadJob = scope.launch {
            val text = link.downloadFile(file) { d, t -> load.value = Load.Loading(d, t) }
            if (text == null) { load.value = Load.Failed("Couldn't read the file from the Z1."); return@launch }
            val ext = Toolpath.extents(text)
            camOffset.value = CamOffset.parse(text.take(8000))?.also { o ->
                if (setup.value.origin != Origin.ANCHOR1) setup.value = setup.value.copy(offX = o.x, offY = o.y)
            }
            val tools = runCatching { com.z1pendant.JobFileParser.parse(text) }.getOrNull()
            load.value = Load.Ready(ext, Toolpath.cutsBeforeToolChange(text), tools?.time, tools)
            PLog.i("run", "$file: extents $ext")
        }
        scope.launch { readAnchor(); readConfig("zprobe.max_z")?.let { probeMaxZ = it } }
    }

    /** Settings: the deepest a leveling touch may go below the stock top, mm. */
    val levelBelow = MutableStateFlow(prefs.getFloat("run.levelBelow", LEVEL_BELOW_MM.toFloat()).toDouble())
    fun setLevelBelow(mm: Double) {
        levelBelow.value = mm; prefs.edit { putFloat("run.levelBelow", mm.toFloat()) }
        setup.value = setup.value.copy(levelBelow = mm)
    }

    /** The machine's own `zprobe.max_z` (config), put back after the leveling's tighter one. */
    private var probeMaxZ = 100.0

    fun close() {
        loadJob?.cancel(); active.value = false; laserAsk.value = false; afterLaser = null
        probe.checkAs = null
    }

    fun update(s: RunSetup) {
        setup.value = s; error.value = null
        prefs.edit {
            putInt("run.gridX", s.gridX); putInt("run.gridY", s.gridY); putFloat("run.clearance", s.clearance.toFloat())
            putFloat("run.inset", s.levelInset.toFloat())
            if (s.origin == Origin.CUSTOM) s.anchor?.let { putString("run.anchorId", it.id) }
        }
    }

    /** The custom anchor picked last time (its id), to preselect in the dropdown. */
    fun lastAnchorId(): String? = prefs.getString("run.anchorId", null)

    fun back() { error.value = null; if (step.value > 0) step.value -= 1 else close() }

    /** Back to an earlier page (the Run page's Edit links). */
    fun goTo(n: Int) { if (n in 0 until step.value) { error.value = null; step.value = n } }

    val extents: Extents? get() = (load.value as? Load.Ready)?.extents

    // ---- origin ---------------------------------------------------------------------------------
    /** Anchor 1 from the machine's config: "sd: coordinate.anchor1_x is set to -190.69". */
    private suspend fun readAnchor(): Pair<Double, Double>? {
        anchor.value?.let { return it }
        if (link.conn.value != ConnState.Connected) return null
        val x = readConfig("coordinate.anchor1_x") ?: return null
        val y = readConfig("coordinate.anchor1_y") ?: return null
        return (x to y).also { anchor.value = it }
    }

    /** Read Anchor 1 from the machine if it isn't known yet (Settings › Anchors shows it). */
    fun loadAnchor1() { scope.launch { readAnchor() } }

    private val configWait = HashMap<String, CompletableDeferred<Double>>()

    /** A number from the SD config (`config-get sd <key>`), or null if it isn't there (4 s timeout). */
    suspend fun readConfig(key: String): Double? {
        val d = CompletableDeferred<Double>()
        configWait[key] = d                 // main thread only, like onMessage
        link.sendLine("config-get sd $key")
        return withTimeoutOrNull(4000) { d.await() }.also { configWait.remove(key) }
    }

    /** Where the work zero will be, in machine X/Y, for the map. */
    fun plannedOrigin(st: MachineStatus?): Pair<Double, Double>? {
        val s = setup.value
        val cur = st?.let { (it.mpos.getOrNull(0) ?: return null) - (it.wpos.getOrNull(0) ?: 0.0) to
            (it.mpos.getOrNull(1) ?: return null) - (it.wpos.getOrNull(1) ?: 0.0) }
        return when (s.origin) {
            Origin.ANCHOR1 -> anchor.value?.let { (ax, ay) -> (ax + s.offX) to (ay + s.offY) }
            // An anchor may give only X or only Y (an edge): the other stays where it is now.
            Origin.CUSTOM -> s.anchor?.let { a ->
                (a.x?.plus(s.aOffX) ?: cur?.first ?: return null) to (a.y?.plus(s.aOffY) ?: cur?.second ?: return null)
            }
            Origin.SPINDLE -> if (applied.value?.origin == Origin.SPINDLE) cur else st?.let { it.mpos[0] to it.mpos[1] }
            else -> cur
        }
    }

    /** The toolpath box with the planned origin goes past the bed (the X/Y soft limits), or null if unknown. */
    fun offBed(st: MachineStatus?): Boolean? {
        val e = extents ?: return null
        val (x0, y0) = plannedOrigin(st) ?: return null
        val b = bed()
        return x0 + e.minX < b.x0 - 0.01 || x0 + e.maxX > b.x1 + 0.01 || y0 + e.minY < b.y0 - 0.01 || y0 + e.maxY > b.y1 + 0.01
    }

    private fun ready(st: MachineStatus?): String? = when {
        link.conn.value != ConnState.Connected -> "Not connected to the Z1."
        st == null -> "No status from the machine yet."
        st.job != null -> "A job is running."
        !st.isIdle -> "The machine is ${st.state}, not Idle."
        else -> null
    }

    /** Next from step [step]: step 0 sends the origin (once per choice). */
    fun next() {
        error.value = null
        if (probeNeeded()) return                                   // the screen opens the probe wizard instead
        if (step.value == 0) {
            val s = setup.value
            if (s.origin == Origin.CUSTOM && s.anchor == null) { error.value = "Pick an anchor, or save one in Settings › Anchors."; return }
            val needSend = s.origin == Origin.ANCHOR1 || s.origin == Origin.SPINDLE || s.origin == Origin.CUSTOM
            val same = applied.value?.let { originKey(it) == originKey(s) } == true
            if (needSend && !same) {
                ready(link.status.value)?.let { error.value = it; return }
                scope.launch {
                    val a = if (s.origin == Origin.ANCHOR1) readAnchor() ?: run {
                        error.value = "Couldn't read Anchor 1's position from the machine."; return@launch
                    } else null
                    val lines = RunCommands.originLines(s, a)
                    log("▶ run: origin ${s.origin.label}" + (s.anchor?.takeIf { s.origin == Origin.CUSTOM }?.let { " ${it.name}" } ?: ""))
                    lines.forEach { link.sendLine(it) }
                    applied.value = s
                    step.value = 1
                }
                return
            }
        }
        if (step.value < 3) step.value += 1
    }

    /** What decides the origin commands: Next sends them again only when one of these changed. */
    private fun originKey(s: RunSetup) = listOf(s.origin, s.offX, s.offY, s.anchor, s.aOffX, s.aOffY, s.anchorSetsZ)

    /** Next would open the 3D probe: Custom origin picked and no zero set with it yet. */
    fun probeNeeded() = step.value == 0 && setup.value.origin == Origin.PROBE3D && !probed3d.value

    /** The 3D probe wizard came back; [zeroSet]: it set an X/Y work zero, so go on to Checks. */
    fun probeReturned(zeroSet: Boolean) {
        if (!zeroSet) return
        probed3d.value = true; applied.value = setup.value
        error.value = null; if (step.value == 0) step.value = 1
    }

    // ---- laser probe ------------------------------------------------------------------------------
    /** What the check sheet offers first: true when a probe number is in (it may be the laser probe). */
    fun probeNumberIn(st: MachineStatus?) = st?.tool?.let { com.z1pendant.isProbeTool(it) } == true

    fun laserChoice(alreadyIn: Boolean) {
        val st = link.status.value
        ready(st)?.let { error.value = it; laserAsk.value = false; return }
        laserAsk.value = false
        laser.value = Laser(LaserPhase.CHECKING, if (alreadyIn) "Measuring it on the tool setter…" else "Tool change to the laser probe…")
        log("▶ run: laser probe ${if (alreadyIn) "check" else "swap"}")
        probe.loadLaser(alreadyIn, st?.tool)
    }

    fun laserCancel() { laserAsk.value = false; afterLaser = null }

    /** "It is the laser probe": take this reading as its length. */
    fun laserRemember() {
        probe.rememberLength()
        laser.value = Laser(LaserPhase.OK, "its length is saved")
        afterLaser?.let { afterLaser = null; it() }
    }

    private fun onProbe(a: ToolAction?, c: ProbeLength.Check?) {
        if (laser.value.phase != LaserPhase.CHECKING || a == null) return
        if (a.error != null) { laser.value = Laser(LaserPhase.FAILED, a.error); afterLaser = null; return }
        if (a.stage != ToolAction.Stage.DONE) {
            laser.value = laser.value.copy(note = when (a.stage) {
                ToolAction.Stage.MOVING -> if (a.load) "Going to the tool change spot…" else "Going to the tool setter…"
                ToolAction.Stage.WAITING -> "Put in the laser probe, then tap Next."
                ToolAction.Stage.CALIBRATING -> "Measuring it on the tool setter…"
                ToolAction.Stage.CHECKING -> "Checking the probe triggers…"
                else -> laser.value.note
            })
            return
        }
        val v = laserVerdict(c)
        laser.value = v
        if (v.phase == LaserPhase.OK) proceed(afterLaser) else afterLaser = null
    }

    /** Carry on with what the check was for, once the machine is back to Idle (the setter routine ends in motion). */
    private fun proceed(f: (() -> Unit)?) {
        afterLaser = null
        if (f == null) return
        scope.launch {
            val idle = withTimeoutOrNull(15_000) {
                while (link.status.value?.isIdle != true) kotlinx.coroutines.delay(100)
                true
            }
            if (idle == true) f() else error.value = "The machine didn't come back to Idle after the probe check."
        }
    }

    /** Run [then] once the laser probe is confirmed in; asks first (the sheet) if it isn't. */
    private fun withLaser(then: () -> Unit) {
        if (laser.value.phase == LaserPhase.OK && link.status.value?.tool == 0) { then(); return }
        afterLaser = then; laserAsk.value = true
    }

    // ---- Scan now -------------------------------------------------------------------------------
    fun scanNow() {
        error.value = null
        val e = extents ?: run { error.value = "The toolpath isn't loaded yet."; return }
        ready(link.status.value)?.let { error.value = it; return }
        withLaser {
            ready(link.status.value)?.let { error.value = it; return@withLaser }
            scanSawStart = false
            scan.value = Scan(ScanPhase.RUNNING)
            log("▶ run: scan margin")
            onSend(); link.sendLine(RunCommands.scanMargin(e))
            scope.launch {
                kotlinx.coroutines.delay(4000)
                if (scan.value.phase == ScanPhase.RUNNING && !scanSawStart)
                    scan.value = Scan(ScanPhase.FAILED, "The machine didn't start the scan. Is it homed?")
            }
        }
    }

    fun stopScan() { link.reset() }

    // ---- Start ----------------------------------------------------------------------------------
    /** What still blocks Start, or null. */
    fun startBlock(st: MachineStatus?): String? {
        ready(st)?.let { return it }
        val l = load.value
        if (l !is Load.Ready) return if (l is Load.Failed) l.why else "Still reading the toolpath."
        if (l.extents == null) return "No cutting moves found in the file."
        if (offBed(st) == true) return "With this origin the cutting area runs past the bed."
        if (setup.value.needsLaser && l.cutsBeforeM6) return "The file cuts before its first tool change (M6): the laser probe would cut."
        return null
    }

    /**
     * What the Run page says under the cards: [startBlock], except the machine being busy (not Idle,
     * a job on). Start is off then anyway, and during the laser swap Start itself made it busy.
     */
    fun startNote(st: MachineStatus?): String? =
        if (link.conn.value == ConnState.Connected && st != null && (!st.isIdle || st.job != null)) null else startBlock(st)

    fun start() {
        error.value = null
        startBlock(link.status.value)?.let { error.value = it; return }
        val s = setup.value; val e = extents!!
        val go = {
            startBlock(link.status.value)?.let { error.value = it } ?: run {
                val file = path.value!!
                log("▶ run: start $file")
                onSend()
                link.sendLine("M370")                                   // a new job never inherits an old leveling grid
                RunCommands.startLines(s, e, probeMaxZ).forEach { link.sendLine("buffer $it\n") }   // as Studio sends it
                link.sendLine(RunCommands.play(file))
                started.value = true
                close()
            }
        }
        if (s.needsLaser) withLaser(go) else go()
    }

    // ---- machine messages -----------------------------------------------------------------------
    private val configRe = Regex("""(\S+) is set to\s+([-+]?[\d.]+)""")

    private fun onMessage(m: String) {
        configRe.find(m)?.let { r ->
            configWait[r.groupValues[1]]?.complete(r.groupValues[2].toDouble())
        }
        if (scan.value.phase == ScanPhase.RUNNING) {
            when {
                m.startsWith("Auto scan margin") || m.startsWith("M494.1") || m.startsWith("G90 G1") -> scanSawStart = true
                m.startsWith("Done ATC") || (scanSawStart && m.startsWith("M494.2")) -> scan.value = Scan(ScanPhase.DONE)
                m.contains("ALARM", true) || m.contains("Abort", true) || m.startsWith("ERROR") || m.startsWith("error") ->
                    scan.value = Scan(ScanPhase.FAILED, m.trim().take(80))
            }
        }
    }

    // ---- leveling mesh ------------------------------------------------------------------------------
    /** `M375.1`: the active leveling grid, or null. Nothing moves. */
    suspend fun readMesh(): List<List<Double>>? {
        val buf = StringBuilder()
        val done = CompletableDeferred<Unit>()
        val j = scope.launch {
            link.messages.collect { m ->
                if (m.trim() == "ok") done.complete(Unit) else buf.append(m).append('\n')
            }
        }
        try {
            kotlinx.coroutines.delay(50)
            link.sendLine("M375.1")
            withTimeoutOrNull(4000) { done.await() }
        } finally { j.cancel() }
        return Mesh.parse(buf.toString())
    }
}
