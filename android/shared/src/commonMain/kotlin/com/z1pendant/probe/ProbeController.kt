package com.z1pendant.probe

import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.platform.PLog
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.nowMs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** The probe tool number on the stock Z1 (T9999 keeps the probe powered and the motion guard on). */
const val PROBE_TOOL = 9999

/** Loading or calibrating the probe on the tool setter, followed from the lines the machine reports. */
data class ToolAction(val load: Boolean, val stage: Stage, val error: String? = null) {
    enum class Stage { MOVING, WAITING, CALIBRATING, CHECKING, DONE }
}

sealed class RunState {
    data object Idle : RunState()
    /** On step [index] of [labels] (see stepLabels); [step] is the label; [touch] = touches made in this step so far. */
    data class Running(val step: String, val index: Int = 0, val labels: List<String> = emptyList(), val setMode: Boolean = true,
                       val touch: Int = 0) : RunState()
    data class Done(val result: ProbeResult) : RunState()
    data class Failed(val reason: String, val hint: String?, val changed: String) : RunState()
}

/**
 * A finished run. Positions are work coordinates of the zero that was active before the run, so
 * "Measure only" can say "the corner is 12.346 mm right of your X0".
 */
data class ProbeResult(
    val mode: ProbeMode, val setup: ProbeSetup,
    val zeroX: Double?, val zeroY: Double?, val top: Double?,
    val widthX: Double?, val widthY: Double?,
    /** Axes whose work zero the run changed (Set work zero). */
    val zeroSet: Set<Char>,
    /** Machine positions of the measured points (Measure only: what Set uses). */
    val machine: Measured,
    /** Work offsets (machine − work) before the run, for Undo. */
    val before: List<Double>,
    /** Edge, multiple points: the line through the touches. */
    val fit: EdgeFit? = null,
)

/**
 * The single choices on the Setup screen the user has made since probing was opened. Nothing is
 * picked when it opens (the values behind them are still remembered, for the defaults), so each one
 * is a deliberate tap; Next waits for all the ones the operation needs.
 */
enum class Pick { OPERATION, CORNER, SIDE, START, POINTS, DIRECTION }

/** What the Setup screen still needs picked, as the Next button says it (null = ready). */
fun ProbeSetup.missingPick(picks: Set<Pick>): String? = when {
    Pick.OPERATION !in picks -> "Pick an operation"
    kind.hasCorner && Pick.CORNER !in picks -> "Pick a corner"
    kind == ProbeKind.EDGE && Pick.SIDE !in picks -> "Pick an edge"
    kind == ProbeKind.EDGE && Pick.START !in picks -> "Pick where it starts"
    kind == ProbeKind.EDGE && Pick.POINTS !in picks -> "Pick the touches"
    multi && Pick.DIRECTION !in picks -> "Pick which way"
    else -> null
}

/**
 * The 3D probe: its setup (remembered), whether the probe is in and calibrated this session, and
 * runs in both modes. Calibration is only trusted when the pendant saw it happen (it ran it, or saw
 * the machine's echoed setter lines while another app ran it) since the probe went in: the machine
 * keeps a probe length, but not when it was taken (on 2026-11-15 it was 7 mm stale).
 */
