# Z1 Pendant: developer notes

How the app talks to the machine, how jogging works, and what has been confirmed on a real Z1 Pro.
For installing and using the app, see the [README](../README.md).

## Source layout
- `android/`: the Gradle project (Kotlin 2.2, Compose Multiplatform 1.9, minSdk 31). Build the
  Android app with `gradlew assembleRelease`.
  - `shared/`: Kotlin Multiplatform module with nearly all the code, for Android and iOS
    (`iosArm64`, `iosSimulatorArm64`; static framework `Shared`). In `src/commonMain/kotlin/com/z1pendant/`:
    - `link/MachineLink.kt`: the transport interface plus `Dialect` (backend-specific G-code).
      `link/makera/*` is the stock-firmware backend (TCP frames, discovery; sockets are ktor-network).
      Another controller (e.g. LinuxCNC) would implement the same interface.
    - `jog/JogEngine.kt`: turns dial/pad/volume clicks into short relative moves (below).
    - `hotkeys/*`: hotkey model, JSON import/export (kotlinx-serialization, same file format as before),
      runner with guards (ask first, Idle only).
    - `ui/*`: all the Compose UI: main screen, handwheel, jog pad, hotkey grid and editor, Move box,
      settings, console. `AppViewModel.kt`: state and actions.
    - `probe/`: the probe wizard's model (`ProbeModel.kt`: setups, Makera's M480 commands, the
      pendant's own touch sequences, which probe is in by its setter height) and controller
      (`ProbeController.kt`: load/calibrate, runs, undo and set-zero).
    - `platform/Platform.kt`: what each phone supplies (preferences, hotkeys.json, haptics, clock,
      back button, hotkey file picker); `util/Fmt.kt`: number formatting identical to Java's.
  - `shared/src/androidMain`: SharedPreferences "z1pendant", filesDir/hotkeys.json, vibrator, file pickers.
  - `shared/src/iosMain`: NSUserDefaults, Documents/hotkeys.json, Taptic feedback, and
    `MainViewController()` for the iOS app. Not compiled on Windows; the Xcode project comes later on a Mac.
  - `shared/src/androidUnitTest`: formatter vs Java, hotkey JSON vs org.json, frames, probing
    sequences, and the link and the probe wizard's controller against `z1sim.py`
    (`gradlew :shared:testDebugUnitTest`).
  - `app/`: the Android shell: `MainActivity` (key events, Wi-Fi low-latency lock, keep-screen-on,
    adb test hooks), manifest, icon.
- `python/z1proto.py`: wire protocol (frames, CRC, status and discovery parsers) with a self-test.
- `python/z1client.py`: PC test client: `discover`, `probe` (read-only), `second` (two TCP clients
  at once), `send`, `jog`. Anything that can move the machine needs `--motion`.
- `python/z1sim.py`: machine simulator: discovery broadcast, TCP 2222, G0/G1/G90/G91/G10 L20/G53/$H/$X,
  M220, M821/M822, M851/M852, `diagnose`, hold/resume/halt, a 32-block queue with the firmware's 100 ms
  start delay, and soft limits X −207..−1, Y −206..−1, Z −102..−1 that alarm like the real machine
  (`--model air`: the Carvera Air's X −302, Y −212, Z −121). `M211` reports them like the firmware;
  `--no-m211` stands in for firmware without the report.
- `python/z1sim_probe.py`: probing for the simulator: a block and a pocket to probe, G38.2/G38.6 with
  `[PRB:]`, Makera's M480.1–.10 routines, M491 and `M6 T9999` with the tool-change wait (M490.2
  continues) and the setter calibration. `simprobe dead` makes the probe never trigger;
  `simprobe len <dz>` makes the setter trip dz mm higher, standing in for another probe.
- `python/z1upload.py`: send a file to the SD card (`/sd/gcodes/…`) or fetch one, over Wi-Fi. One file,
  standard library only, so a Fusion add-in can carry it. Refuses while a job runs and never overwrites
  the playing file. The simulator answers uploads like the Wi-Fi board (`simxfer drop <seq>` loses one
  data packet); `python/test_z1upload.py` runs the cases against it. Like Makera's app it sets the Wi-Fi
  board's clock first (`time <Unix seconds>`), so files aren't dated 1980.
- `LiveDownloadTest` (unit tests) checks the app's file download on a real machine, read-only; it runs only
  with `Z1_LIVE_HOST=<ip>` (and optionally `Z1_LIVE_FILE=/sd/gcodes/<name>`) set.
- `python/fsprobe.py`: send the Wi-Fi board's file commands (`ls -s`, `mkdir`, `mv`, `rm`) and print every reply
  frame raw. The simulator answers them like the board (`Machine.fs_command`).

