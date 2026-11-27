package com.z1pendant.run

import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineTarget
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.epochSeconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Custom anchors (mockups: scratchpad custom_anchors_mockup.html, agreed 2026-11-24): named points in
 * machine coordinates (a vise jaw, a fixture plate, a measured bracket corner) that Run can use as the
 * job's origin, the way it uses Anchor 1. They live in the phone, per machine; the Z1's own config
 * (and so the tool setter, which hangs off Anchor 1) is never touched.
 *
 * Each axis is optional: an edge gives only X or only Y. [z] is the G54 Z offset itself (what `$#`
 * reports), so it holds with any tool in, as long as tool lengths are measured.
 */
data class Anchor(
    val id: String,
    val name: String,
    val x: Double?,
    val y: Double?,
    val z: Double? = null,
    /** How it was set: "3D probe", "Spindle position", "Typed in". */
    val how: String = "",
    /** Unix seconds. */
    val savedAt: Long = 0,
) {
    val hasXY get() = x != null || y != null
}

object AnchorJson {
    fun encode(list: List<Anchor>): String = buildJsonArray {
        for (a in list) add(buildJsonObject {
            put("id", a.id); put("name", a.name)
            a.x?.let { put("x", it) }; a.y?.let { put("y", it) }; a.z?.let { put("z", it) }
            put("how", a.how); put("savedAt", a.savedAt)
        })
    }.toString()

    fun decode(s: String?): List<Anchor> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(s) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            fun num(k: String) = str(k)?.toDoubleOrNull()
            val id = str("id") ?: return@mapNotNull null
            Anchor(id, str("name") ?: "Anchor", num("x"), num("y"), num("z"), str("how") ?: "", str("savedAt")?.toLongOrNull() ?: 0)
        }
    }
}

/** `$#` reply line `[G54:-150.2500,-110.6000,-82.4000,0.0000,0.0000]`: the work offset in machine coordinates. */
object WorkOffset {
    private val g54 = Regex("""\[G54:\s*([-+]?[\d.]+),\s*([-+]?[\d.]+),\s*([-+]?[\d.]+)""")
    fun parse(line: String): Triple<Double, Double, Double>? = g54.find(line)?.let { m ->
        val (x, y, z) = m.destructured
        Triple(x.toDoubleOrNull() ?: return null, y.toDoubleOrNull() ?: return null, z.toDoubleOrNull() ?: return null)
    }
}

/**
 * The saved anchors for the connected machine, plus the Settings › Anchors editor's state, which has
 * to live here because the probe wizard is its own screen: New / Re-probe open it, and when it closes
 * [probeReturned] reads the work zero it set (`$#`) into the editor or the comparison.
 */
