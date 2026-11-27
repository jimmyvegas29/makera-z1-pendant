package com.z1pendant.map

import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.link.Bed
import com.z1pendant.util.Fmt
import kotlin.math.hypot

/**
 * The bed map ("Go to", field_test/bed_map_spec.md, agreed 2026-11-27): a square map of the
 * machine's X/Y travel with a grid of points, your saved anchors and X0 Y0. Tap one to pick it, then
 * Move here: Z goes all the way up first, then X/Y. Everything is machine coordinates.
 */
enum class MapGrid(val n: Int) {
    G5(5), G9(9), G17(17);
    val label get() = "$n × $n"
}

enum class TargetKind { GRID, ANCHOR, ZERO }

/** One name at a spot on the map: an anchor's name, "X0 Y0", or a grid point ("Grid C4"). */
data class MapName(val kind: TargetKind, val name: String)

/**
 * Somewhere the map can send the spindle: one spot with every name on it. An anchor saved where X0 Y0
 * is (or on a grid point) is one target with both names, so the map draws both marks nested and the
 * card lists both (field_test/bed_map_overlap mockup, option A).
 */
data class MapTarget(val x: Double, val y: Double, val names: List<MapName>) {
    constructor(kind: TargetKind, label: String, x: Double, y: Double) : this(x, y, listOf(MapName(kind, label)))
    fun has(k: TargetKind) = names.any { it.kind == k }
    /** The mark that matters most here: an anchor, else X0 Y0, else a grid point. */
    val kind get() = when { has(TargetKind.ANCHOR) -> TargetKind.ANCHOR; has(TargetKind.ZERO) -> TargetKind.ZERO; else -> TargetKind.GRID }
    /** Every name, anchors first ("Vise jaw · X0 Y0"). */
    val label get() = names.joinToString(" · ") { it.name }
}

object BedMap {
    /** Machine Z the spindle goes up to before moving X/Y: the same as the Z up hotkey. */
    val SAFE_Z: Double = Hotkey.SAFE_Z.toDouble()

    /**
     * The grid points, [grid].n × n spread edge to edge over [bed], each named by its column letter
     * (A = left) and row number (1 = front), like a chess board seen from the front of the machine.
     */
    fun gridPoints(bed: Bed, grid: MapGrid): List<MapTarget> {
        val n = grid.n
        return buildList {
            for (j in 0 until n) for (i in 0 until n) {
                val x = bed.x0 + i * bed.width / (n - 1)
                val y = bed.y0 + j * bed.height / (n - 1)
                add(MapTarget(TargetKind.GRID, "Grid ${'A' + i}${j + 1}", x, y))
            }
        }
    }

    /** Closer than this (mm) is the same spot. */
    const val SAME = 0.01

    /**
     * Every target: the grid, saved anchors that have both X and Y and lie on the bed, and X0 Y0
     * ([zero], machine; null when there's no status yet). Ones on the same spot are merged into one
     * target carrying all their names (anchors first, then X0 Y0, then the grid point).
     */
    fun targets(bed: Bed, grid: MapGrid, anchors: List<com.z1pendant.run.Anchor>, zero: Pair<Double, Double>?): List<MapTarget> {
        val raw = gridPoints(bed, grid) +
            anchors.filter { it.x != null && it.y != null && onBed(bed, it.x, it.y) }.map { MapTarget(TargetKind.ANCHOR, it.name, it.x!!, it.y!!) } +
            listOfNotNull(zero?.takeIf { onBed(bed, it.first, it.second) }?.let { MapTarget(TargetKind.ZERO, "X0 Y0", it.first, it.second) })
        val out = mutableListOf<MapTarget>()
        for (t in raw) {
            val i = out.indexOfFirst { kotlin.math.abs(it.x - t.x) < SAME && kotlin.math.abs(it.y - t.y) < SAME }
            if (i < 0) out += t
            else out[i] = out[i].copy(names = (out[i].names + t.names).sortedBy { it.kind.ordinal.let { o -> if (o == 0) 3 else o } })
        }
        return out
    }

    fun onBed(bed: Bed, x: Double, y: Double) = x in bed.x0..bed.x1 && y in bed.y0..bed.y1

    /**
     * The target nearest a tap at machine ([x], [y]), within [reach] mm (null = none close enough).
     * Anchors and X0 Y0 win a tie with a grid point, since you'd aim for them on purpose.
     */
    fun nearest(targets: List<MapTarget>, x: Double, y: Double, reach: Double): MapTarget? =
        targets.map { it to hypot(it.x - x, it.y - y) - if (it.kind == TargetKind.GRID) 0.0 else 0.5 }
            .filter { it.second <= reach }.minByOrNull { it.second }?.first

    /**
     * Absolute in machine coordinates: with [safeZ], Z all the way up first, then X/Y; without, X/Y
     * only, at the height Z is at (sweeping a part with an indicator).
     */
    fun moveLines(t: MapTarget, safeZ: Boolean = true): List<String> =
        listOfNotNull("G90", "G53 G0 Z${Fmt.fixed(SAFE_Z, 3)}".takeIf { safeZ }, "G53 G0 X${Fmt.fixed(t.x, 3)} Y${Fmt.fixed(t.y, 3)}")
}

/** A move from the map: where to, and how far along it is. */
data class MapMove(val target: MapTarget, val stage: Stage, val error: String? = null, val safeZ: Boolean = true) {
    enum class Stage { LIFT, MOVE, DONE }
}