## Files screen
The top bar's folder icon opens `FilesScreen` (`ui/FilesScreen.kt`, state in `files/FilesController.kt`; mockup
`mockups/files_mock.html`): browse `/sd/gcodes` and its folders, Job info, rename, delete (also several at once),
new folder, upload from the phone (Android document picker, iOS Files picker via `rememberFilePicker`).
Everything is answered by the Wi-Fi board; nothing moves the machine.
- Commands, measured on the Z1 2026-11-16 (`reference/file_commands_notes.md` in the parent project): `ls -s <dir>`
  answers 0x83 chunks (`name/ 0 YYYYMMDDhhmmss` for folders, `name  size YYYYMMDDhhmmss` for files, UTC, spaces
  as 0x01) and 0x84 `Load directory finished.`; `mkdir`, `mv <from> <to>`, `rm` answer 0x84 `ok` (plus 0x90 info) or
  0x85 with the reason. `rm` on a folder deletes everything in it. `mv`/`rm` keep the `.md5` cache in step.
  0x84 is also an "ok": `MakeraLink.fsCommand` takes these frames while a command waits, so it's never taken as
  a G-code ack.
- Upload is `z1upload.py`'s protocol in Kotlin (`MakeraLink.uploadFile`): cancel any leftover transfer, set the
  clock, B0 + B1 (MD5, `util/Md5.kt`), then answer the board's B2/B3 requests; 0x91 or a leftover is retried for 15 s.
- Job info reads only the file's header (`MachineLink.readFileHead`, stopped once a line of G-code has arrived):
  stock height and Z zero from the Carvera community post's header, each tool once (`JobFileParser`)
  (`files/JobInfo.kt`). Only what Fusion's own post output carries: the stock's X × Y size isn't in it, so
  the pendant doesn't show one, and rows the file doesn't have are left out.
- Rules: nothing writes to the card (upload, delete, rename, new folder) while the Z1 is running, paused, changing
  tools or homing, and never the playing file. Names are cleaned like the Fusion plugin's (spaces → `_`). An upload
  to a taken name asks: replace, keep both (`name_2.cnc`), or cancel. Newest first by default.
- Tests: `FilesParseTest` (listing, labels, names, Job info, MD5 against Java's), `FilesSimTest` (list, mkdir,
  upload into a folder, cancel, rename, header read, delete, the controller's rules) and `LiveFilesTest` (opt-in
  with `Z1_LIVE_HOST`, works only in a throwaway `_pendant_live_test` folder; `Z1_LIVE_INFO_FILE` adds a Job info read).

## Run a job
`ui/RunScreen.kt` (state in `run/RunController.kt`, pure parts in `run/RunModel.kt`; mockup
`mockups/run_mock.html`). Opened from the Run tile (`BuiltIn.RUN_JOB`, which opens Files as "Select File";
added once to existing hotkey sets) or a file's sheet.
- How Studio starts a job, captured 2026-11-17 (`reference/captures/studio_job_start_20261117.md` in the parent
  project): `buffer M495 X<minX>Y<minY>[C<maxX>D<maxY>][O<dx>F<dy>][A<w>B<h>I<nx>J<ny>H<h>]P1`, then
  `play <path>`. X/Y are the toolpath's min corner in work coordinates (feed moves' end points,
  `Toolpath.extents`, which matches Studio on can_lid_op1.cnc); P1 = go there at safe Z first. M495 never
  sets an origin: Studio uses `G10L2P0X…Y…` (machine coordinates) separately. The pendant sends `M370`
  (clear the old leveling grid) before the buffer line.
- Origin, applied on Next from step 1: Anchor 1 = `G10L2P0X<anchor1+offX>Y<anchor1+offY>` with Anchor 1 read
  live (`config-get sd coordinate.anchor1_x/_y`); where the spindle is = `G10L20P0X0Y0` (sent once per choice).
  "Same as CAM" = the community post's "X/Y Offset" header lines (the WCS from Fusion's part attach point:
  only right if the setup's part position is set and the stock sits in the bracket).
- Custom anchors (`run/Anchors.kt`, `ui/AnchorsScreen.kt`; built 2026-11-24 instead of PR #10 writing
  `coordinate.anchor1_*`, which would also move the tool setter = Anchor 1 + 181 mm): named points in machine
  coordinates, each axis optional, saved in prefs as JSON under `anchors.<machine name>`. Run's "Custom anchor"
  (radio + dropdown, no list) sends `G10L2P0X<x+off>Y<y+off>[Z<z>]`; with a saved Z the Z probe is off by
  default, without one it's locked on as with Anchor 1. Settings › Anchors: New (probe a corner / an edge with
  the wizard, where the spindle is, or typed), Edit, Delete, Re-probe (saved vs measured, then keep / save /
  save as new). After the wizard sets a zero, the anchor is read with `$#` (`[G54:x,y,z,…]`, the work offset
  itself, so Z holds with any tool whose length is measured); no answer in 3 s falls back to MPos − WPos for X/Y.
- The firmware's M495 (community `ATCHandler.cpp`) inserts its own M6 T0 unless the active tool is 0, and
  refuses an uncalibrated probe. So before Scan now or a start that probes, the pendant gets the laser probe
  in itself: `M6 T0` (swap + setter calibration) or, "already in", `M493.2 T0` then `M491` (setting the
  number marks the length unmeasured, so this order). `ProbeController.checkAs = LASER` compares the setter
  height with the laser probe's learned length; another probe's length stops it. The check holds while the
  machine says T0.
