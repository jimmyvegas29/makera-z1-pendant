"""Makera Z1 simulator: speaks the stock wire protocol so the pendant app can be tested with the
machine off. Not a faithful Smoothieware: just enough G-code for jogging, zeroing and macros.

  python z1sim.py [--name Makera_Z1P_SIM] [--port 2222] [--latency-ms 0] [--no-broadcast]

  * UDP broadcast "name,ip,port,busy,version" to 255.255.255.255:3333 once a second.
  * TCP 2222, several clients at once. Every frame is logged.
  * '?' -> 0x81 status, '!' hold, '~' resume, 0x18 halt (-> Alarm, needs $X), 0x19 stop (flush queue).
  * Jobs: `play <name>` plays <--sd>/<name> with |P:line,percent,seconds| in the status, ~4 s in
    state Tool for each M6, and frame 0xB7 answering "path|md5" of the playing file.
  * 3D probing (z1sim_probe.py): a part to probe, G38.2/G38.6 with [PRB:], M480.1-.10, M491, M6 T with
    the tool-change wait (M490.2 continues) and the probe calibrated on the setter; `simprobe dead`.
  * `simfreeze <s>` (test hook, no reply): this client's connection goes silent for s seconds, like
    the Z1's network stalling: nothing is read or answered, then the backlog is handled as usual.
  * Lines: G0/G1 (G90/G91, F), G4, G10 L20 P0, G92, G53, $H, $X, M220 S, M3/M5 S, M6 T, M999, anything
    else just gets "ok". Replies are 0x90 "ok\\r\\n".
  * Motion runs in real time at the commanded feed (G0 = axis max rate) x feed override, with a
    32-block planner queue; soft limits X -207..-1, Y -206..-1, Z -102..-1 once homed (--model air:
    the Carvera Air's X -302, Y -212, Z -121); $H ends at MPos -1 on X, Y and Z, like the real Z1.
  * M211 answers like the firmware: "Soft endstops are Enabled", then ",X min = -207.000",
    ",X max = -1.000", ... each as its own message (one print each, as a real Carvera Air sends it);
    --m211-one-line sends it as one line. M211 S0/S1 turns them off/on. --no-m211 makes it a plain
    "ok" (firmware without it).
"""
from __future__ import annotations

import argparse
import hashlib
import math
import os
import shutil
import re
import socket
import threading
import time
from collections import deque

import z1proto as P
from z1sim_probe import ProbeSim

AXES = "XYZA"
MAX_RATE = {"X": 3000.0, "Y": 3000.0, "Z": 1000.0, "A": 1800.0}   # mm/min (deg/min for A)
SOFT = {"X": (-207.0, -1.0), "Y": (-206.0, -1.0), "Z": (-102.0, -1.0)}
# Carvera Air: soft_endstop.*_min from the community firmware's config2.default (max -1 is in Robot.cpp).
SOFT_AIR = {"X": (-302.0, -1.0), "Y": (-212.0, -1.0), "Z": (-121.0, -1.0)}
QUEUE_MAX = 32
QUEUE_DELAY_S = 0.100   # Smoothie conveyor queue_delay_time_ms default: a queue that was empty waits this long


def log(msg):
    print(f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}", flush=True)