class ProbeController(
    private val link: MachineLink,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    /** Something that may move the machine was sent (own-motion tracking). */
    private val onSend: () -> Unit = {},
    /** Jogging off while a run moves the machine. */
    private val setJogEnabled: (Boolean) -> Unit = {},
) {
    val setup = MutableStateFlow(loadSetup())
    val mode = MutableStateFlow(ProbeMode.SET_ZERO)
    /** Setup choices made since probing was opened (see [Pick]); cleared when it closes. */
    val picks = MutableStateFlow<Set<Pick>>(emptySet())
    fun pick(p: Pick) { picks.value = picks.value + p }
    fun unpick(p: Pick) { picks.value = picks.value - p }
    /** What a run does: multiple points only ever measure. */
    fun effectiveMode(s: ProbeSetup = setup.value) = if (s.multi) ProbeMode.MEASURE else mode.value
    /** The machine says T9999 and you said the probe really is in (the number can be left over). */
    val confirmed = MutableStateFlow(false)
    val calibrated = MutableStateFlow(false)
    val toolAction = MutableStateFlow<ToolAction?>(null)
    val run = MutableStateFlow<RunState>(RunState.Idle)
    /** Which probe the last calibration's setter touch says is in, against the one picked (null = no calibration seen). */
    val lengthCheck = MutableStateFlow<ProbeLength.Check?>(null)
    /**
     * Check calibrations against this probe instead of the wizard's pick. The Run flow sets it to
     * the laser probe (the margin scan, Z probe and leveling need it in, as tool 0).
     */
    @kotlin.concurrent.Volatile var checkAs: ProbeTool? = null
    /**
     * Where the tool setter is (machine X, Y), from the last calibration's setter touch: the bed map
     * draws it. Null until a calibration has been seen on this phone.
     */
    val setterXY = MutableStateFlow(prefs.getString("probe.setterXY", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
        ?.takeIf { it.size == 2 }?.let { it[0] to it[1] })

    private var runJob: Job? = null
    private val runLines = Channel<String>(Channel.UNLIMITED)
    @kotlin.concurrent.Volatile private var listening = false
    private var sawSetter = false
    /** The slow setter touch of the calibration in progress (the touch after `G38.6 … P1`). */
    private var slowTouchNext = false
    private var setterZ: Double? = null
    private var lastSetterZ: Double? = null
    /**
     * When the last calibration finished, and whether a status has said a probe is in since. The
     * status that crosses "Done ATC" can still carry the old tool number (−1 mid-change, or the
     * cutter); taken as "the probe came out" it wiped the calibration (2026-11-19 on the Z1).
     */
    private var calDoneAt: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
    private var probeSeenSinceCal = false

    init {
        scope.launch { link.messages.collect { onMessage(it) } }
        scope.launch {
            link.status.collect { st ->
                val tool = st?.tool
                val busy = toolAction.value?.let { it.stage != ToolAction.Stage.DONE && it.error == null } == true
                // The probe came out: a tool change or Set tool to anything else. (During a tool
                // change the number reads −1 for a moment; that's the change we're watching.)
                if (tool != null && com.z1pendant.isProbeTool(tool)) probeSeenSinceCal = true
                // A stale number just after the calibration isn't the probe coming out: give the
                // machine 5 s to report the probe before believing it.
                val settling = !probeSeenSinceCal && calDoneAt?.let { it.elapsedNow().inWholeMilliseconds < 5_000 } == true
                if (tool != null && !com.z1pendant.isProbeTool(tool) && !busy && !settling) {
                    if (calibrated.value) PLog.i("probe", "probe out: the status says T$tool")
                    probeOut()
                }
                if (st?.state.equals("Tool", true)) toolAction.value?.let { a ->
                    if (a.error == null && a.stage == ToolAction.Stage.MOVING) toolAction.value = a.copy(stage = ToolAction.Stage.WAITING)
                }
            }
        }
        scope.launch {
            link.conn.collect { c ->
                if (c != ConnState.Connected) {
                    probeOut()
                    toolAction.value?.let { if (it.stage != ToolAction.Stage.DONE) toolAction.value = null }
                }
            }
        }
    }

    // ---- setup ------------------------------------------------------------------------------
    fun update(s: ProbeSetup) {
        val toolChanged = s.tool != setup.value.tool
        setup.value = s
        if (toolChanged && checkAs == null) lastSetterZ?.let { lengthCheck.value = ProbeLength.check(s.tool, it, learned()) }
        prefs.edit {
            putString("probe.kind", s.kind.name); putString("probe.corner", s.corner.name)
            putBoolean("probe.axisX", s.axisX); putBoolean("probe.axisY", s.axisY); putBoolean("probe.axisZ", s.axisZ)
            putString("probe.tool", s.tool.name)
            putFloat("probe.x", s.x.toFloat()); putFloat("probe.y", s.y.toFloat())
            putFloat("probe.depth", s.depth.toFloat()); putFloat("probe.tip", s.tip.toFloat())
            putString("probe.edge", s.edge.name); putBoolean("probe.edgeFromHere", s.edgeFromHere)
            putBoolean("probe.edgeMulti", s.edgeMulti); putInt("probe.edgeCount", s.edgeCount)
            putFloat("probe.edgeLength", s.edgeLength.toFloat()); putInt("probe.alongSign", s.alongSign)
        }
    }

    private fun loadSetup(): ProbeSetup {
        fun <T : Enum<T>> e(key: String, values: Array<T>, def: T) = values.firstOrNull { it.name == prefs.getString(key, null) } ?: def
        val d = ProbeSetup()
        val tool = e("probe.tool", ProbeTool.entries.toTypedArray(), d.tool)
        return ProbeSetup(
            kind = if (tool.topOnly) ProbeKind.TOP else e("probe.kind", ProbeKind.entries.toTypedArray(), d.kind),
            corner = e("probe.corner", Corner.entries.toTypedArray(), d.corner),
            // Before the per-axis toggles (0.1.10) a center had X and Y / X only / Y only.
            axisX = prefs.getBoolean("probe.axisX", prefs.getString("probe.axes", null) != "Y"),
            axisY = prefs.getBoolean("probe.axisY", prefs.getString("probe.axes", null) != "X"),
            axisZ = prefs.getBoolean("probe.axisZ", true),
            tool = tool,
            x = prefs.getFloat("probe.x", d.x.toFloat()).toDouble(), y = prefs.getFloat("probe.y", d.y.toFloat()).toDouble(),
            depth = prefs.getFloat("probe.depth", d.depth.toFloat()).toDouble(), tip = prefs.getFloat("probe.tip", d.tip.toFloat()).toDouble(),
            edge = e("probe.edge", Side.entries.toTypedArray(), d.edge),
            edgeFromHere = prefs.getBoolean("probe.edgeFromHere", d.edgeFromHere),
            edgeMulti = prefs.getBoolean("probe.edgeMulti", d.edgeMulti),
            edgeCount = prefs.getInt("probe.edgeCount", d.edgeCount).coerceIn(ProbeSetup.MIN_POINTS, ProbeSetup.MAX_POINTS),
            edgeLength = prefs.getFloat("probe.edgeLength", d.edgeLength.toFloat()).toDouble(),
            alongSign = if (prefs.getInt("probe.alongSign", d.alongSign) < 0) -1 else 1,
        ).normalized()
    }

    // ---- probe in the spindle, calibrated ---------------------------------------------------
    /** Tool change to the probe (`M6 T9999`): swap, then the firmware calibrates it on the setter and checks it triggers. */
    fun loadProbe() {
        toolAction.value = ToolAction(load = true, stage = ToolAction.Stage.MOVING); sawSetter = false
        recalibrating()
        onSend(); link.sendLine("M6 T$PROBE_TOOL")
    }

    /**
     * Swap probes while the machine already says T9999 (`M6 T9999` would do nothing then): mark the
     * spindle empty first, then the normal load.
     */
    fun reloadProbe() { link.sendLine("M493.2 T-1"); loadProbe() }

    /** "I've swapped it": the same as a short press of the machine's button. */
    fun continueToolChange() = link.sendLine("M490.2")

    /** Calibrate the probe that's in (`M491`): the setter routine, with the probe check because the tool is T9999. */
    fun calibrate() {
        toolAction.value = ToolAction(load = false, stage = ToolAction.Stage.MOVING); sawSetter = false
        recalibrating()
        onSend(); link.sendLine("M491")
    }

    fun dismissToolError() { toolAction.value = null }

    /**
     * The laser probe as tool 0, which the firmware's margin scan insists on. [alreadyIn] false:
     * tool change (`M6 T0`: swap, then the setter calibration). true: it's in, the number may be
     * wrong: set T0 without motion, then calibrate (`M493.2 T0` marks the length unmeasured, so the
     * order matters). Either way the setter touch says which probe it really is.
     */
    fun loadLaser(alreadyIn: Boolean, currentTool: Int?) {
        checkAs = ProbeTool.LASER
        toolAction.value = ToolAction(load = !alreadyIn, stage = ToolAction.Stage.MOVING); sawSetter = false
        recalibrating()
        onSend()
        if (alreadyIn) { link.sendLine("M493.2 T0"); link.sendLine("M491") }
        else {
            if (currentTool == 0) link.sendLine("M493.2 T-1")     // M6 T0 does nothing while it already says T0
            link.sendLine("M6 T0")
        }
    }

    private fun probeOut() {
        confirmed.value = false; calibrated.value = false
        lastSetterZ = null; lengthCheck.value = null
    }

    /** A calibration started: the old one (and its probe check) no longer counts until this one finishes. */
    private fun recalibrating() {
        calibrated.value = false; lastSetterZ = null; lengthCheck.value = null
    }

    /**
     * Probing was opened: it calibrates again before anything runs. The calibration is the dead-probe
     * test (the probe has to fire on the setter), says which probe is in by where it trips the
     * setter, and takes a fresh probe length. A probe can be swapped by hand without the machine
     * knowing (it still says T9999), so an earlier calibration doesn't count.
     */
    fun openWizard() {
        checkAs = null
        // Corner / hole / block axes start all on each time (they used to be remembered, and an X left
        // off from some earlier run came back unnoticed: Jimmy, 2026-11-27).
        setup.value.let { if (!(it.axisX && it.axisY && it.axisZ)) update(it.copy(axisX = true, axisY = true, axisZ = true)) }
        if (toolAction.value?.let { it.stage != ToolAction.Stage.DONE && it.error == null } != true) recalibrating()
    }

    /** Probing closed: the next open starts with nothing picked. */
    fun closeWizard() { picks.value = emptySet(); reset() }

    // ---- which probe is in ----------------------------------------------------------------------
    /** Each probe's learned setter Z (machine coordinates), from earlier calibrations. */
    fun learned(): Map<ProbeTool, Double> = ProbeTool.entries.mapNotNull { t ->
        prefs.getString("probe.len.${t.name}", null)?.toDoubleOrNull()?.let { t to it }
    }.toMap()

    private fun saveLength(t: ProbeTool, z: Double) = prefs.edit { putString("probe.len.${t.name}", f3(z)) }

    /** A calibration just finished at setter Z [z]: check it's the probe you picked; a probe's first one is learned. */
    private fun checkLength(z: Double) {
        lastSetterZ = z
        val tool = checkAs ?: setup.value.tool
        var c = ProbeLength.check(tool, z, learned())
        if (c.result == ProbeLength.Result.NEW) { saveLength(tool, z); c = c.copy(result = ProbeLength.Result.LEARNED) }
        lengthCheck.value = c
        PLog.i("probe", "setter Z ${f3(z)} for ${tool.name}: ${c.result}${c.other?.let { " (${it.name})" } ?: ""}")
    }

    /** "It is the probe I picked": take this reading as its length from now on. */
    fun rememberLength() {
        val c = lengthCheck.value ?: return
        saveLength(c.tool, c.z)
        lengthCheck.value = c.copy(result = ProbeLength.Result.LEARNED, expected = c.z, other = null)
    }

    /** "It's the other probe": pick the probe the reading matches. */
    fun switchToMatch() { lengthCheck.value?.other?.let { update(setup.value.withTool(it)) } }

    private fun onMessage(line: String) {
        if (listening) runLines.trySend(line)
        val a = toolAction.value
        when {
            line.startsWith("Please change the tool to: T$PROBE_TOOL") ->
                if (a == null || a.stage == ToolAction.Stage.DONE || a.error != null) { toolAction.value = ToolAction(true, ToolAction.Stage.MOVING); sawSetter = false }
            line.startsWith("G38.6") && line.contains("P1") -> {      // the probe on the setter (Studio's or ours)
                sawSetter = true; slowTouchNext = true; setterZ = null
                recalibrating()
                toolAction.value = (a?.takeIf { it.error == null && it.stage != ToolAction.Stage.DONE } ?: ToolAction(false, ToolAction.Stage.MOVING))
                    .copy(stage = ToolAction.Stage.CALIBRATING)
            }
            slowTouchNext && line.startsWith("[PRB:") -> {
                slowTouchNext = false
                parsePrb(line)?.takeIf { it.second }?.let { (t, _) ->
                    setterZ = t.z
                    if (setterXY.value != t.x to t.y) { setterXY.value = t.x to t.y; prefs.edit { putString("probe.setterXY", "${f3(t.x)},${f3(t.y)}") } }
                }
            }
            line.startsWith("M492.3") -> a?.let { toolAction.value = it.copy(stage = ToolAction.Stage.CHECKING) }
            line.startsWith("Done ATC") && sawSetter && a != null && a.error == null -> {
                sawSetter = false
                calibrated.value = true; confirmed.value = true
                calDoneAt = kotlin.time.TimeSource.Monotonic.markNow()
                probeSeenSinceCal = link.status.value?.tool?.let { com.z1pendant.isProbeTool(it) } == true
                setterZ?.let(::checkLength); setterZ = null
                toolAction.value = a.copy(stage = ToolAction.Stage.DONE)
            }
            a != null && a.error == null && a.stage != ToolAction.Stage.DONE && isStop(line) ->
                toolAction.value = a.copy(error = friendly(line))
        }
    }

    // ---- runs ---------------------------------------------------------------------------------
    /** Machine − work, per axis: the active work offset including the tool length offset on Z. */
    private fun offsets(st: MachineStatus) = st.mpos.indices.map { st.mpos[it] - (st.wpos.getOrNull(it) ?: 0.0) }

    fun start() {
        if (runJob?.isActive == true) return
        val st = link.status.value
        if (link.conn.value != ConnState.Connected || st == null || !st.isIdle) {
            run.value = RunState.Failed("The machine isn't ready (${st?.state ?: "not connected"}).", null, "Nothing changed."); return
        }
        val s = setup.value; val m = effectiveMode(s)
        s.startProblem(st.wpos.getOrNull(2))?.let { run.value = RunState.Failed(it, null, "Nothing changed."); return }
        val before = offsets(st)
        val start = Touch(st.mpos[0], st.mpos[1], st.mpos[2])
        while (runLines.tryReceive().isSuccess) { /* drop old lines */ }
        listening = true
        setJogEnabled(false)
        runJob = scope.launch {
            try {
                val result = if (m == ProbeMode.SET_ZERO && s.makeraCommand() != null) runMakera(s, before)
                    else runOwn(s, m, start, before)
                run.value = RunState.Done(result)
            } catch (e: ProbeStop) {
                // No touch: the probe may have died (or started off the part). Calibrating tests it.
                if (e.message?.contains("didn't touch") == true) recalibrating()
                run.value = RunState.Failed(e.message ?: "Probing stopped.", hintFor(e.message), changedSince(before))
            } catch (e: kotlinx.coroutines.CancellationException) {
                run.value = RunState.Failed("Stopped.", null, changedSince(before)); throw e
            } finally {
                listening = false; setJogEnabled(true)
            }
        }
    }

    /** Stop button: abort now (Ctrl-X). The machine needs Unlock afterwards. */
    fun stop() { link.reset(); runJob?.cancel() }

    fun reset() { if (runJob?.isActive != true) run.value = RunState.Idle }

    /** Makera's own routine: one command; it touches and writes the zero. Wait for "Done ATC". */
    private suspend fun runMakera(s: ProbeSetup, before: List<Double>): ProbeResult {
        val cmd = s.makeraCommand()!!
        val labels = s.stepLabels()
        var idx = 0
        val touches = mutableListOf<Touch>()
        fun show(i: Int) {
            idx = maxOf(idx, i)
            run.value = RunState.Running(labels[idx], idx, labels, true, s.touchesInStep(idx, touches.size))
        }
        show(0)
        onSend(); link.sendLine(cmd)
        val t0 = nowMs()
        var moved = false
        while (true) {
            val line = withTimeoutOrNull(250) { runLines.receive() }
            val st = link.status.value
            if (link.conn.value != ConnState.Connected) throw ProbeStop("The link to the machine dropped.")
            if (st != null && !st.isIdle) moved = true
            if (line != null) {
                parsePrb(line)?.let { (t, ok) ->
                    if (!ok) throw ProbeStop("The probe didn't touch anything.")
                    touches += t; show(s.stepAfterTouches(touches.size))
                }
                stepFromEcho(line)?.let { l -> labels.indexOf(l).takeIf { it >= 0 }?.let { show(it) } }
                if (line.startsWith("Done ATC")) break
                if (line.startsWith("Abort")) throw ProbeStop("Probing was aborted.")
                if (isStop(line)) throw ProbeStop(friendly(line))
            }
            if (st?.isAlarm == true) throw ProbeStop("The machine stopped (Alarm).")
            // Makera's routine silently does nothing when the machine isn't homed.
            if (!moved && touches.isEmpty() && nowMs() - t0 > 4000)
                throw ProbeStop("Nothing happened. Makera's routine needs the machine homed.")
        }
        val after = freshOffsets()
        // The axes Makera's routine writes (even when the new zero lands where the old one was).
        val changed = buildSet {
            if (s.doX) add('X'); if (s.doY) add('Y'); if (s.doZ) add('Z')
        }
        val (wx, wy) = widthsFromTouches(s.kind, s, touches)
        // The new zero, in the old zero's work coordinates: machine position of the new 0 = new offset.
        return ProbeResult(ProbeMode.SET_ZERO, s,
            zeroX = if ('X' in changed) after[0] - before[0] else null,
            zeroY = if ('Y' in changed) after[1] - before[1] else null,
            top = if ('Z' in changed) after[2] - before[2] else null,
            widthX = wx, widthY = wy, zeroSet = changed, machine = Measured(), before = before)
    }

    /**
     * The pendant's own touches: Measure only, and Set work zero for what Makera has no routine for
     * (Top only, a corner without all three axes, a block center without Z). Z0 is written at the
     * top touch (like Makera's routines); X0 / Y0 once it's over the point found.
     */
    private suspend fun runOwn(s: ProbeSetup, m: ProbeMode, start: Touch, before: List<Double>): ProbeResult {
        val io = LinkIo(s.stepLabels(), m == ProbeMode.SET_ZERO)
        io.step(s.stepLabels().first())
        val meas = ProbeSequences(io, s, start, setZero = m == ProbeMode.SET_ZERO, zeroZ = before.getOrNull(2)).run()
        val st = idleStatus()                     // the last move (to the corner / center) is still running
        val set = mutableSetOf<Char>()
        if (m == ProbeMode.SET_ZERO) {
            if (meas.top != null) set += 'Z'
            val v = buildMap {
                if (st != null) {
                    meas.zeroX?.let { put('X', st.mpos[0] - it) }
                    meas.zeroY?.let { put('Y', st.mpos[1] - it) }
                }
            }
            if (v.isNotEmpty()) {
                v.keys.forEach { set += it }
                link.dialect.setWorkPosition(v).forEach { io.send(it) }
            }
        }
        val fit = if (s.multi && meas.edgeTouches.size >= 2)
            EdgeFit(meas.edgeAlong.map { (it - meas.edgeAlong[0]) * s.alongSign }, meas.edgeTouches, outSign = -s.edge.dir) else null
        return ProbeResult(m, s,
            zeroX = meas.zeroX?.let { it - before[0] }, zeroY = meas.zeroY?.let { it - before[1] },
            top = meas.top?.let { it - before[2] }, widthX = meas.widthX, widthY = meas.widthY,
            zeroSet = set, machine = meas, before = before, fit = fit)
    }

    /** The link as the sequences need it: one line at a time, each waiting for its answer. */
    private inner class LinkIo(val labels: List<String>, val setMode: Boolean) : ProbeIo {
        private var step = labels.firstOrNull() ?: ""
        private var touched = 0
        private fun publish() { run.value = RunState.Running(step, labels.indexOf(step).coerceAtLeast(0), labels, setMode, touched) }
        override fun step(text: String) { step = text; touched = 0; publish() }

        override suspend fun send(line: String) {
            checkMachine()
            val ack = CompletableDeferred<Pair<Boolean, String>>()
            onSend(); link.sendLine(line) { ok, reply -> ack.complete(ok to reply) }
            val (ok, reply) = withTimeoutOrNull(15_000) { ack.await() } ?: throw ProbeStop("The machine didn't answer \"$line\".")
            if (!ok) throw ProbeStop(if (reply.isBlank()) "The machine refused \"$line\"." else friendly(reply))
            drainStops()
        }

        override suspend fun probe(line: String): Touch {
            checkMachine()
            val ack = CompletableDeferred<Boolean>()
            onSend(); link.sendLine(line) { ok, _ -> ack.complete(ok) }
            val t0 = nowMs()
            while (true) {
                val l = withTimeoutOrNull(250) { runLines.receive() }
                if (l != null) {
                    parsePrb(l)?.let { (t, ok) ->
                        if (!ok) throw ProbeStop("The probe didn't touch anything.")
                        withTimeoutOrNull(5_000) { ack.await() }
                        touched++; publish()
                        return t
                    }
                    if (isStop(l)) throw ProbeStop(friendly(l))
                }
                checkMachine()
                if (nowMs() - t0 > 180_000) throw ProbeStop("No touch reported in 3 minutes.")
            }
        }

        private fun drainStops() {
            while (true) {
                val l = runLines.tryReceive().getOrNull() ?: return
                if (isStop(l)) throw ProbeStop(friendly(l))
                if (parsePrb(l)?.second == false) throw ProbeStop("The probe didn't touch anything.")
            }
        }

        private fun checkMachine() {
            if (link.conn.value != ConnState.Connected) throw ProbeStop("The link to the machine dropped.")
            if (link.status.value?.isAlarm == true) throw ProbeStop("The machine stopped (Alarm).")
        }
    }

    /** Offsets from a status that arrived after now (the zero was just written). */
    private suspend fun freshOffsets(): List<Double> = idleStatus()?.let { offsets(it) } ?: emptyList()

    /**
     * The first Idle status that arrived after now, once nothing of ours is waiting for an answer:
     * the machine acks a move when it's queued, so the last one may still be running.
     */
    private suspend fun idleStatus(timeoutMs: Long = 60_000): MachineStatus? {
        val since = nowMs()
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val s = link.status.value
                if (s != null && s.atMs > since + 150 && s.isIdle && link.pendingLines == 0) return@withTimeoutOrNull s
                if (s?.isAlarm == true) return@withTimeoutOrNull s
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: link.status.value
    }

    /** "What changed" after a stop, from the work offsets now vs before the run. */
    private fun changedSince(before: List<Double>): String {
        val st = link.status.value ?: return "Unknown: no status from the machine."
        val now = offsets(st)
        val ch = "XYZ".filterIndexed { i, _ -> i < now.size && i < before.size && abs(now[i] - before[i]) > 0.0005 }
        return when {
            ch.isEmpty() -> "Your work zero wasn't changed."
            else -> ch.toList().joinToString(" and ") + "0 " + (if (ch.length == 1) "was" else "were") + " set; " +
                "XYZ".filter { it !in ch }.toList().joinToString(" and ").ifEmpty { "nothing else" } + " unchanged."
        }
    }

    // ---- after a run --------------------------------------------------------------------------
    /** Undo for "Set work zero": put the old X and Y zero back (Z0 is tied to the tool that set it). */
    fun undoXY(r: ProbeResult) = scope.launch {
        val st = idleStatus(10_000) ?: return@launch
        val v = buildMap {
            if ('X' in r.zeroSet) put('X', st.mpos[0] - r.before[0])
            if ('Y' in r.zeroSet) put('Y', st.mpos[1] - r.before[1])
        }
        if (v.isNotEmpty()) link.dialect.setWorkPosition(v).forEach { link.sendLine(it) }
    }

    /** "Set as work zero" for a Measure-only result: make the measured point(s) read 0 on [axes]. */
    fun setZero(r: ProbeResult, axes: Set<Char>) = scope.launch {
        val st = idleStatus(10_000) ?: return@launch
        val m = r.machine
        val v = buildMap {
            if ('X' in axes) m.zeroX?.let { put('X', st.mpos[0] - it) }
            if ('Y' in axes) m.zeroY?.let { put('Y', st.mpos[1] - it) }
            if ('Z' in axes) m.top?.let { put('Z', st.mpos[2] - it) }
        }
        if (v.isNotEmpty()) link.dialect.setWorkPosition(v).forEach { link.sendLine(it) }
    }

    companion object {
        fun isStop(line: String): Boolean {
            val l = line.lowercase()
            return l.startsWith("alarm") || l.startsWith("error") || "probe dead" in l || "halt" in l && "reset" in l
        }

        fun friendly(line: String): String {
            val l = line.lowercase()
            return when {
                "unexpected probe trigger" in l -> "The probe touched something on the way."
                "probe fail" in l -> "The probe didn't touch anything."
                "probe dead" in l -> "The probe didn't trigger on the setter. Check its cable."
                "calibrate fail" in l -> "Calibrating failed: the tool setter didn't trigger."
                "triggered before move" in l -> "The probe already read touched before it moved."
                else -> line.removePrefix("ALARM:").removePrefix("Error:").trim()
            }
        }

        fun hintFor(reason: String?): String? = when {
            reason == null -> null
            "on the way" in reason -> "The start offset didn't clear the edge, or the probe started too low. Raise X / Y start offset, or start higher."
            "didn't touch" in reason -> "The probe started off the part, or it isn't triggering: check its cable, then Calibrate again (it checks the probe)."
            "homed" in reason -> "Home the machine, then try again."
            else -> null
        }

        /** Makera's corner routines echo their lines: name the part of the run each one starts. */
        private fun stepFromEcho(line: String): String? = when {
            line.startsWith("G91 G0 X") && "Y" !in line -> Steps.X_SIDE
            line.startsWith("G91 G0 Y") -> Steps.Y_SIDE
            line.startsWith("G90 G0 X0") -> Steps.CORNER
            else -> null
        }
    }
}