- Scan now = `M495 X Y C D` on its own; done on "Done ATC" (or the M494.2 after the trace); the live dot is the
  machine position. Not homed: the firmware silently does nothing, so no echo in 4 s reads as a failure.
- Assist switches: `M331[.n]` on / `M332[.n]` off, state from `|S:` fields 4, 7, 8, 10 (n = 0, 1, 2, 4).
  Plain M331 answers "extend out mode" on this firmware.
- Mesh: `|O:` in the status while a grid is active (the chip); `M375.1` prints it (`Mesh.parse` takes the plain
  rows and the human-readable table). The firmware applies an active grid to every move (jogs too) until
  `M370`, and outside the probed area the stock build uses the nearest grid edge's value, so the pendant
  clears it when a job ends or is cancelled. On connect, a grid with the machine Idle and no job (three
  statuses without `|P:`) opens a Clear / Keep popup (`AppViewModel.meshPrompt`), but only after `M375.1`
  shows values: right after power-on the Z1 can report `|O:-1.351` with an empty grid (all 15 × 15 points
  `nan`, gone again a few seconds later; seen 2026-11-22), which corrects nothing. `Mesh.parse` reads `nan`
  as NaN; `Mesh.largest` is null for an empty grid. Simulator: `z1sim.py --boot-mesh empty|real`.