class Machine(ProbeSim):
    def __init__(self):
        self.lock = threading.RLock()
        self.name = "Makera_Z1P_SIM"                 # --name; also the config's wifi.machine_name
        self.mpos = [-100.0, -100.0, -50.0, 0.0]
        self.wco = [0.0, 0.0, 0.0, 0.0]          # work offset: wpos = mpos - wco
        self.absolute = True
        self.feed = 500.0
        self.fro = 100.0
        self.spindle_target = 10000.0          # the real Z1 reports its default target while off
        self.spindle_factor = 100.0            # M223 spindle override, %
        self.spindle_on = False
        self.spindle_cur = 0.0
        self.spindle_at = time.monotonic()
        self.tool = 1                          # active tool (M6 T…); 0 = none: M3 then halts, like the Z1
        self.light = True
        self.vacuum = False
        self.state = "Idle"
        self.homed = False
        self.hold = False
        # M600 in a playing file suspends it ("Pause"); only `resume` / M601 ends that, `~` doesn't
        # (Z1 2026-11-24). resume moves back to the saved XYZ and restores the spindle.
        self.suspended = False
        self.saved = None                         # (mpos, spindle_on) at the suspend
        self.queue: deque = deque()              # each: (target list, feed mm/min) or ("dwell", s)
        self.cur = None
        self.msgs: list[str] = []                 # pushed to clients as 0x90
        self.wait_since = None                    # when the first block arrived in an empty queue
        self.starts = 0                           # restarts from an empty queue (stop-go count)
        self.job = None                           # a playing file (see play()); gives |P: in the status
        self.feed_line = 0                        # file line of the next queued move (job playback)
        self.tool_target = -1                     # |T: field 3: the tool a tool change asks for
        self.tool_wait = False                    # waiting for the user to swap the tool (state "Tool")
        self.sd = "sim_sd"                        # folder standing in for the SD card (/sd/gcodes/<name>)
        self.last_prb = None
        self.speed = 1.0                          # --speed: motion runs this many times faster
        self.probe_init()                         # 3D probing (z1sim_probe.py)
        # Run flow (captured from Studio 2026-11-17): buffer + M495, assist modes, leveling grid.
        self.buffered = None
        self.assist = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0}   # M331[.n] on / M332[.n] off
        self.mesh = None                               # rows of Z corrections (front first)
        # --boot-mesh empty: like the Z1 right after power-on (2026-11-22): |O: in the status, but M375.1
        # prints a 15 x 15 grid of "nan" (no values). Cleared by M370 like a real grid.
        self.ghost_mesh = None
        # Temperatures (|S: fields 5 and 6). --heat N: the spindle warms N °C/min while it turns and cools
        # 6 °C/min toward 29 °C when stopped; the box follows at a tenth. Over spindle_max it halts like the
        # Z1 (2026-11-22): "ERROR: Spindle overheated, max - 75.0, current - 75.1", Alarm, |H:9; cleared above
        # the limit it halts again.
        self.spindle_temp = 29.0
        self.box_temp = 22.8
        self.heat = 0.0
        self.spindle_max = 75.0
        self.temp_at = time.monotonic()
        self.soft = dict(SOFT)                    # --model air: SOFT_AIR
        self.soft_enabled = True                  # M211 S0/S1
        self.m211 = True                          # --no-m211: firmware without the M211 report
        self.m211_one_line = False                # --m211-one-line: the report as one message
        self.halt_code = None

    # --- G-code -------------------------------------------------------------------------
    def line(self, text: str) -> list[str]:
        t = text.strip().upper().replace(" ", "")
        if not t:
            return ["ok\r\n"]
        with self.lock:
            if t.startswith("PLAY"):
                return self.play(text.strip()[4:].strip())
            if t.startswith("CONFIG-GET"):
                key = text.split()[-1]
                v = {"coordinate.anchor1_x": "-190.69", "coordinate.anchor1_y": "-194.58",
                     "wifi.machine_name": self.name, "temperature_control.spindle.max_temp": f"{self.spindle_max:.1f}",
                     "soft_endstop.enable": "true" if self.soft_enabled else "false",
                     **{f"soft_endstop.{a.lower()}_min": f"{self.soft[a][0]:.1f}" for a in "XYZ"}}.get(key)
                return [f"sd: {key} is set to {v}\r\n" if v else f"sd: {key} is not in config\r\n", "ok\r\n"]
            if t in ("RESUME", "M601"):
                ok = ["ok\r\n"] if t == "M601" else []      # console commands get no "ok"
                if not self.suspended:
                    return ["Not suspended\r\n"] + ok
                pos, spin = self.saved
                self.msgs.append("Resuming playing...\r\n")
                if spin:
                    self.spindle_on = True; self.spindle_at = time.monotonic()
                if list(pos) != list(self.mpos):
                    self.msgs.append("Restoring saved XYZ positions and state...\r\n")
                    self.queue.append((list(pos), 1000.0))
                self.suspended = False; self.saved = None
                return ["Playing file resumed\r\n"] + ok
            if t.startswith("BUFFER"):
                self.buffered = text.strip()[6:].strip()
                return [f"Command buffered: {self.buffered}\n\r\n", "ok\r\n"]
            mm = re.fullmatch(r"M33([12])(?:\.(\d))?", t)
            if mm:
                idx = int(mm.group(2) or 0); on = mm.group(1) == "1"
                self.assist[idx] = 1 if on else 0
                name = ["extend out", "auto blowing", "auto bed cleaning", "extout", "static electricity removal"][idx]
                return [f"turning {name} mode {'on' if on else 'off'}\r\n", "ok\r\n"]
            if t == "M370":
                self.mesh = None; self.ghost_mesh = None; return ["ok\r\n"]
            if t == "M375.1":
                if not self.mesh and self.ghost_mesh is not None:
                    ys = [50 * i / 14 for i in range(14, -1, -1)]
                    rows = "".join(f"{y:.4f}|" + "".join(f"{'nan':>11}" for _ in range(15)) + "\n" for y in ys)
                    return [rows + "-----+" + "----------+" * 14 + "-----\n"
                            + " ".join(f"{50 * i / 14:.4f}" for i in range(15)) + "\n", "ok\r\n"]
                if not self.mesh:
                    return ["No grid\r\n", "ok\r\n"]
                return ["".join(" ".join(f"{v:.4f}" for v in row) + " \n" for row in self.mesh), "ok\r\n"]
            if t.startswith("M495"):
                return self.m495(t)
            mm = re.fullmatch(r"M211(?:S([01]))?", t)
            if mm and self.m211:
                if mm.group(1) is not None:
                    self.soft_enabled = mm.group(1) == "1"
                    return ["ok\r\n"]
                parts = [f"Soft endstops are {'Enabled' if self.soft_enabled else 'Disabled'}"]
                for i, a in enumerate("XYZ"):
                    lo, hi = self.soft[a]
                    parts += [f"{a} min = {lo:.3f}", f"{a} max = {hi:.3f}"]
                    if not self.homed:
                        parts.append(f"{a} axis is not homed")
                if self.m211_one_line:
                    return [",".join(parts) + "\n", "ok\r\n"]
                return [parts[0]] + ["," + p for p in parts[1:]] + ["ok\r\n"]
            if t == "$#":                            # work offsets, as the firmware prints them (machine coordinates)
                w = self.wco
                rows = [f"[G54:{w[0]:.4f},{w[1]:.4f},{w[2]:.4f},{w[3]:.4f},0.0000]\r\n"]
                rows += [f"[G{n}:0.0000,0.0000,0.0000,0.0000,0.0000]\r\n" for n in range(55, 60)]
                rows += ["[G28:0.0000,0.0000,0.0000]\r\n", "[G30:0.0000,0.0000,0.0000]\r\n",
                         "[G92:0.0000,0.0000,0.0000,0.0000,0.0000]\r\n"]
                return ["".join(rows), "ok\r\n"]
            if t in ("$X", "M999"):
                if self.state == "Alarm":
                    self.state = "Idle"; self.halt_code = None
                return ["ok\r\n"]
            if t == "SIMDRIVERFAULT":                # test hook: the spindle driver's fault line (halt 41)
                self.queue.clear(); self.cur = None; self.spindle_on = False
                self.state = "Alarm"; self.halt_code = 41
                return ["ERROR: Spindle alarm triggered -  power off/on required\r\n", "ok\r\n"]
            if self.state == "Alarm":
                return ["error:Alarm lock\r\n"]
            if t == "$H":
                self.queue.clear()
                self.cur = None
                self.state = "Home"
                threading.Thread(target=self._home, daemon=True).start()
                return ["ok\r\n"]
            words = re.findall(r"([A-Z])([-+]?\d*\.?\d*)", t)
            g = [float(v) for k, v in words if k == "G" and v]
            m = [float(v) for k, v in words if k == "M" and v]
            vals = {}
            for k, v in words:
                if k not in "GM" and v not in ("", "+", "-", "."):
                    vals[k] = float(v)
            if 90 in g:
                self.absolute = True
            if 91 in g:
                self.absolute = False
            if "F" in vals and not (10 in g or 4 in g):
                self.feed = vals["F"]
            r = self.probe_line(t, g, m, vals)    # G38.x, M480, M491, M6 T, M490.x … (z1sim_probe.py)
            if r is not None:
                return r
            if 223 in m and "S" in vals:
                self._spin()
                self.spindle_factor = max(50.0, min(200.0, vals["S"]))   # stock Z1 clamp
                return ["ok\r\n"]
            if 220 in m and "S" in vals:
                self.fro = max(10.0, min(300.0, vals["S"]))
                return ["ok\r\n"]
            if 821 in m:
                self.light = True
            if 822 in m:
                self.light = False
            if 851 in m:
                self.vacuum = True
            if 852 in m:
                self.vacuum = False
            if 6 in m and "T" in vals:
                self.tool = int(vals["T"])
            if 493.2 in m and "T" in vals:          # set current tool, no motion
                self.tool = int(vals["T"])
            if 3 in m or 4 in m:
                if self.tool <= 0 or self.tool >= 100000:
                    self.halt()
                    return ["ERROR: No tool or probe tool!\r\n"]
                self._spin()
                self.spindle_target = vals.get("S", self.spindle_target)
                self.spindle_on = True
            if 5 in m:
                self._spin()
                self.spindle_on = False
            if 10 in g and vals.get("L") == 2:     # the work offset itself, machine coordinates
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        self.wco[i] = vals[ax]
                return ["ok\r\n"]
            if 10 in g and vals.get("L") == 20:
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        self.wco[i] = self._end_pos()[i] - vals[ax]
                return ["ok\r\n"]
            if 92 in g:
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        self.wco[i] = self._end_pos()[i] - vals[ax]
                return ["ok\r\n"]
            if 4 in g:
                self.queue.append(("dwell", vals.get("P", 0) / 1000.0 if "P" in vals else vals.get("S", 0)))
                return ["ok\r\n"]
            if any(ax in vals for ax in AXES) and (0 in g or 1 in g or not g or 53 in g or 90 in g or 91 in g):
                start = self._end_pos()
                tgt = list(start)
                for i, ax in enumerate(AXES):
                    if ax in vals:
                        if 53 in g:
                            tgt[i] = vals[ax]
                        elif self.absolute:
                            tgt[i] = vals[ax] + self.wco[i]
                        else:
                            tgt[i] = start[i] + vals[ax]
                if self.homed and self.soft_enabled:
                    for i, ax in enumerate("XYZ"):
                        lo, hi = self.soft[ax]
                        if not (lo - 1e-6 <= tgt[i] <= hi + 1e-6):
                            self._alarm(f"Soft Endstop {ax} was exceeded - reset or $X or M999 required")
                            return ["error:Soft Endstop\r\n"]
                rapid = 0 in g and 1 not in g
                while len(self.queue) >= QUEUE_MAX:          # firmware blocks before 'ok' when full
                    self.lock.release()
                    time.sleep(0.01)
                    self.lock.acquire()
                cut = self.guard_cut(start, tgt)    # probe in: a move into the part stops there and alarms
                if cut is not None:
                    self.queue.append((cut, None if rapid else self.feed, self.feed_line, "guard"))
                else:
                    self.queue.append((tgt, None if rapid else self.feed, self.feed_line))
                return ["ok\r\n"]
            return ["ok\r\n"]

    # --- jobs ------------------------------------------------------------------------------
    def sd_path(self, path: str) -> str:
        """/sd/gcodes/<rel> -> <sd folder>/<rel> (subfolders kept); any other path -> its file name."""
        rel = path[len("/sd/gcodes/"):] if path.startswith("/sd/gcodes/") else os.path.basename(path)
        parts = [p for p in rel.split("/") if p]
        if not parts or ".." in parts:
            return os.path.join(self.sd, "_invalid_")
        return os.path.join(self.sd, *parts)

    def sd_dir(self, path: str) -> str | None:
        """/sd/gcodes[/<rel>] -> the folder on disk; None outside /sd/gcodes."""
        p = path.rstrip("/")
        if p == "/sd/gcodes":
            return self.sd
        if not p.startswith("/sd/gcodes/"):
            return None
        return self.sd_path(p)

    def fs_command(self, text: str) -> list[tuple[int, str]]:
        """The Wi-Fi board's own ls -s / mkdir / mv / rm, with the replies measured on the Z1
        (reference/file_commands_notes.md): 0x83 listing chunks, 0x84 'ok' or 'Load directory finished.',
        0x85 errors, 0x90 info. Spaces inside paths travel as 0x01."""
        parts = text.strip().split(" ")
        cmd, args = parts[0], [a.replace("\x01", " ") for a in parts[1:] if a]
        if len(text.strip().encode("utf-8")) > 128:
            # The real board mixes a longer line with an earlier command's leftovers and acts on that
            # (measured 2026-11-23: 128 bytes work, 129 don't). The sim refuses instead of guessing.
            if cmd == "ls":
                return [(0x84, "Load directory finished.\r\n")]
            what = {"mv": "rename", "rm": "delete", "mkdir": "create directory"}.get(cmd, cmd)
            return [(0x85, f"Could not {what} /load (line over 128 bytes)\r\n")]
        if cmd == "ls":
            local = self.sd_dir(args[-1] if args and args[-1] != "-s" else "/sd/gcodes")
            lines = []
            if local and os.path.isdir(local):
                for name in sorted(os.listdir(local)):
                    full = os.path.join(local, name)
                    ts = time.strftime("%Y%m%d%H%M%S", time.gmtime(os.path.getmtime(full)))
                    enc = name.replace(" ", "\x01")
                    lines.append(f"{enc}/ 0 {ts}\r\n" if os.path.isdir(full) else f"{enc}  {os.path.getsize(full)} {ts}\r\n")
            out, chunk = [], ""
            for line in lines:
                if chunk and len(chunk) + len(line) > 400:
                    out.append((0x83, chunk)); chunk = ""
                chunk += line
            if chunk:
                out.append((0x83, chunk))
            return out + [(0x84, "Load directory finished.\r\n")]
        if cmd == "mkdir" and len(args) == 1:
            local = self.sd_dir(args[0])
            try:
                os.mkdir(local)
                return [(0x84, "ok\r\n"), (0x90, f"created directory {args[0]}\r\n")]
            except (OSError, TypeError):
                return [(0x85, f"could not create directory {args[0]}\r\n")]
        if cmd == "mv" and len(args) == 2:
            a, b = self.sd_dir(args[0]), self.sd_dir(args[1])
            try:
                if b and os.path.exists(b):          # FATFS f_rename won't overwrite (FR_EXIST)
                    raise OSError("exists")
                os.rename(a, b)
                return [(0x84, "ok\r\n"), (0x90, f"renamed {args[0]} to {args[1]}\r\n")]
            except (OSError, TypeError):
                return [(0x85, f"Could not rename {args[0]} to {args[1]}\r\n")]
        if cmd == "rm" and len(args) == 1:
            local = self.sd_dir(args[0])
            try:
                if local and os.path.isdir(local):
                    shutil.rmtree(local)
                else:
                    os.remove(local)
                return [(0x84, "ok\r\n")]
            except (OSError, TypeError):
                return [(0x85, f"Could not delete {args[0]} \r\n")]
        return [(0x85, f"{cmd}: bad arguments\r\n")]

    def play(self, path: str) -> list[str]:
        """Play a file like the firmware's player: lines go to the planner in order, |P: reports
        line / percent of the file read / seconds run, M6 waits ~4 s in state Tool (standing in for
        the user's OK), and after the end |P: shows 100 % for about a second."""
        path = path.replace(chr(1), " ")          # the board space character
        local = self.sd_path(path)
        if self.job or not os.path.isfile(local):
            return [f"error: can't play {path}\r\n"]
        data = open(local, "rb").read()
        sd = path if path.startswith("/sd/gcodes/") else "/sd/gcodes/" + os.path.basename(path)
        self.job = {"path": sd, "md5": hashlib.md5(data).hexdigest(), "size": len(data), "read": 0,
                    "line": 0, "start": time.time(), "paused": 0.0, "done_at": None}
        pre = self.buffered; self.buffered = None
        if pre:                                    # the buffered line runs before line 1
            self.msgs.append(pre + "\n\r\n")
            self.m495(pre.upper().replace(" ", ""))
        threading.Thread(target=self._player, args=(data.decode("utf-8", "replace").splitlines(keepends=True),),
                         daemon=True).start()
        return [f"Playing {sd}\r\n", "ok\r\n"]

    def _wait_queue(self, empty=False):
        while True:
            with self.lock:
                if self.state == "Alarm" or not self.job:
                    return False
                if not self.hold and (len(self.queue) < QUEUE_MAX - 2 if not empty else (not self.queue and self.cur is None)):
                    return True
            time.sleep(0.01)

    def _player(self, lines):
        j = self.job
        while self.script_busy:                   # the buffered M495 first
            time.sleep(0.05)
        for n, raw in enumerate(lines, 1):
            j["read"] += len(raw.encode())
            code = re.sub(r"\([^)]*\)", "", raw.split(";")[0]).strip().upper().replace(" ", "")
            if not code:
                continue
            if not self._wait_queue():
                break
            self.feed_line = n
            m6 = re.search(r"(?<![A-Z])M0*6(?![0-9.])", code)
            tm = re.search(r"T(\d+)", code)
            if m6 and tm and int(tm.group(1)) != self.tool:
                if not self._wait_queue(empty=True):
                    break
                j["line"] = n
                with self.lock:                     # the swap: Tool state, collet empty, then the new tool
                    self.tool_target = int(tm.group(1)); self.tool_wait = True
                    self.spindle_on = False
                t0 = time.time()
                time.sleep(1.0)
                with self.lock:
                    self.tool = -1
                time.sleep(3.0)
                with self.lock:
                    self.tool = self.tool_target; self.tool_wait = False
                    self.msgs.append("Done ATC\r\n")
                j["paused"] += time.time() - t0
                continue
            if re.fullmatch(r"M0*600", code):
                if not self._wait_queue(empty=True):
                    break
                with self.lock:
                    self.saved = (list(self.mpos), self.spindle_on)
                    self.suspended = True; self.spindle_on = False
                    self.msgs.append("Suspended, send resume to continue\r\n")
                t0 = time.time()
                while True:
                    with self.lock:
                        if not self.suspended or self.state == "Alarm" or self.job is not j:
                            break
                    time.sleep(0.02)
                j["paused"] += time.time() - t0
                continue
            if re.fullmatch(r"M0*(2|30)", code):
                break
            self.line(raw)
        self._wait_queue(empty=True)
        with self.lock:
            if self.job is j:
                j["read"] = j["size"]; j["done_at"] = time.time()

    def job_field(self) -> str:
        j = self.job
        if not j:
            return ""
        if j["done_at"] and time.time() - j["done_at"] > 1.2:
            self.job = None
            return ""
        el = int((j["done_at"] or time.time()) - j["start"] - j["paused"])
        return f"|P:{j['line']},{j['read'] * 100 // max(1, j['size'])},{el}"

    def _end_pos(self):
        for item in reversed(self.queue):
            if item[0] != "dwell":
                return list(item[0])
        if self.cur and self.cur[0] != "dwell":
            return list(self.cur[0])
        return list(self.mpos)

    def _alarm(self, msg):
        self.queue.clear()
        self.cur = None
        self.suspended = False; self.saved = None
        self.state = "Alarm"
        self.msgs.append(f"ALARM: {msg}\r\n")

    def halt(self):
        with self.lock:
            self._alarm("Abort during cycle")

    def stop(self):
        with self.lock:
            self.queue.clear()
            self.cur = None

    def _home(self):
        while True:
            with self.lock:
                if self.state != "Home":
                    return
                done = True
                for i, ax in enumerate("XYZ"):
                    # The real Z1 homes to MPos -1.000 (the soft max); 0 would be past it,
                    # so the next move on any axis would trip the soft limit.
                    home = self.soft[ax][1]
                    step = 3000 / 60 * 0.01
                    if self.mpos[i] != home:
                        d = home - self.mpos[i]
                        self.mpos[i] = home if abs(d) <= step else self.mpos[i] + math.copysign(step, d)
                        done = False
                if done:
                    self.homed = True
                    self.state = "Idle"
                    return
            time.sleep(0.01)

    # --- motion thread ------------------------------------------------------------------
    def run(self):
        dt = 0.005
        dwell_end = 0.0
        while True:
            time.sleep(dt)
            with self.lock:
                if self.state in ("Alarm", "Home"):
                    continue
                if self.tool_wait:
                    self.state = "Tool"
                    continue
                if self.hold:
                    self.state = "Hold"
                    continue
                if self.suspended:
                    self.state = "Pause"
                    continue
                if self.cur is None and self.queue:
                    if self.state != "Run":
                        # empty -> non-empty: Smoothie holds off QUEUE_DELAY_S unless the queue fills
                        if self.wait_since is None:
                            self.wait_since = time.time()
                        if time.time() - self.wait_since < QUEUE_DELAY_S and len(self.queue) < QUEUE_MAX:
                            continue
                        self.wait_since = None
                        self.starts += 1
                        log(f"START #{self.starts} (queue {len(self.queue)})")
                    self.cur = self.queue.popleft()
                    if self.cur[0] == "dwell":
                        dwell_end = time.time() + self.cur[1]
                if self.cur is None:
                    self.state = "Idle"
                    continue
                self.state = "Run"
                if self.cur[0] == "dwell":
                    if time.time() >= dwell_end:
                        self.cur = None
                    continue
                tgt, feed = self.cur[0], self.cur[1]
                if len(self.cur) > 2 and self.job:
                    self.job["line"] = self.cur[2]
                d = [tgt[i] - self.mpos[i] for i in range(4)]
                dist = math.sqrt(sum(x * x for x in d))
                if dist < 1e-6:
                    self.mpos = list(tgt)
                    self._finished()
                    continue
                if feed is None:
                    feed = min(MAX_RATE[AXES[i]] for i in range(4) if abs(d[i]) > 1e-9)
                else:
                    feed = feed * self.fro / 100.0
                    feed = min([feed] + [MAX_RATE[AXES[i]] for i in range(4) if abs(d[i]) > 1e-9])
                step = feed / 60.0 * dt * self.speed
                if step >= dist:
                    self.mpos = list(tgt)
                    self._finished()
                else:
                    self.mpos = [self.mpos[i] + d[i] / dist * step for i in range(4)]

    def _finished(self):
        guard = len(self.cur) > 3 and self.cur[3] == "guard"
        self.cur = None
        if guard:
            self._alarm("unexpected probe trigger")

    def _spin(self):
        """Ramp the measured RPM toward the target (on) or 0 (off), about 3000 RPM/s."""
        now = time.monotonic()
        goal = min(13000.0, self.spindle_target * self.spindle_factor / 100) if self.spindle_on else 0.0
        step = 3000.0 * (now - self.spindle_at)
        self.spindle_at = now
        d = goal - self.spindle_cur
        self.spindle_cur = goal if abs(d) <= step else self.spindle_cur + math.copysign(step, d)

    def _temps(self):
        """The heat model (see __init__); halts over the limit."""
        now = time.monotonic()
        dt = (now - self.temp_at) * self.speed / 60.0; self.temp_at = now
        if self.heat <= 0:
            return
        if self.spindle_on and self.spindle_cur > 0:
            self.spindle_temp += self.heat * dt; self.box_temp += self.heat / 10 * dt
        else:
            self.spindle_temp = max(29.0, self.spindle_temp - 6 * dt); self.box_temp = max(22.8, self.box_temp - 0.6 * dt)
        if self.spindle_temp > self.spindle_max and self.state != "Alarm":
            self.msgs.append(f"ERROR: Spindle overheated, max - {self.spindle_max:.1f}, current - {self.spindle_temp:.1f}\r\n")
            self.msgs.append("Aborted by halt\r\n")
            self.queue.clear(); self.cur = None; self.spindle_on = False
            self.state = "Alarm"; self.halt_code = 9

    def status(self) -> str:
        with self.lock:
            self._spin()
            self._temps()
            mp = self.mpos
            wp = [mp[i] - self.wco[i] for i in range(4)]
            f = self.cur[1] if self.cur and self.cur[0] != "dwell" and self.cur[1] else 0.0
            return (f"<{self.state}|MPos:{mp[0]:.4f},{mp[1]:.4f},{mp[2]:.4f},{mp[3]:.4f},0.0000"
                    f"|WPos:{wp[0]:.4f},{wp[1]:.4f},{wp[2]:.4f},{wp[3]:.4f},0.0000"
                    f"|F:{f:.1f},{self.feed:.1f},{self.fro:.1f}|S:{self.spindle_cur:.1f},{self.spindle_target:.1f},{self.spindle_factor:.1f},"
                    f"{self.assist[0]},{self.spindle_temp:.1f},{self.box_temp:.1f},{self.assist[1]},{self.assist[2]},{self.assist[3]},{self.assist[4]}"
                    + (f"|O:{max(abs(v) for r in self.mesh for v in r):.3f}" if self.mesh
                       else f"|O:{self.ghost_mesh:.3f}" if self.ghost_mesh is not None else "") +
                    (f"|H:{self.halt_code}" if self.state == "Alarm" and self.halt_code else "") +
                    f"|T:{self.tool},{self.tlo:.3f},{self.tool_target}{self.job_field()}|W:0.00>")