class AnchorsController(
    private val link: MachineLink,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val machine: StateFlow<MachineTarget?>,
    private val log: (String) -> Unit = {},
) {
    /** What the editor is filling in (null = the list is shown). [id] null = a new anchor. */
    data class Draft(val id: String?, val name: String, val x: Double?, val y: Double?, val z: Double?, val how: String)

    /** Re-probe result: the saved anchor next to what was just measured. Nothing is saved until a button is tapped. */
    data class Compare(val anchor: Anchor, val x: Double?, val y: Double?, val z: Double?) {
        /** The largest change on an axis both have, mm. */
        val maxChange: Double get() = listOfNotNull(
            if (anchor.x != null && x != null) kotlin.math.abs(x - anchor.x) else null,
            if (anchor.y != null && y != null) kotlin.math.abs(y - anchor.y) else null,
            if (anchor.z != null && z != null) kotlin.math.abs(z - anchor.z) else null).maxOrNull() ?: 0.0
    }

    private sealed class ProbeFor { object New : ProbeFor(); data class Again(val id: String) : ProbeFor() }

    val list = MutableStateFlow<List<Anchor>>(emptyList())
    val draft = MutableStateFlow<Draft?>(null)
    val compare = MutableStateFlow<Compare?>(null)
    /** Reading the work zero after a probe. */
    val reading = MutableStateFlow(false)
    /** A problem to show on the Anchors screen. */
    val note = MutableStateFlow<String?>(null)
    private var probeFor: ProbeFor? = null
    private var key: String? = null

    init {
        scope.launch { machine.collect { t -> load(t?.name) } }
    }

    private fun load(name: String?) {
        key = name?.takeIf { it.isNotBlank() }?.let { "anchors.$it" }
        list.value = AnchorJson.decode(key?.let { prefs.getString(it, null) })
    }

    private fun store(l: List<Anchor>) {
        list.value = l
        key?.let { k -> prefs.edit { putString(k, AnchorJson.encode(l)) } }
    }

    fun byId(id: String?) = id?.let { i -> list.value.firstOrNull { it.id == i } }

    private var counter = 0
    private fun newId() = "a${epochSeconds()}_${counter++}"

    // ---- list ---------------------------------------------------------------------------------------------
    fun startNew() { note.value = null; draft.value = Draft(null, "", null, null, null, "Typed in") }
    fun startEdit(a: Anchor) { note.value = null; draft.value = Draft(a.id, a.name, a.x, a.y, a.z, a.how) }
    fun closeEditor() { draft.value = null; note.value = null }

    fun updateDraft(d: Draft) { draft.value = d; note.value = null }

    /** Save the editor's anchor; returns why not, or null when saved. */
    fun saveDraft(): String? {
        val d = draft.value ?: return null
        val name = d.name.trim()
        when {
            name.isEmpty() -> return "Give it a name."
            d.x == null && d.y == null -> return "It needs at least an X or a Y."
            list.value.any { it.name.equals(name, true) && it.id != d.id } -> return "There's already an anchor called $name."
        }
        val a = Anchor(d.id ?: newId(), name, d.x, d.y, d.z, d.how, epochSeconds())
        store(if (d.id == null) list.value + a else list.value.map { if (it.id == a.id) a else it })
        log("· anchor saved: $name")
        draft.value = null
        return null
    }

    fun delete(id: String) {
        store(list.value.filterNot { it.id == id })
        if (draft.value?.id == id) draft.value = null
    }

    // ---- filling in -------------------------------------------------------------------------------------------
    /** X/Y from where the spindle is now (machine coordinates); Z is left as it was. */
    fun fromSpindle(): Boolean {
        val st = link.status.value ?: run { note.value = "No position from the machine yet."; return false }
        val d = draft.value ?: return false
        draft.value = d.copy(x = st.mpos.getOrNull(0), y = st.mpos.getOrNull(1), how = "Spindle position")
        return true
    }

    /** The probe wizard is about to open for the editor's anchor. */
    fun probeForDraft() { note.value = null; probeFor = ProbeFor.New }
    /** The probe wizard is about to open to measure [a] again. */
    fun probeAgain(a: Anchor) { note.value = null; draft.value = null; probeFor = ProbeFor.Again(a.id) }

    /** The wizard closed; [axes] are the work zero axes it set. Reads them back as machine coordinates. */
    fun probeReturned(axes: Set<Char>) {
        val forWhat = probeFor ?: return
        probeFor = null
        if (axes.none { it in "XY" }) {
            if (forWhat is ProbeFor.Again) note.value = "Nothing was set, so the anchor is unchanged."
            return
        }
        reading.value = true
        scope.launch {
            val g = readWorkOffset()
            reading.value = false
            if (g == null) { note.value = "Couldn't read the work zero from the machine."; return@launch }
            val (gx, gy, gz) = g
            val x = gx.takeIf { 'X' in axes }; val y = gy.takeIf { 'Y' in axes }
            val z = gz?.takeIf { 'Z' in axes }
            if (gz == null && 'Z' in axes) note.value = "Z couldn't be read from the machine, so it isn't saved."
            when (forWhat) {
                ProbeFor.New -> draft.value?.let { d ->
                    draft.value = d.copy(x = x ?: d.x, y = y ?: d.y, z = z, how = "3D probe")
                }
                is ProbeFor.Again -> byId(forWhat.id)?.let { a -> compare.value = Compare(a, x, y, z) }
            }
        }
    }

    /**
     * The work offset (G54) in machine coordinates: `$#` (the firmware's own numbers, Z free of tool
     * length). If that gets no answer, X/Y from the status (MPos − WPos, exact on X/Y) and no Z.
     */
    suspend fun readWorkOffset(): Triple<Double, Double, Double?>? {
        if (link.conn.value != ConnState.Connected) return null
        val viaHash = withTimeoutOrNull(3000) {
            coroutineScope {
                val answer = async(start = CoroutineStart.UNDISPATCHED) { link.messages.first { WorkOffset.parse(it) != null } }
                link.sendLine("$#")
                WorkOffset.parse(answer.await())
            }
        }
        if (viaHash != null) return viaHash
        val st = link.status.value ?: return null
        val x = (st.mpos.getOrNull(0) ?: return null) - (st.wpos.getOrNull(0) ?: return null)
        val y = (st.mpos.getOrNull(1) ?: return null) - (st.wpos.getOrNull(1) ?: return null)
        return Triple(x, y, null)
    }

    /** What a probe run found, as an anchor would keep it (machine coordinates; null = not found). */
    data class Point(val x: Double?, val y: Double?, val z: Double?)

    /**
     * The point a probe run just found. When the run set the work zero ([zeroSet]), that zero read
     * back from the machine like the Anchors screen does (`$#`, Z free of tool length), on the axes it
     * set. Otherwise (Measure only) [x] / [y], machine coordinates, and no Z. Null when the machine
     * didn't answer.
     */
    suspend fun probedPoint(zeroSet: Set<Char>, x: Double?, y: Double?): Point? {
        if (zeroSet.none { it in "XY" }) return Point(x, y, null)
        val g = readWorkOffset() ?: return null
        return Point(g.first.takeIf { 'X' in zeroSet }, g.second.takeIf { 'Y' in zeroSet }, g.third?.takeIf { 'Z' in zeroSet })
    }

    /**
     * Save [p] as a new anchor called [name], keeping only the axes in [keep] (Save as anchor on the
     * probe result: all found axes are on to start, you can leave some out). Returns why it wasn't
     * saved, or null when it was.
     */
    fun saveProbed(name: String, p: Point, keep: Set<Char> = setOf('X', 'Y', 'Z')): String? {
        val n = name.trim()
        val x = p.x.takeIf { 'X' in keep }; val y = p.y.takeIf { 'Y' in keep }; val z = p.z.takeIf { 'Z' in keep }
        when {
            n.isEmpty() -> return "Give it a name."
            list.value.any { it.name.equals(n, true) } -> return "There's already an anchor called $n."
            x == null && y == null -> return "Keep at least X or Y."
        }
        store(list.value + Anchor(newId(), n, x, y, z, "3D probe", epochSeconds()))
        log("· anchor saved from probing: $n (${listOfNotNull("X".takeIf { x != null }, "Y".takeIf { y != null }, "Z".takeIf { z != null }).joinToString(" ")})")
        return null
    }

    /** [probedPoint] then [saveProbed] with every axis found. */
    suspend fun saveFromProbe(name: String, zeroSet: Set<Char>, x: Double?, y: Double?): String? {
        if (name.isBlank()) return "Give it a name."
        val p = probedPoint(zeroSet, x, y) ?: return "Couldn't read the work zero from the machine."
        return saveProbed(name, p)
    }

    // ---- re-probe result ----------------------------------------------------------------------------------------
    fun keepSaved() { compare.value = null }

    fun saveMeasured() {
        val c = compare.value ?: return
        val a = c.anchor.copy(x = c.x ?: c.anchor.x, y = c.y ?: c.anchor.y, z = c.z ?: c.anchor.z, how = "3D probe", savedAt = epochSeconds())
        store(list.value.map { if (it.id == a.id) a else it })
        compare.value = null
    }

    /** Open the editor with the measured point as a new anchor. */
    fun saveAsNew() {
        val c = compare.value ?: return
        var n = 2
        while (list.value.any { it.name.equals("${c.anchor.name} $n", true) }) n++
        draft.value = Draft(null, "${c.anchor.name} $n", c.x ?: c.anchor.x, c.y ?: c.anchor.y, c.z, "3D probe")
        compare.value = null
    }
}