- Tool change popup (`ToolChangePopup`): state `Tool`, the wanted tool from `|T:` field 3, Continue = `M490.2`.
- Tests: `RunModelTest` (extents, the M495 line against Studio's, origin lines, CAM offset, M6 guard, mesh,
  assist/mesh status fields). The simulator (`python/z1sim.py`) answers config-get, buffer, M495 (scan, Z probe,
  leveling, P), M331/M332, M370/M375.1 and G10 L2; run on the Dell it's reached from the phone with
  `adb reverse tcp:2222 tcp:2222` and 127.0.0.1 (the Dell's firewall blocks 2222).

## Protocol (Makera app decompile + community firmware `MakeraFrame.h`)
`86 68 | len u16 BE | type | payload | CRC u16 BE | 55 AA`, len = 1 + payload + 2,
CRC-16/XMODEM (poly 0x1021, **init 0**) over len + type + payload. Test vector: `86 68 00 03 71 3b e5 55 aa`.
- To the machine: `0xA1` one real-time byte (`?` status, `!` hold, `~` resume, `0x18` halt, `0x19` stop,
  `0x1A` keep-alive); `0xA2` one command line (no newline).
- From the machine: `0x81` status `<Idle|MPos:x,y,z,a,b|WPos:…|F:feed,set,override|S:…|…>`,
  `0x90` text (`ok`, errors, alarms), `0x82` diagnose reply, `0x71` firmware version.
- Discovery: the machine's ESP32 broadcasts `name,ip,port,busy,state` to **UDP 3333**.
- The pendant jogs with short moves to absolute machine positions, `G53 G1 X… F…`. G53 applies to its
  own line only, so a jog never changes the machine's modal state: no G91 is left behind if the link
  drops or the app dies mid-jog. (It used to send `G91 G1` and restore `G90` afterwards.)
- The stock firmware does have `$J X… F…` (relative, F = a fraction of the axis max rate), but it sends
  no `ok`, which the line flow control relies on, and it tops out near 1200 mm/min, so the app doesn't
  use it. See the measurements below.

## Connection
A status query (`?`) goes out every 200 ms (50 ms while jogging). After 1.5 s without a status reply:
- **A line may not have reached the machine** (no `ok` yet, and no status query sent after it was
  answered): the socket is closed at once and the link reconnects.
- **Everything sent reached the machine**: the link is **Stalled**. Lines not yet sent fail ("machine not
  answering"), new ones are refused, the jog engine drops its clicks, and the socket stays open. When
  statuses come back it is Connected again on the same socket, and the job being watched is kept.
  After 25 s it gives up and reconnects.

Sockets are closed with `SO_LINGER 0`: a reset, which throws away bytes the phone hasn't delivered yet.
A normal close keeps delivering them. On 2026-11-14 a Z1 went silent for 15–17 s about every minute
(ping, broadcasts and TCP; it went away by itself the next day). A jog line sent just before such a gap
sat in the phone's socket, and with a normal close it could have run when the Z1 woke, long after the
user had stopped. A line the machine already has runs either way, so only a line that may not have
arrived forces the reset. TCP keeps order: once a status query sent after a line is answered, that
line has arrived (a `$H` that is still homing, say). `z1sim.py`'s `simfreeze <s>` line fakes such a gap.

## Jog engine
Every 40 ms the clicks collected become one `G53 G1` move to the next machine position. Feed = hand speed (clicks in the last
200 ms × step) plus a correction that keeps about 120 ms of motion queued:
`feed = vHand + (lead − vHand·0.12 s) / 0.3 s`, clamped to 5 mm/min … axis max.

Why the buffer: Smoothie holds a queue that was empty for `queue_delay_time_ms` (100 ms) before running
it. If the queue runs dry mid-turn, the machine stops and restarts 100 ms late, which feels laggy.

The **lead** (commanded but not yet travelled, dead-reckoned from the last status) plus waiting clicks
is capped at `max(min(5 clicks, 5 mm), v²/2a + v·tick + vHand·(0.12 + 0.1) + 1 click)`. Clicks beyond
that are dropped (one thud vibration per 150 ms at most), so when input stops or the link drops, the
axis stops within that distance. Moves are clamped to the soft limits while the machine is homed.

**Soft limits come from the machine** (`link/SoftLimits.kt`), so a Carvera Air (X −302, Y −212, Z −121,
the community firmware's `config2.default`) isn't held to a Z1's travel. On connect, once the machine is
Idle with no job, the app sends `M211`; the firmware (stock Z1 and community `Robot.cpp`) answers
`Soft endstops are Enabled,X min = -207.000,X max = -1.000,...`. Each piece is its own print, so it
arrives as separate lines (`Soft endstops are Enabled`, `,X min = -302.000`, … `ok`, seen on a real
Carvera Air); `SoftLimits.Report` collects them up to the `ok` and joins them. (0.1.11 took the first
line alone, found no numbers and used the Z1's.) The simulator sends it split the same way;
`--m211-one-line` sends one line. The mins are the config's `soft_endstop.*_min`; the maxes are fixed at
−1 in the firmware code. No `ok` within 3 s: whatever lines came. No report, or one with no numbers: the
mins from `config-get sd soft_endstop.*_min`, max −1; nothing at all: the Z1's. The jog clamp, typed moves, Run's
bed map and its "runs past the bed" check all use them; with soft limits off in the firmware the app
doesn't clamp either. Not used: the community firmware's `soft_endstop.rotary_y_min` (a tighter Y min with
the 4th axis fitted), which `M211` doesn't report and the stock Z1 firmware doesn't have.

A **jog session** starts from the machine position in a status that arrived *after* the first click
(never an older reading, which could be stale after a Wi-Fi gap), taken while the machine is
**settled**: Idle, or "Run, no job, feed 0" (spindle on, axes still) that has lasted 0.6 s. Every move
starts with ~0.1 s of exactly "Run, feed 0" (the queue delay) while the status still shows the old
position, so a session started there would aim from a stale spot. The session ends once the machine is
settled **at its target** (or, if it stopped elsewhere, after 2 s still). During a session the reported
position must stay on the commanded path (between the start and the targets sent, ±0.02 for status
rounding) and X, Y and A must stay put. If not, something else moved the machine: the session ends
(thud, clicks dropped) and the next click starts over from a fresh reading.

**Z while moving reads differently.** Idle, the Z1 reports the planned position; moving, the motors'
position. On 2026-11-20 Z read +0.3492 mm higher while moving, at every X/Y, with no leveling grid loaded
(no `|O:`), cause unknown. A leveling grid adds its correction the same way. So Z is never an "other
axis" in the path check, and the engine learns the offset during X/Y/A jogs (still Z, moving vs Idle)
and takes it off moving Z readings (`JogEngine.planned`), so a Z jog's lead and path use planned Z. Before
it has been learned, a moving Z gets 1 mm of slack. Before this, the 0.35 mm "jump" ended every jog the
moment the machine moved, and the next click restarted from a stale position: once it sent the machine
back 4 mm.

**Trace:** while jogging, the engine logs every click, status, session start/end and move sent with its
numbers under logcat tag `JogTrace` (`adb logcat -v epoch -s JogTrace:D JogEngine:I MakeraLink:*`).

Jog pad: a tap is one click; a held button repeats after 0.4 s at `max(120 ms, step / axis max speed)`,
and letting go drops any clicks not yet sent.

The tap's click is sent when the finger lifts, and the repeat runs on a fixed clock. Nothing is sent
on touch: the Z1 (Smoothie) plans a move that is alone in its queue to end at a standstill and never
re-plans a move once it is running (`Block::calculate_trapezoid` returns early `if (is_ticking)`), so a
step sent on touch made every hold stop after its first step.

While a button repeats, the hand speed is the exact repeat rate and the feed is a constant 97 % of it
(`JogConfig.holdFeedFactor`), with no lead correction. On a real Z1, any feed change from move to move
is audible, and a feed even a few % faster than the clicks arrive empties the queue, so the machine
stops for a moment and starts again. A little slower, the queue fills slowly up to the cap, where a
dropped click costs nothing because moves are still queued. With the lead correction, every jump in the
lead estimate went into the feed (F918, F704, F921, F665, … at the 1 mm step). The repeat runs on a fixed
clock because a delay after each click adds up (124 ms instead of 120 on a Galaxy S21), which made the
real supply slower than the feed.

The lead's dead reckoning (every jog, not only the pad) counts from when the reported position was
read, not from the latest status: the Z1 refreshes the position in its status only every 250–370 ms and
repeats it in between, so a position is as old as the first status that carried it (trusted up to
400 ms, `JogConfig.positionMaxAgeMs`; after that the machine is standing still). Counting from the
latest status made the lead look up to ~0.3 s of travel too long, so clicks were dropped at the cap
while the machine had little queued.

## Haptics
VibrationEffect composition primitives: TICK per detent, CLICK every 10th, THUD when clicks are dropped
or blocked, a double click at a soft limit. Strength is in Settings.
Phones without primitives (the Galaxy S21 has none) get short one-shot pulses at an amplitude that
follows the strength (12 ms detent, 20 ms every 10th, 40 ms thud, two 20 ms pulses 60 ms apart at a
limit). A phone without amplitude control either plays the predefined tick/click effects at a fixed
strength; Settings then says the slider only switches vibration on or off.
On Android 13+ they play as `USAGE_PHYSICAL_EMULATION` (vibrations that stand in for a hardware part,
here the handwheel's detents). Without attributes Android files short primitives as touch feedback, and
the phone's **Touch feedback** switch then silences them; many people turn that off to quiet the
keyboard. Android's master **Use vibration & haptics** switch still applies, and the strength slider
(0 = off) is the app's own off switch. Android 12 has no such usage and keeps the default (touch).
iOS (not tested yet): light / medium / heavy impacts for tick / every 10th / thud, two rigid knocks
for a soft limit, a selection click for button presses.

## Machine outputs
`diagnose` (sent as an `0xA2` line) returns an `0x82` frame
`{S:…|L:…|V:…|F:…|G:light,beep,extendin,extendout,extendout_pwm|T:…|C:…|E:…|P:…|I:…|RSSI:…}` with no `ok`.
G[0] = light (M821 on / M822 off), G[3] = vacuum / chip-clear output (`switch.extendout`, M851 on /
M852 off). The app reads it every 3 s (not while jogging) for the Light and Vacuum toggle tiles.

## Spindle
Start: `M3 S<rpm>`; stop: `M5`. The status line carries both things the tile needs, every poll:
`|S:current,target,override%,…|` (current RPM is live; target stays at the default 10000 while off)
and `|T:tool,offset,target tool,…|` (active tool). The firmware halts the machine on M3 unless
1 ≤ tool ≤ 999 on the stock Z1 (Makera `makeraz1firmware`; "ERROR: No tool or probe tool!"; the community firmware allows < 100000), so the
start box refuses first: tool −1 = empty collet, and the probe numbers 0, 9999 and ≥ 999990
(community `ATCHandler`: "a 3-axis probe must be installed (tool 0, 9999 or tool >= 999990)"; the
community docs only name 999990+ as the 3D probe, 9999 is undocumented). In that case the box shows a T1–T99 stepper (− / +, hold to run faster) and
**Set tool**, which sends `M493.2 T<n>` (no motion; Makera's app sends it too; saved by the firmware, tool
length marked not measured). Stopping always asks too: the only sign of a job is the Run state, which
doesn't say who started the spindle. Running = current RPM > 0, or an M3 sent in the last 10 s (spin-up). The Z1
spindle is 150 W, 0–13,000 RPM (Makera's spec); the config holds motion `delay_on_s 8` after M3 and
`delay_off_s 4` after M5.

Override slider (drawer): in RPM, full bar = 13,000. Release sends `M223 S<desired / programmed × 100>`
to 0.01 % (programmed = the status's target field; assumed to exclude the override, as in the community
`SpindleControl`, **unverified on stock**). Range: 50 % of programmed (Makera's slider floor) up to
13,000 or 200 %, whichever is lower: the stock firmware clamps M223 to 50–200 % (community: 10–300 %), same as Makera's slider.

## Controls drawer
The bottom drawer (feed override, Hold, Resume, Reset) opens by itself on Hold, Pause or Alarm, and when
the machine moves without the pendant having started it (e.g. a job from Makera Studio). It closes again
at Idle if it opened itself. `OwnMotion` tracks the pendant's own motion: jogs, typed moves, console lines and
hotkeys count from the moment they are sent until the machine has been busy and is Idle again (or 1.5 s if it
never starts). The firmware acks a line when it is queued, not when the move ends, so the ack can't be
used. It used to count a jog as the pendant's only for 1.5 s after the last click, and typed moves not at
all, so a long jog step or a typed move opened the drawer. The view model re-checks it once per status (`ownMotionActive`),
not while the screen redraws.

## Jobs (confirmed on the Z1 2026-11-15, log in the parent project's reference/)
- **A job is playing iff the status has `|P:line,percent,elapsed s|`** (stock: 3 fields). It isn't there
  for M3, jogs or lines we send; it stays through tool changes and pauses, and through the job's own
  Idle moments (state reads Idle around tool changes and for ~4 s at the end), so "Idle" doesn't
  mean done. On a normal finish it reads 100 % for ~1.2 s, then goes; after an abort it just goes.
  The Wi-Fi board has a canned fallback status with `P:1234,50,1200`: ignored.
- `line` = 1-based file line of the block running (comments count); `percent` = share of the file
  read (sits at ~91 % over the last lines, then 100); `elapsed` stops during a tool-change wait.
  The app shows line ÷ total lines (once the file is read) for the percent and time left: the
  firmware's % is of the file read, and it reads ahead, so it hit 100 % ~100 lines before the end
  of a 11,600-line engraving (tiny moves at 30 mm/min) and the time left went blank.
- On a disconnect the app forgets the job (it may end or change while we're away) and asks for
  the file again on reconnect. If it was watching a job and comes back to no job, that job ended:
  overrides go back to 100 % as at a normal end. Back into a running job, it leaves them alone.
- State `Tool` = waiting for a manual tool change; `|T:active,TLO,target|` (target = the tool asked
  for; active reads −1 while the collet is empty during the swap).
- The state reads `Run` while the spindle turns even with nothing moving, so a pendant M3 counts as
  its own motion (no drawer pop).
- **Which file:** an empty frame of type `0xB7` → `0xB7 "path|md5"` (`"|"` when none). The Wi-Fi
  board answers it; it never reaches the motion controller. The `progress` text command also works
  (`file: …, 21 % complete, elapsed time: …, est time: …`).
- **Download** (Wi-Fi board, stop-and-wait; full spec: parent project `reference/download_protocol_notes.md`):
  `0xB0 "download <path>\n"` (spaces as 0x01) → `0xB1 md5`; `0xB2` → u32 packet count + u32 8192;
  `0xB3 u32 seq` (from 1) → seq + up to 8192 bytes; `0xB4` → "ok"; `0xB5` cancels. One transfer at a
  time across clients (others get `0x91`); 9 s idle aborts it. A QuickLZ copy (`/sd/gcodes/.lz/`,
  starts `00 00`) isn't handled yet. The app downloads the playing file once per md5, paced 25 ms per
  packet, and reads its tools (`JobFileParser`: Fusion `(T1 name D=… - type)` and Makera
  `;@MKR|TOOL|number=…|name=…` headers; `M6` lines, skipping changes to the tool already in).
- Overrides live in RAM in the firmware (makeraz1firmware): M220 → `Robot::seconds_per_minute`, M223 →
  the spindle `factor`; both reply just `ok`, and the status shows them (`F:…,override`, `S:…,factor`).
  Nothing resets the spindle factor but `M223 S100` or a reboot; the feed override resets on `M2`
  only (`M30` resets it only in grbl mode, which the Z1 isn't), and the probing macros end with
  `M220 S100`. Jobs end with `M30`, so both overrides carry into the next job.
- When a job ends (`|P:` gone for 3 statuses), the app puts feed and spindle overrides back to 100 %:
  the firmware keeps them across jobs, and an 85 % left over from earlier made a later job's S13000
  run at ~11,050.
- `?` in tool names (e.g. `(T4  30? ? 0.3 engraver …)`) are in the file itself: Fusion's post writes
  ASCII and replaces °, ×, ″ … with `?`. Name tools in ASCII in Fusion to avoid it.
- The Wi-Fi board takes about 3 clients at once; more get `0x91` "maximum number of client connections".
- Bare `S` lines don't change the spindle speed on the stock firmware; only `S… M3` does.

## Temperatures
- `|S:` fields 5 and 6 = spindle and electronics-box ("power cabinet") thermistors, °C (P1.31 / P1.30 on
  the motion board; `S:0.0,10000.0,100.0,0,36.3,25.0,…`). `MachineStatus.spindleTemp` / `boxTemp`.
- Limits: `config-get sd temperature_control.spindle.max_temp` (Jimmy's SD config: 75) and
  `…power.max_temp` (not in the SD config: "is not in config"), read once per connection after the
  mesh check. Missing → the built-in config's 70 / 60 (`TempLimits.DEFAULT`, LPC dump 2026-11-08).
- Halts (community Kernel.h `HALT_REASON`, `|H:` while halted): 9 spindle overheated ("ERROR: Spindle
  overheated, max - 75.0, current - 75.1", seen 2026-11-22), 14 power cabinet overheated, 41 spindle
  alarm = the MUGOR driver's fault line P0.19 ("ERROR: Spindle alarm triggered -  power off/on
  required"). The firmware re-checks the thermistors once a second when not halted, so clearing an
  overheat above the limit halts again: the alarm sheet holds Clear alarm until it's under.
- `TempTrace`: a sample per 2 s while a job runs (and through an Alarm after it, to show the cooling),
  halved past 1,200 samples. Tool marks: "Done ATC" arms one; the next status with a real tool number
  (not −1, not state Tool) places it at the "Done ATC" time; same tool again → no mark. A job with no
  tool change gets its first mark when the spindle starts.
- Warning within 5 °C of a limit (1 °C hysteresis): `tempWarn`, banner in the job panel, one `thud`.
- Simulator: `z1sim.py --heat N --spindle-temp T --spindle-max M` (warms N °C/min while the spindle
  turns, halts like the Z1). The sim runs M3/M5 when it reads them, not in step with the moves.
  Tests: `TempTraceTest`, `TempSimTest`.

## Zero / undo
`zero` sends `G10 L20 P0 <axis>0`. Pressed again while the axis still reads 0.000 and hasn't moved, it
sends `G10 L20 P0 <axis><previous value>` instead. The Zero hotkeys (X/Y/Z/A, XY, XYZ, selected) share it.

## Typed moves
The Move box sends `G90 G0 <axis><value>` (Go to) or `G53 G0 <axis><machine end position>` (Move by,
computed from a status at most 1 s old), and refuses targets past the soft limits before sending anything.

## Test hooks
Debug builds only (`gradlew assembleDebug`); a release build ignores these extras, so other apps
can't use them to move the machine.
- `adb shell am start -n com.z1pendant/.MainActivity --es host <ip> [--ei port 2222] [--es name …]` connects.
- `… --ei testclicks <±n> --ei testms <ms> [--ei teststep <index>]` injects a burst of dial clicks.
- Simulator: `python3 python/z1sim.py`, then connect the app to that computer's IP (or
  `adb reverse tcp:2222 tcp:2222` and `--es host 127.0.0.1`).

- `… --es updateurl http://<pc>:8000/latest.json` runs the update check against a stand-in for GitHub's
  release JSON (same shape as `releases/latest`; its APK URL can point at the same server). The dialog
  shows on the main screen.

## App updates
`update/UpdateController.kt`, with the phone's side in `Updater` (`AndroidUpdater`, `IosUpdater`).
- **Check:** at start (Settings › App can turn it off), at most every 6 h; between checks the last reply
  (kept in the prefs) is reused, so a found update is still offered. `GET
  https://api.github.com/repos/jimmyvegas29/makera-z1-pendant/releases/latest`, no token (GitHub allows 60
  an hour per IP), `User-Agent: Z1-Pendant/<version>`; nothing else is sent. A failed check is quiet (no
  internet at the machine is normal); Settings says why. `releases/latest` never returns drafts or
  pre-releases.
- **Version:** `BuildInfo.VERSION`, generated into `:shared` from `pendantVersionName` in
  `android/gradle.properties` (the Android app's `versionName`/`versionCode` come from there too). The
  .ipa workflow writes the tag's version into `Info.plist`. Compared number by number (`0.1.10` >
  `0.1.9`).
- **Dialog:** once per version found by the startup check, on the main screen only; Later asks again next
  start, Skip this version stops the dialog (Settings still offers it).
- **Android install:** waits while a job runs or the machine moves (installing closes the app). Downloads
  the release's `.apk` into the cache, checks size and SHA-256 against the asset's `digest` from GitHub
  (refuses without one), then the package name, a newer `versionCode` and the signing certificate
  (`GET_SIGNING_CERTIFICATES`; a self-built app gets a clear "uninstall first" instead of Android's
  conflict error). Then a `PackageInstaller` session; its status comes back to a not-exported receiver,
  which opens Android's confirm dialog (`STATUS_PENDING_USER_ACTION`). Needs `REQUEST_INSTALL_PACKAGES`;
  without the user's "Install unknown apps" OK, Android's page for it is opened and the user taps Update
  again. The cached APK is deleted at the next start.
- **iPhone:** check only; Update opens the release page (an app can't install itself, and a sideloaded one
  is signed with each user's own Apple ID).

## Edge probing
`probe/ProbeModel.kt` (`ProbeSequences.edge`, `EdgeFit`), the wizard in `ui/ProbeWizard.kt`; spec from
Jimmy's review in the parent project's `field_test/edge_probing_feedback.md`.
- Setup starts with nothing picked but the probe (`Pick`, `missingPick`); the values behind the picks are still
  remembered in the prefs. Corner / hole / block axes are set all on in `openWizard`.
- **Touch the top first**: over the part; top, then out by the start offset (G53 G0), down to the side depth
  (G53 G1), G38.2 in; between touches back out and up before moving along. **Start from here**
  (`edgeFromHere`): no Z at all; G38.2 straight in by the search distance, back to the start line, along the
  start line to the next touch, back to the start position at the end (`Steps.BACK_OFF`).
- **Multiple points**: `edgeCount` (2–8) over `edgeLength`, spacing = length / (count − 1), always Measure
  only. `EdgeFit`: least squares through the touches; angle, delta over the length, per 100 mm, residuals
  (+ = out of the part), straightness = peak to valley (3+ touches), noise = atan(0.005 / √Σ(t − t̄)²).
- `ProbeSetup.travelCheck` checks every touch's along position (and, touching the top first, the step out)
  against `SoftLimits`; Run greys out with the reason.
- Opened for a zero (Run's origin, Anchors): Multiple points isn't offered. **Save as anchor** on the result
  (`AnchorsController.probedPoint` + `saveProbed`): the work zero the run set, read back with `$#`, or the
  measured X/Y; X/Y/Z chips choose what's kept.
- Sim: `z1sim_probe.py` `simprobe skew <deg>`, `lean <deg>`, `bow <mm>` (hollow sides) shape the part.
- Tests: `ProbeTest` (sequences against a fake part, `EdgeFit`, travel check, picks), `ProbeSimTest` (2 points
  on a skewed part, start from here with 4 touches), `AnchorsSimTest` (save from a probe result).

## Bed map
`map/BedMap.kt`, `ui/MapScreen.kt`, `AppViewModel.mapGo` (spec: parent project `field_test/bed_map_spec.md`).
- The soft-limit box (`SoftLimits.bed`, else the Z1's), back at the top. Grid 5/9/17 points edge to edge
  (`map.grid`), anchors with both X and Y, and X0 Y0 (MPos − WPos); targets within 0.01 mm merge into one
  with all their names. The tool setter is drawn from the last calibration's setter touch (`probe.setterXY`).
- Move here = `G90`, `G53 G0 Z-2` (Safe Z, on every time the map opens), `G53 G0 X Y`. Lift ends at Z −2,
  Move when Idle at the target; Stop = reset. Idle somewhere else = "stopped short".
- The bottom panel has a fixed height so the map never moves when the card changes. The map also opens
  over the probe wizard from Move the probe into place.
- Tests: `MapTest` (grid, merged targets, snapping, move lines), `MapSimTest` (Safe Z on and off).

## Confirmed on a real Z1 Pro (stock firmware 1.1.3.0.1.13)
- Discovery broadcast: `Makera_Z1P_xxxxxx,<ip>,2222,0,Idle`. The 5th field is the machine state; "busy"
  stayed 0 with Makera Studio connected.
- Status has no `H:` field and the firmware sends no periodic `0x71` version packets.
- G-code and `$` lines get `ok`; shell commands (`version`) reply with text only, so the app waits for
  an `ok` only on G-code / `$` lines. Errors can arrive split across packets.
- Several TCP clients at once work (Makera Studio, the app, test clients), each getting its own replies.
- **Upper soft limit = the homed position, machine −1.000.** A move past it alarms ("Soft Endstop X was
  exceeded"); that's why the app's "Z up" goes to `G53 G0 Z-2`, not Z0. The machine homes at power-on.
- Dial jogs at 0.01 and 1 mm per click, M821/M822 light, M851/M852 vacuum: all confirmed.
- Status round trip over Wi-Fi: ~30 ms median from a PC. On a Pixel, Wi-Fi power save gave 18–300 ms
  until the app held `WIFI_MODE_FULL_LOW_LATENCY` while visible (then mostly 17–42 ms).

## Jog commands compared on a real Z1 (stock firmware 1.1.3)
Same X moves, 300 mm/min, status polled every 20 ms from a PC over Wi-Fi:

| Command | Start of a 5 mm move | 5 mm took | `ok` | Modal state after |
|---|---|---|---|---|
| `$J X-5 F0.25` | 229 ms | 1.15 s | none | unchanged (G90) |
| `G91 G1 X-5 F300` + `G90` | 250 ms | 1.21 s | yes | G91 until the `G90` |
| `G53 G1 X<pos> F300` | 226 ms | 1.21 s | yes | unchanged (G90) |

The start delay is the same for all three (it is mostly the Wi-Fi bridge and polling), so `$J`'s
skipped queue delay doesn't show. `$J F0.05` ran at about 60 mm/min, so its rate scale tops out near
1200 mm/min. A burst of ten `$J X-0.05` 40 ms apart all ran (0.5 mm exactly).

## Still unverified
- The Run flow on the real Z1 (built and run against the simulator 2026-11-17): M495 with O/F/A/B/I/J/H letters,
  Scan now outside a job, the "Done ATC" end of a direct M495, the `|O:` format, and `M375.1`'s output.
- Custom anchors on the real Z1: the `$#` reply format (format strings found in the stock LPC dump, not run live)
  and G10 L2 with Z.
- The file download on the real machine (protocol from the Makera app + Wi-Fi board decompiles; run on the simulator).
- Whether the stock firmware takes `M223` with two decimals and what its spindle "target" field means.
- Y/Z upper soft limits (assumed −1 like X; the community `Robot.cpp` sets all three to −1).
- In-app updates: the Android install (PackageInstaller session, the "Install unknown apps" page, the confirm
  dialog) on a real phone, and `IosUpdater` compiled for iOS (written on Windows; the check and dialog
  logic is unit-tested, the GitHub reply checked live).
- Edge probing's Start from here and Multiple points, the bed map's moves and Save as anchor on the real Z1
  (built 2026-11-27, run on the simulator; Jimmy tested the first edge version and the map on the Z1).
- `M211`'s report on a real Z1 and a real Carvera Air (the format strings are in the stock Z1 dump; seen
  only on the simulator so far).