class Transfers:
    """The Wi-Fi board's file transfers, as decompiled (reference/upload_protocol_notes.md):
    * One owner: the connection slot that sent the accepted 0xB0 (FUN_42012fa0). A 0xB0 from anyone else
      gets 0x91. 0xB1-0xB6 from the owner go into a one-frame mailbox (a newer frame overwrites);
      from anyone else they're dropped.
    * Every accepted 0xB0 is queued for one task that runs the handlers one after another, and each
      handler clears the owner when it ends, even with more queued (so a queued one runs ownerless).
    * Connection slots are reused: a transfer whose client dropped keeps running until 9 s pass with no
      frame, and the next connection given that slot inherits it.
    * Upload (FUN_4201f750): open "wb", wait for the client's MD5, ask for the view (empty B2), ask for
      packets (B3 seq). Cancel ends it silently; cancel or timeout removes the file and its MD5 entry.
    * Download (FUN_4201ec70): B1 md5 first; B2 -> count + u16 8192; B3 seq -> data; B4 -> ok."""

    TIMEOUT = 9.0
    REASK = 3.5

    def __init__(self, machine):
        self.m = machine
        self.lock = threading.Lock()
        self.slots = {}
        self.owner = 0
        self.queue = deque()
        self.mail = None
        self.drop_upload_seq = 0              # "simxfer drop <seq>": lose the first copy of that data packet
        threading.Thread(target=self._task, daemon=True).start()

    def connect(self, client) -> int:
        with self.lock:
            slot = next(i for i in range(1, 100) if i not in self.slots)
            self.slots[slot] = client
            return slot

    def disconnect(self, slot):
        with self.lock:
            self.slots.pop(slot, None)

    def send(self, slot, ptype, data: bytes):
        c = self.slots.get(slot)
        if c:
            c.txb(ptype, data)

    def frame(self, slot, ptype, payload):
        with self.lock:
            if ptype == 0xB0:
                if self.owner not in (0, slot):
                    self.send(slot, 0x91, b"Other client is currently uploading/downloading files. Please try again later.")
                    return
                self.owner = slot
                self.queue.append((slot, payload))
            elif self.owner == slot:
                self.mail = (ptype, payload)

    def _task(self):
        while True:
            with self.lock:
                job = self.queue.popleft() if self.queue else None
            if not job:
                time.sleep(0.01)
                continue
            slot, payload = job
            text = payload.decode("ascii", "replace").strip().strip("\x00").replace("\x01", " ")
            try:
                if text.startswith("upload"):
                    self._upload(slot, text[len("upload"):].strip())
                else:
                    self._download(slot, text[len("download"):].strip() if text.startswith("download") else text)
            finally:
                with self.lock:
                    self.owner = 0
                    self.mail = None

    def _get(self):
        with self.lock:
            m, self.mail = self.mail, None
        if m is None:
            time.sleep(0.01)
        return m

    def _upload(self, slot, path):
        local = self.m.sd_path(path)
        try:
            f = open(local, "wb")
        except OSError:
            self.send(slot, 0xB5, f"Error: failed to open file [{path}]!".encode())
            return
        md5file = None
        state, seq, count = "md5", 0, 0
        last = asked = time.time()

        def abort():
            f.close()
            for x in (local, md5file):
                if x:
                    try:
                        os.remove(x)
                    except OSError:
                        pass

        def request():
            if state == "md5":
                self.send(slot, 0xB1, b"")
            elif state == "view":
                self.send(slot, 0xB2, b"")
            else:
                self.send(slot, 0xB3, seq.to_bytes(4, "big"))

        while True:
            now = time.time()
            if now - last > self.TIMEOUT:
                self.send(slot, 0xB5, b"Info: Machine receive file time out!\r\n")
                return abort()
            if now - asked > self.REASK:
                request()
                asked = now
            m = self._get()
            if m is None:
                continue
            ptype, payload = m
            last = asked = time.time()
            if ptype == 0xB5:
                return abort()                         # the board sends nothing back for an upload cancel
            if state == "md5" and ptype == 0xB1:
                rel = os.path.relpath(local, self.m.sd)
                md5file = os.path.join(self.m.sd, ".md5", rel)
                os.makedirs(os.path.dirname(md5file), exist_ok=True)
                with open(md5file, "wb") as mf:
                    mf.write(payload[:32])
                state = "view"
                request()
            elif state == "view" and ptype == 0xB2:
                count = int.from_bytes(payload[:4], "big")
                state, seq = "data", 1
                request()
            elif state == "data" and ptype == 0xB3 and int.from_bytes(payload[:4], "big") == seq:
                if self.drop_upload_seq == seq:
                    self.drop_upload_seq = 0
                    continue
                data = payload[4:]
                if len(data) > 8192:
                    self.send(slot, 0xB6, b"Error: File Write error!retry...")
                    continue
                f.write(data)
                if count <= seq:
                    f.close()
                    self.send(slot, 0xB4, b"ok\r\n")
                    self.send(slot, 0x90, f"Info: upload success: {path}.".encode())
                    return
                seq += 1
                request()

    def _download(self, slot, path):
        try:
            data = open(self.m.sd_path(path), "rb").read()
        except OSError:
            self.send(slot, 0xB5, f"Error: failed to open file [{path}]!".encode())
            return
        self.send(slot, 0xB1, hashlib.md5(data).hexdigest().encode())
        last = time.time()
        while True:
            if time.time() - last > self.TIMEOUT:
                self.send(slot, 0xB5, b"Error: Machine received cmd timeout!")
                return
            m = self._get()
            if m is None:
                continue
            ptype, payload = m
            last = time.time()
            if ptype == 0xB2:
                n = (len(data) + 8191) // 8192
                self.send(slot, 0xB2, n.to_bytes(4, "big") + (8192).to_bytes(2, "big"))
            elif ptype == 0xB3:
                seq = int.from_bytes(payload[:4], "big")
                chunk = data[(seq - 1) * 8192: seq * 8192]
                if seq < 1 or not chunk:
                    self.send(slot, 0xB5, b"Error: Machine received cmd timeout!")
                    return
                self.send(slot, 0xB3, payload[:4] + chunk)
            elif ptype == 0xB4:
                self.send(slot, 0xB4, b"ok\r\n")
                self.send(slot, 0x90, f"Info: download success: {path}.".encode())
                return
            elif ptype == 0xB5:
                self.send(slot, 0xB5, b"Info: canceled by remote!")
                return


