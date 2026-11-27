"""3D probing for the Z1 simulator: a part on the table, G38.2 / G38.6 touches with [PRB:] reports,
the motion guard (unexpected probe trigger), Makera's M480.1-.10 routines, M491 and the manual tool
change M6 T (with the probe calibrated on the tool setter), all after the stock firmware
(makeraz1firmware 8ae81f3, ATCHandler.cpp / ZProbe.cpp) and the logs from the real machine
(reference/m6_t9999_test_log_20261115.txt).

The part, in machine coordinates:
  * a block X -150..-90, Y -150..-110, top Z -70 (outside corners, block center, top only)
  * a pocket in it X -112..-97, Y -145..-125, floor Z -78 (inside corners, hole center)
  * the table at Z -95, the tool setter at X -9.69 Y -13.58 (the probe touches it at Z -69.637)
The probe ball is 2 mm (radius 1). `simprobe dead` / `simprobe ok` makes the probe never trigger.
`simprobe len <dz>` makes the setter trip dz mm higher (another probe's length; the laser probe is about -9).
`simprobe skew <deg>` turns the part (block and pocket) about the block's center, counter-clockwise seen
from above; `simprobe lean <deg>` makes the block's sides flare out going down (wider at the bottom);
`simprobe bow <mm>` makes the block's sides hollow: each side sits <mm> further in at its middle, curving
back out to the corners. They're for edge probing's multi-point measurement (angle and straightness).
"""
from __future__ import annotations

import math
import re
import threading
import time

STOCK = (-150.0, -90.0, -150.0, -110.0, -70.0)       # x0, x1, y0, y1, top
POCKET = (-112.0, -97.0, -145.0, -125.0, -78.0)     # x0, x1, y0, y1, floor
TABLE = -95.0
SETTER = (-9.69, -13.58, -69.637)
TIP_R = 1.0
CHANGE_XY = (-9.89, -13.58)                            # anchor1 + toolrack_offset + (132, 0)
PROBE_TOOLS = (0, 9999)


SHAPE = {"skew": 0.0, "lean": 0.0, "bow": 0.0}      # degrees (simprobe skew / lean), mm (simprobe bow)


def surface(x: float, y: float, z: float | None = None) -> float:
    x0, x1, y0, y1, top = STOCK
    px0, px1, py0, py1, floor = POCKET
    if SHAPE["skew"]:                                  # into the part's own (unturned) frame
        a = -math.radians(SHAPE["skew"]); cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        x, y = cx + (x - cx) * math.cos(a) - (y - cy) * math.sin(a), cy + (x - cx) * math.sin(a) + (y - cy) * math.cos(a)
    if SHAPE["lean"] and z is not None and z < top:    # the sides flare out below the top
        g = (top - z) * math.tan(math.radians(SHAPE["lean"]))
        x0, x1, y0, y1 = x0 - g, x1 + g, y0 - g, y1 + g
    if SHAPE["bow"]:                                    # hollow sides: in by "bow" mid-side, 0 at the corners
        cx, cy, hx, hy = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
        gx = SHAPE["bow"] * max(0.0, 1 - ((y - cy) / hy) ** 2)
        gy = SHAPE["bow"] * max(0.0, 1 - ((x - cx) / hx) ** 2)
        x0, x1, y0, y1 = x0 + gx, x1 - gx, y0 + gy, y1 - gy
    if x0 <= x <= x1 and y0 <= y <= y1:
        return floor if (px0 <= x <= px1 and py0 <= y <= py1) else top
    return TABLE


def blocked(x: float, y: float, z: float) -> bool:
    """The ball (tip at z, center z + r) would be inside the part here."""
    if z < surface(x, y, z) - 1e-6:
        return True
    for dx, dy in ((TIP_R, 0), (-TIP_R, 0), (0, TIP_R), (0, -TIP_R)):
        if z + TIP_R * 0.5 < surface(x + dx, y + dy, z + TIP_R * 0.5) - 1e-6:
            return True
    return False


class ProbeSim:
    """Mixed into Machine (z1sim.py). Uses its lock, queue, mpos, wco, msgs, line(), _alarm()."""

    def probe_init(self):
        self.tlo = -7.1
        self.probe_dead = False
        self.setter_dz = 0.0
        self.script_busy = False
        self.go = threading.Event()

    # ---- helpers ---------------------------------------------------------------------------
    def _wait_idle_locked(self):
        """Wait (lock held on entry) until the planner is empty and nothing moves."""
        while self.queue or self.cur is not None or self.state == "Run":
            if self.state == "Alarm":
                return False
            self.lock.release(); time.sleep(0.01); self.lock.acquire()
        return self.state != "Alarm"

    def _move_and_wait(self, tgt, feed, guard=False):
        self.queue.append((tgt, feed, self.feed_line, "guard" if guard else None))
        return self._wait_idle_locked()

    def probe_on(self) -> bool:
        return self.tool in PROBE_TOOLS

    def guard_cut(self, start, tgt):
        """Motion guard: with the probe in, a move that would push the ball into the part stops there."""
        if not self.probe_on():
            return None
        d = [tgt[i] - start[i] for i in range(3)]
        n = max(abs(v) for v in d)
        steps = int(n / 0.05) + 1
        for k in range(1, steps + 1):
            p = [start[i] + d[i] * k / steps for i in range(3)]
            if blocked(p[0], p[1], p[2]):
                q = [start[i] + d[i] * (k - 1) / steps for i in range(3)]
                return q + [tgt[3]]
        return None

    # ---- G38 -------------------------------------------------------------------------------
    def g38(self, code: float, axis: str, dist: float, feed: float, p1: bool) -> list[str]:
        """Lock held. Relative search, machine axes, like the Z1 (G38.x X20 = up to 20 mm in +X)."""
        if not self._wait_idle_locked():
            return ["error:Alarm lock\r\n"]
        start = list(self.mpos)
        i = "XYZ".index(axis)
        sign = 1 if dist > 0 else -1
        hit = None
        if code == 38.6:                                   # tool setter, Z only
            sx, sy, sz = SETTER
            sz += self.setter_dz
            if abs(start[0] - sx) < 3 and abs(start[1] - sy) < 3 and start[2] >= sz >= start[2] + dist:
                hit = sz
        elif not self.probe_dead:
            def at(v):
                p = list(start); p[i] = v
                return blocked(p[0], p[1], p[2])
            n = int(abs(dist) / 0.05) + 1
            for k in range(1, n + 1):
                v = start[i] + sign * min(k * 0.05, abs(dist))
                if at(v):
                    lo, hi = start[i] + sign * (k - 1) * 0.05, v      # free .. blocked: refine to 0.1 µm
                    for _ in range(40):
                        mid = (lo + hi) / 2
                        lo, hi = (lo, mid) if at(mid) else (mid, hi)
                    hit = round(lo, 4)
                    break
        tgt = list(start)
        tgt[i] = hit if hit is not None else start[i] + dist
        if not self._move_and_wait(tgt, feed):
            return ["error:Alarm lock\r\n"]
        mp = self.mpos
        if hit is None:
            prb = f"[PRB:{mp[0]:.3f},{mp[1]:.3f},{mp[2]:.3f}:0]\n"
            if code == 38.6:
                self._alarm("Calibrate fail!")
            elif code == 38.2:
                self._alarm("Probe fail")
            return [prb, "ok\r\n"]
        self.last_prb = list(mp)
        return [f"[PRB:{mp[0]:.3f},{mp[1]:.3f},{mp[2]:.3f}:1]\n", "ok\r\n"]

    # ---- M-codes ---------------------------------------------------------------------------
    def probe_line(self, t: str, g, m, vals) -> list[str] | None:
        """Lock held. Returns replies, or None if the line isn't ours."""
        if t.startswith("SIMPROBELEN"):                    # another probe: it trips the setter dz higher/lower (spaces are stripped)
            try:
                self.setter_dz = float(t[len("SIMPROBELEN"):])
            except ValueError:
                return ["error:simprobe len <dz>\r\n"]
            return [f"sim: setter Z {SETTER[2] + self.setter_dz:.3f}\r\n", "ok\r\n"]
        for key in ("SKEW", "LEAN", "BOW"):
            if t.startswith("SIMPROBE" + key):
                unit = "mm" if key == "BOW" else "deg"
                try:
                    SHAPE[key.lower()] = float(t[len("SIMPROBE" + key):])
                except ValueError:
                    return [f"error:simprobe {key.lower()} <{unit}>\r\n"]
                return [f"sim: part {key.lower()} {SHAPE[key.lower()]:g} {unit}\r\n", "ok\r\n"]
        if t.startswith("SIMPROBE"):
            self.probe_dead = "DEAD" in t
            return [f"sim: probe {'dead' if self.probe_dead else 'ok'}\r\n", "ok\r\n"]
        for code in (38.2, 38.3, 38.6):
            if code in g:
                ax = next((a for a in "XYZ" if a in vals), None)
                if ax is None:
                    return ["error:at least one of X Y or Z must be specified\r\n"]
                return self.g38(code, ax, vals[ax], vals.get("F", 100.0), "P" in vals)
        sub = re.search(r"M0*480\.(\d+)", t)
        if sub:
            if not self.homed:
                return ["ok\r\n"]                           # the firmware silently does nothing
            n = int(sub.group(1))
            self._script(self._m480(n, vals), echo=n <= 4)
            return ["ok\r\n"]
        if any(abs(x - 490.2) < 1e-6 for x in m):
            self.go.set(); return ["ok\r\n"]
        if any(abs(x - 490.1) < 1e-6 for x in m):           # wait for the user (state Tool)
            self.go.clear(); self.tool_wait = True
            while not self.go.is_set() and self.state != "Alarm":
                self.lock.release(); time.sleep(0.02); self.lock.acquire()
            self.tool_wait = False
            return ["ok\r\n"]
        if any(abs(x - 492.3) < 1e-6 for x in m):
            if self.probe_dead:
                self._alarm_raw("ERROR: Probe dead or not set, please charge or set first!\r\n")
                return ["ok\r\n"]
            return ["ok\r\n"]
        if any(abs(x - 493.1) < 1e-6 for x in m):
            self.tlo = -0.043; return ["ok\r\n"]
        if any(abs(x - 491) < 1e-6 for x in m) and not any(abs(x - 491.1) < 1e-6 for x in m):
            if not self.homed:
                return ["ALARM: Need to home first\r\n", "ok\r\n"]
            self._script(self._cali(self.probe_on(), back=True), echo=True)
            return ["ok\r\n"]
        if 6 in m and "T" in vals and not self.job:
            n = int(vals["T"])
            if n != self.tool:
                if self.spindle_on:
                    self._alarm_raw("Error: can not change tool while spindle is running.\r\n"); return ["ok\r\n"]
                self.msgs.append(f"Please change the tool to: T{n}\r\n")
                self._script(self._change(n), echo=True)
            return ["ok\r\n"]
        return None

    def m495(self, t: str) -> list[str]:
        """Lock held. The firmware's M495 (community ATCHandler): margin scan (C D), Z probe (O F),
        leveling (A B I J H), then P = go to the toolpath corner. Needs tool 0 for any of the three."""
        vals = {k: float(v) for k, v in re.findall(r"([A-Z])([-+]?\d*\.?\d+)", t[4:])}
        if "X" not in vals or "Y" not in vals:
            return ["ERROR: Miss Automation Parameter: X/Y\r\n", "ok\r\n"]
        if not self.homed:
            return ["ok\r\n"]                          # silently nothing, like the firmware
        x, y = vals["X"], vals["Y"]
        margin = "C" in vals and "D" in vals; zp = "O" in vals; lev = all(k in vals for k in "ABIJH")
        def gen():
            if (margin or zp or lev) and self.tool != 0:
                self.msgs.append("Change to probe tool first!\r\n")
                yield from self._change(0)
                yield from self._cali(True, back=False)
            if margin:
                self.msgs.append("Auto scan margin\r\n")
                yield "M494.1"; yield "G53 G0 Z-3.000"; yield f"G90 G0 X{x:.3f} Y{y:.3f}"
                cx, cy = vals["C"], vals["D"]
                for px, py in ((x, cy), (cx, cy), (cx, y), (x, y)):
                    yield f"G90 G1 X{px:.3f} Y{py:.3f} F1000.000"
                yield "WAIT"; yield "M494.2"
            if zp:
                self.msgs.append(f"Auto z probe, offset: {vals['O']:.3f}, {vals['F']:.3f}\r\n")
                yield "G53 G0 Z-3.000"; yield f"G90 G0 X{x + vals['O']:.3f} Y{y + vals['F']:.3f}"
                yield "G38.2 Z-100.000 F500.000"; yield "G10 L20 P0 Z0.000"; yield "G91 G0 Z1.000"; yield "G90"
            if lev:
                nx, ny = int(vals["I"]), int(vals["J"])
                self.msgs.append(f"Auto leveling, grid: {nx} * {ny} height: {vals['H']:.2f}\r\n")
                yield f"G90 G0 X{x:.3f} Y{y:.3f}"
                yield f"G32R1X0Y0A{vals['A']:.3f}B{vals['B']:.3f}I{nx}J{ny}H{vals['H']:.3f}"
                self.mesh = [[round(0.02 * ((i * 7 + j * 3) % 5 - 2), 4) for i in range(nx)] for j in range(ny)]
            if "P" in vals:
                self.msgs.append("Goto path origin first\r\n")
                yield "G53 G0 Z-3.000"; yield f"G90 G0 X{x:.3f} Y{y:.3f}"
        self._script(gen(), echo=True)
        return ["ok\r\n"]

    def _alarm_raw(self, text):
        self.queue.clear(); self.cur = None; self.state = "Alarm"; self.msgs.append(text)

    # ---- scripts (run on their own thread, one line at a time, like the firmware's script queue) --
    def _script(self, gen, echo: bool):
        def run():
            self.script_busy = True
            try:
                for line in gen:
                    if line == "WAIT":
                        with self.lock:
                            self._wait_idle_locked()
                        continue
                    if echo:
                        self.msgs.append(line + "\r\n")
                    for r in self.line(line):
                        if not r.startswith("ok"):
                            self.msgs.append(r)
                    if self.state == "Alarm":
                        return
                with self.lock:
                    self._wait_idle_locked()
                if self.state != "Alarm":
                    self.msgs.append("Done ATC\r\n")
            finally:
                self.script_busy = False
        threading.Thread(target=run, daemon=True).start()

    def _cali(self, is_probe: bool, back: bool):
        start = list(self.mpos)
        if is_probe:
            yield "M494.1"
        yield "M497.3"
        yield "G53 G0 Z-3.000"
        yield f"G53 G0 X{SETTER[0]:.3f} Y{SETTER[1]:.3f}"
        yield "G38.6 Z-108.000 F500.000"
        yield "G91 G0 Z1.000"
        yield "G38.6 Z-2.000 F100.000 P1" if is_probe else "G38.6 Z-2.000 F100.000"
        yield "M493.1"
        yield "G53 G0 Z-20.000"
        if is_probe:
            yield "M492.3"
            yield "M494.2"
        if back:
            yield "G90"
            yield "G53 G0 Z-3.000"
            yield f"G53 G0 X{start[0]:.3f} Y{start[1]:.3f}"

    def _change(self, n: int):
        start = list(self.mpos)
        yield "G53 G0 Z-3.000"
        yield f"G53 G0 X{CHANGE_XY[0]:.3f} Y{CHANGE_XY[1]:.3f}"
        yield "M497.2"
        yield "WAIT"
        self.tool_target = n
        yield "M490.1"
        yield "M493.2 T-1"
        yield from self._cali(n in PROBE_TOOLS, back=False)
        yield f"M493.2 T{n}"
        yield "M494.1" if n == 9999 else "M494.2"
        yield "G90"
        yield "G53 G0 Z-3.000"
        yield f"G53 G0 X{start[0]:.3f} Y{start[1]:.3f}"

    def _m480(self, n: int, vals):
        d = vals.get("D", 2.0); X = vals.get("X", 20.0); Y = vals.get("Y", 20.0); Z = vals.get("Z", 2.0)
        mpos = list(self.mpos)
        if n in (1, 2, 3, 4):
            sx = {1: 1, 2: -1, 3: -1, 4: 1}[n]; sy = {1: 1, 2: 1, 3: -1, 4: -1}[n]
            X *= sx; Y *= sy
            yield "M494.1"; yield "M497.5"
            yield "G38.2 Z-108.000 F100.000"; yield "G10 L20 P0 Z0"; yield "G91 G0 Z1.000"
            yield "G38.2 Z-108.000 F100.000"; yield "G10 L20 P0 Z0"
            yield f"G53 G0 Z{mpos[2]:.3f}"
            yield f"G91 G0 X{-X:.3f}"; yield "M220S10"; yield f"G90 G0 Z{-Z:.3f}"; yield "M220S100"
            yield f"G38.2 X{X:.3f} F100.000"
            yield f"G91 G0 X{-1.0 if X > 0 else 1.0:.3f}"
            yield f"G38.2 X{X:.3f} F50.000"
            yield f"G10 L20 P0 X{-d / 2 if X > 0 else d / 2:.3f}"
            yield f"G91 G0 X{-1.0 if X > 0 else 1.0:.3f}"
            yield f"G53 G0 Z{mpos[2]:.3f}"; yield f"G53 G0 X{mpos[0]:.3f}"
            yield f"G91 G0 Y{Y:.3f}"; yield "M220S10"; yield f"G90 G0 Z{-Z:.3f}"; yield "M220S100"
            yield f"G38.2 Y{-Y:.3f} F100.000"
            yield f"G91 G0 Y{1.0 if Y > 0 else -1.0:.3f}"
            yield f"G38.2 Y{-Y:.3f} F50.000"
            yield f"G10 L20 P0 Y{d / 2 if Y > 0 else -d / 2:.3f}"
            yield f"G91 G0 Y{1.0 if Y > 0 else -1.0:.3f}"
            yield f"G53 G0 Z{mpos[2]:.3f}"
            yield "G90 G0 X0.0Y0.0"
        elif n in (5, 6, 7, 8):
            sx = {5: 1, 6: -1, 7: -1, 8: 1}[n]; sy = {5: 1, 6: 1, 7: -1, 8: -1}[n]
            X *= sx; Y *= sy
            yield "G38.2 Z-108.000 F100.000"; yield "G10 L20 P0 Z0"; yield "G91 G0 Z1.000"
            yield "G38.2 Z-108.000 F100.000"; yield "G10 L20 P0 Z0"
            yield f"G53 G0 Z{mpos[2]:.3f}"
            yield f"G91 G0 X{X:.3f} Y{-Y:.3f}"; yield "M220S10"; yield f"G90 G0 Z{-Z:.3f}"; yield "M220S100"
            yield "WAIT"
            m2 = list(self.mpos)
            yield f"G38.2 X{-X:.3f} F100.000"
            yield f"G91 G0 X{1.0 if X > 0 else -1.0:.3f}"
            yield f"G38.2 X{-X:.3f} F50.000"
            yield f"G10 L20 P0 X{d / 2 if X > 0 else -d / 2:.3f}"
            yield f"G53 G0 X{m2[0]:.3f}"
            yield f"G38.2 Y{Y:.3f} F100.000"
            yield f"G91 G0 Y{-1.0 if Y > 0 else 1.0:.3f}"
            yield f"G38.2 Y{Y:.3f} F50.000"
            yield f"G10 L20 P0 Y{-d / 2 if Y > 0 else d / 2:.3f}"
            yield f"G91 G0 Y{-1.0 if Y > 0 else 1.0:.3f}"
            yield f"G53 G0 Z{mpos[2]:.3f}"
            yield "G90 G0 X0.0Y0.0"
        elif n == 9:                                       # pocket / hole center: starts at depth, no Z
            for i, ax, dist in ((0, "X", X), (1, "Y", Y)):
                if abs(dist) <= 0:
                    continue
                yield f"G38.2 {ax}{-dist:.3f} F100.000"; yield f"G91 G0 {ax}1.000"
                yield f"G38.2 {ax}{-dist:.3f} F50.000"; yield "WAIT"
                left = self.mpos[i]
                yield f"G91 G0 {ax}1.000"; yield f"G38.2 {ax}{2 * dist:.3f} F100.000"; yield f"G91 G0 {ax}-1.000"
                yield f"G38.2 {ax}{dist:.3f} F50.000"; yield "WAIT"
                right = self.mpos[i]
                yield f"G53 G0 {ax}{left - (left - right) / 2:.3f}"; yield f"G10 L20 P0 {ax}0"
        elif n == 10:                                      # boss / block center
            yield "G38.2 Z-108.000 F100.000"; yield "G10 L20 P0 Z0"; yield "G91 G0 Z1.000"
            yield "G38.2 Z-108.000 F100.000"; yield "G10 L20 P0 Z0"
            yield f"G53 G0 Z{mpos[2]:.3f}"
            for i, ax, dist in ((0, "X", X), (1, "Y", Y)):
                if abs(dist) <= 0:
                    continue
                yield f"G91 G0 {ax}{-dist:.3f}"; yield "M220S10"; yield f"G90 G0 Z{-Z:.3f}"
                yield f"G38.2 {ax}{dist:.3f} F100.000"; yield "M220S100"
                yield f"G91 G0 {ax}-1.000"; yield f"G38.2 {ax}{dist:.3f} F50.000"; yield "WAIT"
                left = self.mpos[i]
                yield f"G91 G0 {ax}-1.000"; yield f"G53 G0 Z{mpos[2]:.3f}"; yield f"G53 G0 {ax}{mpos[i]:.3f}"
                yield f"G91 G0 {ax}{dist:.3f}"; yield "M220S10"; yield f"G90 G0 Z{-Z:.3f}"; yield "M220S100"
                yield f"G38.2 {ax}{-2 * dist:.3f} F100.000"; yield f"G91 G0 {ax}1.000"
                yield f"G38.2 {ax}{-dist:.3f} F50.000"; yield "WAIT"
                right = self.mpos[i]
                yield f"G91 G0 {ax}1.000"; yield f"G53 G0 Z{mpos[2]:.3f}"
                yield f"G53 G0 {ax}{left - (left - right) / 2:.3f}"; yield f"G10 L20 P0 {ax}0"