class Client(threading.Thread):
    def __init__(self, sock, addr, machine: Machine, latency: float):
        super().__init__(daemon=True)
        self.sock, self.addr, self.m, self.latency = sock, addr, machine, latency
        self.dec = P.Decoder()
        self.wlock = threading.Lock()
        self.lines: deque = deque()
        self.ev = threading.Event()

    def tx(self, ptype, text: str):
        if self.latency:
            time.sleep(self.latency)
        with self.wlock:
            try:
                self.sock.sendall(P.build(ptype, text.encode()))
            except OSError:
                pass

    def txb(self, ptype, data: bytes):
        with self.wlock:
            try:
                self.sock.sendall(P.build(ptype, data))
            except OSError:
                pass

    def worker(self):
        # G-code lines are handled in order on their own thread (like the firmware's main loop),
        # so a full planner queue delays 'ok' without blocking real-time bytes.
        while True:
            self.ev.wait()
            self.ev.clear()
            while self.lines:
                line = self.lines.popleft()
                for r in self.m.line(line):
                    self.tx(P.NORMAL_INFO, r)

    def run(self):
        log(f"client {self.addr} connected")
        threading.Thread(target=self.worker, daemon=True).start()
        self.slot = self.m.xfer.connect(self)
        last_ver = 0.0
        self.sock.settimeout(0.1)
        while True:
            if time.time() - last_ver > 5:
                self.tx(P.FIRM_VER, "SIM-1.0")
                last_ver = time.time()
            while self.m.msgs:
                self.tx(P.NORMAL_INFO, self.m.msgs.pop(0))
            try:
                data = self.sock.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            if not data:
                break
            for ptype, payload in self.dec.feed(data):
                if ptype == P.CTRL_SINGLE and payload:
                    c = payload[:1]
                    if c == b"?":
                        self.tx(P.STATUS_RES, self.m.status())
                        continue
                    log(f"{self.addr} RT {c!r}")
                    if c == P.RT_HOLD:
                        self.m.hold = True
                    elif c == P.RT_RESUME:
                        self.m.hold = False
                    elif c == P.RT_HALT:
                        self.m.halt()
                        self.tx(P.NORMAL_INFO, "ERROR: Abort during cycle\r\n")
                    elif c == P.RT_STOP:
                        self.m.stop()
                elif ptype == P.CTRL_MULTI and payload.strip() == b"diagnose":
                    # like the firmware: answered with 0x82, no "ok"
                    self.tx(P.DIAG_RES, "{S:0,10000,0,0,25,23|L:0,0|V:1,28|F:0,0|G:%d,0,0,%d,%d|T:0|C:1|E:0,0,0,0,0,1,1,0|P:0,0|I:0|RSSI:-60}"
                            % (1 if self.m.light else 0, 1 if self.m.vacuum else 0, 80 if self.m.vacuum else 0))
                elif 0xB0 <= ptype <= 0xB6:
                    self.m.xfer.frame(self.slot, ptype, payload)
                elif ptype == 0xB7:
                    # which file is playing: "path|md5", or "|" (answered by the Wi-Fi board)
                    j = self.m.job
                    self.tx(0xB7, f"{j['path']}|{j['md5']}" if j else "|")
                elif ptype == P.CTRL_MULTI and payload.startswith(b"simfreeze"):
                    secs = float(payload.split()[1])
                    log(f"{self.addr} frozen for {secs} s")
                    time.sleep(secs)
                    log(f"{self.addr} awake")
                elif ptype == P.CTRL_MULTI and payload.startswith(b"time "):
                    # the Wi-Fi board sets its clock (Makera's app sends this on connect)
                    self.tx(P.NORMAL_INFO, f"time = {payload[5:].decode().strip()}\r\n")
                elif ptype == P.CTRL_MULTI and payload.split(b" ")[0] in (b"ls", b"mkdir", b"mv", b"rm"):
                    # answered by the Wi-Fi board itself, like the real one (no line queue, no LPC)
                    for t, text in self.m.fs_command(payload.decode("latin-1")):
                        self.tx(t, text)
                elif ptype == P.CTRL_MULTI and payload.startswith(b"simxfer drop "):
                    self.m.xfer.drop_upload_seq = int(payload.split()[2])
                    self.tx(P.NORMAL_INFO, "ok\r\n")
                elif ptype == P.CTRL_MULTI:
                    line = payload.decode("ascii", "replace")
                    log(f"{self.addr} LINE {line!r}")
                    self.lines.append(line)
                    self.ev.set()
                else:
                    log(f"{self.addr} type 0x{ptype:02X} {payload!r}")
        self.m.xfer.disconnect(self.slot)
        log(f"client {self.addr} gone")
        self.sock.close()


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.1.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def broadcaster(name, port):
    # Bind to the LAN address and send to its /24 broadcast: on a multi-homed host,
    # 255.255.255.255 leaves by whichever interface the routing table picks.
    ip = local_ip()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind((ip, 0))
    bcast = ip.rsplit(".", 1)[0] + ".255"
    while True:
        msg = f"{name},{ip},{port},0,SIM-1.0".encode()
        try:
            s.sendto(msg, (bcast, P.UDP_DISCOVERY_PORT))
        except OSError as e:
            log(f"broadcast failed: {e}")
        time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="Makera_Z1P_SIM")
    ap.add_argument("--model", choices=["z1", "air"], default="z1", help="air: the Carvera Air's soft limits")
    ap.add_argument("--no-m211", action="store_true", help="M211 just answers ok (firmware without the report)")
    ap.add_argument("--m211-one-line", action="store_true", help="M211's report as one message instead of one per piece")
    ap.add_argument("--port", type=int, default=P.TCP_PORT)
    ap.add_argument("--latency-ms", type=float, default=0)
    ap.add_argument("--no-broadcast", action="store_true")
    ap.add_argument("--heat", type=float, default=0.0,
                    help="spindle warms this many °C/min while turning (cools 6 °C/min when off); halts over --spindle-max")
    ap.add_argument("--spindle-max", type=float, default=75.0, help="spindle temperature limit, °C (config-get answers it)")
    ap.add_argument("--spindle-temp", type=float, default=29.0, help="spindle temperature at start, °C")
    ap.add_argument("--boot-mesh", choices=["empty", "real"],
                    help="start with a leveling grid: 'empty' = |O: reported but every point nan (the Z1 after "
                         "power-on), 'real' = a small grid with values")
    ap.add_argument("--speed", type=float, default=1.0, help="run motion this many times faster (tests)")
    ap.add_argument("--sd", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "sim_sd"),
                    help="folder standing in for the SD card: 'play <name>' plays <sd>/<name>")
    a = ap.parse_args()
    m = Machine()
    m.name = a.name
    m.xfer = Transfers(m)
    m.sd = a.sd
    m.speed = a.speed
    m.heat = a.heat; m.spindle_max = a.spindle_max; m.spindle_temp = a.spindle_temp
    if a.model == "air":
        m.soft = dict(SOFT_AIR)
    m.m211 = not a.no_m211
    m.m211_one_line = a.m211_one_line
    if a.boot_mesh == "empty":
        m.ghost_mesh = -1.351
    elif a.boot_mesh == "real":
        m.mesh = [[0.01, 0.02, -0.01], [0.0, 0.05, 0.03]]
    threading.Thread(target=m.run, daemon=True).start()
    if not a.no_broadcast:
        threading.Thread(target=broadcaster, args=(a.name, a.port), daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", a.port))
    srv.listen(4)
    log(f"sim '{a.name}' on {local_ip()}:{a.port}")
    while True:
        sock, addr = srv.accept()
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        Client(sock, addr, m, a.latency_ms / 1000).start()


if __name__ == "__main__":
    main()
