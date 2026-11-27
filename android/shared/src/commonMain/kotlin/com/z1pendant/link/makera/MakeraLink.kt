package com.z1pendant.link.makera

import kotlinx.coroutines.sync.withLock

import com.z1pendant.link.ConnState
import com.z1pendant.link.Dialect
import com.z1pendant.link.GrblishDialect
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.StatusParser
import com.z1pendant.platform.PLog
import com.z1pendant.platform.nowMs
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Stock Makera Z1 over Wi-Fi: the ESP32 bridges TCP 2222 straight to the LPC's UART.
 *
 * Flow control: lines go out one at a time (window 2) and each waits for its "ok"/"error" reply.
 * If the machine never acks (unverified on stock firmware), the link drops to timed spacing so
 * nothing jams. A status query goes out every [pollMs]. No reply for [stallMs]: if a line may not have
 * reached the machine yet, the socket is reset at once so that line can't run late. Otherwise the link is stalled:
 * nothing more is sent, the socket stays open, and the link carries on if the machine answers again.
 * After [giveUpMs] without a reply the socket is dropped and the link reconnects by itself.
 *
 * Sockets are ktor-network (the same code on Android and iOS).
 */
class MakeraLink(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val giveUpMs: Long = 25_000,
) : MachineLink {
    override val conn = MutableStateFlow(ConnState.Disconnected)
    override val status = MutableStateFlow<MachineStatus?>(null)
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val sent = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val latencyMs = MutableStateFlow<Int?>(null)
    override val outputs = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    @Volatile private var lastDiagAt = 0L
    override val dialect: Dialect = GrblishDialect
    val firmware = MutableStateFlow<String?>(null)
    override val jobFile = MutableStateFlow<com.z1pendant.link.JobFile?>(null)

    // ---- file download (the Wi-Fi board's protocol; reference/download_protocol_notes.md) ----
    // Stop-and-wait: one request, wait for its reply (the board keeps only one request). One
    // download at a time across all clients; Studio can't upload while we hold it, so it's kept short.
    private val dlLock = kotlinx.coroutines.sync.Mutex()
    @kotlin.concurrent.Volatile private var dlReply: kotlinx.coroutines.CompletableDeferred<Pair<Int, ByteArray>>? = null

    /** While an upload runs, the board's requests go here (the board drives an upload; see uploadFile). */
    @kotlin.concurrent.Volatile private var xferFrames: kotlinx.coroutines.channels.Channel<Pair<Int, ByteArray>>? = null

    private fun onDownloadPacket(type: Int, payload: ByteArray): Boolean {
        if (type !in MakeraFrame.DL_START..0xB6 && type != MakeraFrame.REFUSED) return false
        xferFrames?.let { it.trySend(type to payload); return true }
        val d = dlReply ?: return false
        d.complete(type to payload)
        return true
    }

    /** Send one request and wait for its reply; on silence or a 0xB6 ("resend") send it again. */
    private suspend fun dlRequest(type: Int, payload: ByteArray = ByteArray(0), tries: Int = 5,
                                  timeoutMs: Long = 1500): Pair<Int, ByteArray>? {
        repeat(tries) {
            val d = kotlinx.coroutines.CompletableDeferred<Pair<Int, ByteArray>>()
            dlReply = d
            sendRaw(MakeraFrame.build(type, payload))
            val r = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { d.await() }
            if (r != null && r.first != MakeraFrame.DL_RETRY) return r
        }
        return null
    }

    /**
     * End any transfer still running for our connection slot before starting one, and never resend a
     * start (reference/upload_protocol_notes.md §5a, seen on the Z1 2026-11-16). The board's owner is a
     * connection slot: a transfer whose client dropped keeps running, and the next connection given that
     * slot inherits it. Every 0xB0 from the owner queues another handler, and each handler clears the
     * owner when it ends, so a queued one runs ownerless, can't hear us and blocks transfers until its
     * 9 s timeout. One 0xB5 first ends a leftover; with nothing running, or Studio owning the slot,
     * the board drops it.
     */
    private suspend fun dlFlush() {
        val swallow = kotlinx.coroutines.CompletableDeferred<Pair<Int, ByteArray>>()
        dlReply = swallow                                   // a leftover download answers the cancel
        sendRaw(MakeraFrame.build(MakeraFrame.DL_CANCEL, ByteArray(0)))
        kotlinx.coroutines.delay(300)
        dlReply = null
    }

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u32(b: ByteArray, at: Int = 0): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    override suspend fun downloadFile(path: String): String? = download(path, Int.MAX_VALUE, null)
    override suspend fun downloadFile(path: String, onProgress: (Int, Int) -> Unit): String? =
        download(path, Int.MAX_VALUE, null, onProgress)

    override suspend fun readFileHead(path: String, maxBytes: Int, enough: (String) -> Boolean): String? =
        download(path, maxBytes, enough)

    /** Read [path]; stop early (and end the transfer) once [maxBytes] are in or [enough] says so. */
    private suspend fun download(path: String, maxBytes: Int, enough: ((String) -> Boolean)?,
                                 onProgress: ((Int, Int) -> Unit)? = null): String? = dlLock.withLock {
        if (conn.value != ConnState.Connected) return null
        var owner = false
        try {
            dlFlush()
            val start = dlRequest(MakeraFrame.DL_START, "download ${path.replace(' ', '\u0001')}\n".encodeToByteArray(),
                                  tries = 1, timeoutMs = 8000)
            if (start?.first != MakeraFrame.DL_MD5) {
                com.z1pendant.platform.PLog.w("MakeraLink", "download $path refused: ${start?.second?.decodeToString()}"); return null
            }
            owner = true
            val size = dlRequest(MakeraFrame.DL_SIZE) ?: return null
            if (size.first != MakeraFrame.DL_SIZE || size.second.size < 4) return null
            val count = u32(size.second)
            if (count > 1024) { com.z1pendant.platform.PLog.w("MakeraLink", "download $path: $count packets, too big"); return null }   // > 8 MB
            val parts = ArrayList<ByteArray>()
            var got = 0
            var stoppedEarly = false
            for (seq in 1..count) {
                val r = dlRequest(MakeraFrame.DL_DATA, u32(seq)) ?: return null
                if (r.first != MakeraFrame.DL_DATA || r.second.size < 4 || u32(r.second) != seq) return null
                parts += r.second.copyOfRange(4, r.second.size)
                got += r.second.size - 4
                onProgress?.invoke(seq.toInt(), count.toInt())
                if (seq < count && (got >= maxBytes || enough?.invoke(joined(parts).decodeToString()) == true)) { stoppedEarly = true; break }
                kotlinx.coroutines.delay(25)              // paced: the board is also streaming the job
            }
            if (!stoppedEarly) { dlRequest(MakeraFrame.DL_END); owner = false }   // else: the finally cancels it
            val bytes = joined(parts)
            // A QuickLZ copy (from /sd/gcodes/.lz) starts with 00 00: not handled yet.
            if (bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte()) {
                com.z1pendant.platform.PLog.w("MakeraLink", "download $path came compressed; skipped"); return null
            }
            bytes.decodeToString()
        } finally {
            if (owner) dlRequest(MakeraFrame.DL_CANCEL)
            dlReply = null
        }
    }
    private fun joined(parts: List<ByteArray>): ByteArray =
        ByteArray(parts.sumOf { it.size }).also { b -> var o = 0; for (x in parts) { x.copyInto(b, o); o += x.size } }

    // ---- the Wi-Fi board's file commands: ls -s, mkdir, mv, rm (reference/file_commands_notes.md) ----
    // Their replies (0x83 listing, 0x84 done, 0x85 failed) are taken here while a command waits, so the
    // 0x84 "ok" is never mistaken for a G-code line's ack. One command at a time.
    private val fsLock = Mutex()
    @Volatile private var fsSink: ((Int, ByteArray) -> Boolean)? = null

    private fun wirePath(path: String) = path.replace(' ', '\u0001')

    /** Lines of 129+ bytes get mixed with an earlier command's leftovers (reference/file_commands_notes.md). */
    override val fsLineMax: Int get() = 128

    /** Send [line]; returns (ok, final text) or null on no answer. Listing chunks go to [listing]. */
    private suspend fun fsCommand(line: String, timeoutMs: Long = 8000, listing: StringBuilder? = null): Pair<Boolean, String>? =
        fsLock.withLock {
            if (conn.value != ConnState.Connected) return null
            // Never send a line the board would mangle: an over-long rm could delete something else.
            if (line.encodeToByteArray().size > fsLineMax) return false to "the path is too long for the Z1 (over $fsLineMax characters)"
            val done = kotlinx.coroutines.CompletableDeferred<Pair<Boolean, String>>()
            fsSink = { type, payload ->
                when (type) {
                    MakeraFrame.FS_LIST -> { if (listing != null) { listing.append(payload.decodeToString()); true } else false }
                    MakeraFrame.FS_DONE -> { done.complete(true to payload.decodeToString().trim()); true }
                    MakeraFrame.FS_ERROR -> { done.complete(false to payload.decodeToString().trim().replace('\u0001', ' ')); true }
                    else -> false
                }
            }
            try {
                sendRaw(MakeraFrame.line(line))
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { done.await() }
            } finally { fsSink = null }
        }

    override suspend fun listDir(path: String): List<com.z1pendant.link.RemoteEntry>? {
        val text = StringBuilder()
        val r = fsCommand("ls -s ${wirePath(path)}", listing = text) ?: return null
        if (!r.first) return null
        return com.z1pendant.files.RemoteFiles.parseListing(text.toString())
    }

    private suspend fun simple(line: String, timeoutMs: Long = 8000): String? {
        val r = fsCommand(line, timeoutMs) ?: return "the machine didn't answer"
        return if (r.first) null else r.second.ifEmpty { "the machine said no" }
    }

    override suspend fun makeDir(path: String) = simple("mkdir ${wirePath(path)}")
    override suspend fun rename(from: String, to: String) = simple("mv ${wirePath(from)} ${wirePath(to)}")
    override suspend fun delete(path: String) = simple("rm ${wirePath(path)}", timeoutMs = 30_000)   // a folder takes longer

    // ---- upload (reference/upload_protocol_notes.md; the board drives it, we answer) ----
    private class Busy(msg: String) : Exception(msg)

    override suspend fun uploadFile(path: String, data: ByteArray, onProgress: (Long, Long) -> Boolean): String? {
        if (conn.value != ConnState.Connected) return "not connected"
        if (!path.startsWith("/sd/")) return "the path must start with /sd/"
        val md5 = com.z1pendant.util.Md5.hex(data).encodeToByteArray()
        // Set the board's clock first, as Makera's app does, so the file gets today's date (not 1980).
        sendRaw(MakeraFrame.line("time ${com.z1pendant.platform.epochSeconds()}"))
        val giveUpAt = nowMs() + 15_000
        while (true) {
            try {
                return dlLock.withLock { uploadOnce(wirePath(path), data, md5, onProgress) }
            } catch (b: Busy) {
                if (nowMs() >= giveUpAt) return b.message
                delay(1000)
            }
        }
    }

    private suspend fun uploadOnce(wire: String, data: ByteArray, md5: ByteArray, onProgress: (Long, Long) -> Boolean): String? {
        val packet = 4096
        val count = maxOf(1, (data.size + packet - 1) / packet)
        dlFlush()                                           // end any leftover transfer in our slot first
        val frames = kotlinx.coroutines.channels.Channel<Pair<Int, ByteArray>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        xferFrames = frames
        var finished = false
        try {
            sendRaw(MakeraFrame.build(MakeraFrame.UL_START, "upload $wire\n".encodeToByteArray()))
            var last = MakeraFrame.build(MakeraFrame.DL_MD5, md5)
            sendRaw(last)
            var viewSent = false
            var tries = 0
            while (true) {
                val r = kotlinx.coroutines.withTimeoutOrNull(3000) { frames.receive() }
                if (r == null) {
                    if (++tries >= 5) return "the machine stopped answering"
                    sendRaw(last); continue                 // stop-and-wait: resend our last frame (never the start)
                }
                val (t, p) = r
                when (t) {
                    MakeraFrame.REFUSED -> throw Busy("another app is sending or fetching a file: ${p.decodeToString().trim()}")
                    MakeraFrame.DL_CANCEL -> {
                        finished = true
                        val msg = p.decodeToString().trim()
                        if (!viewSent && listOf("time out", "canceled", "too many").any { it in msg.lowercase() })
                            throw Busy("a leftover transfer ended in the way: $msg")
                        return msg.ifEmpty { "the machine cancelled the upload" }
                    }
                    MakeraFrame.DL_RETRY -> { if (++tries >= 5) return p.decodeToString().trim().ifEmpty { "too many retries" }; sendRaw(last) }
                    MakeraFrame.DL_MD5 -> { tries = 0; last = MakeraFrame.build(MakeraFrame.DL_MD5, md5); sendRaw(last) }
                    MakeraFrame.DL_SIZE -> {                // "send the file view": u32 count + u16 packet size
                        tries = 0
                        last = MakeraFrame.build(MakeraFrame.DL_SIZE, u32(count.toLong()) + byteArrayOf((packet ushr 8).toByte(), packet.toByte()))
                        sendRaw(last); viewSent = true
                    }
                    MakeraFrame.DL_DATA -> {
                        tries = 0
                        if (p.size < 4) continue
                        val seq = u32(p)
                        if (!viewSent) { sendRaw(MakeraFrame.build(MakeraFrame.DL_CANCEL)); finished = true; throw Busy("a leftover upload asked for packet $seq") }
                        if (seq < 1 || seq > count) return "the machine asked for packet $seq of $count"
                        val from = ((seq - 1) * packet).toInt(); val to = minOf(data.size, from + packet)
                        if (!onProgress(to.toLong(), data.size.toLong())) {
                            sendRaw(MakeraFrame.build(MakeraFrame.DL_CANCEL)); finished = true
                            return "cancelled"
                        }
                        last = MakeraFrame.build(MakeraFrame.DL_DATA, p.copyOfRange(0, 4) + data.copyOfRange(from, to))
                        sendRaw(last)
                    }
                    MakeraFrame.DL_END -> { finished = true; onProgress(data.size.toLong(), data.size.toLong()); return null }
                    else -> {}
                }
            }
        } finally {
            xferFrames = null
            // Free the board's transfer slot (it also deletes the half-written file). Also when the upload
            // was cancelled from the app: this coroutine is then cancelled, so the send must not be.
            if (!finished) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                sendRaw(MakeraFrame.build(MakeraFrame.DL_CANCEL))
            }
        }
    }

    override fun queryJobFile() {
        if (conn.value != ConnState.Connected) return
        scope.launch { sendRaw(MakeraFrame.build(MakeraFrame.JOB_FILE, ByteArray(0))) }
    }

    private class Pending(val line: String, val quiet: Boolean, val onAck: ((Boolean, String) -> Unit)?, var sentAt: Long = 0)

    private val lock = SynchronizedObject()
    /** One frame at a time on the socket. */
    private val writeLock = Mutex()
    private val selector by lazy { SelectorManager(Dispatchers.IO) }
    private val queue = ArrayDeque<Pending>()
    private val inFlight = ArrayDeque<Pending>()
    @Volatile private var out: ByteWriteChannel? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var target: MachineTarget? = null
    @Volatile private var pollMs = 200L
    @Volatile private var queryAt = 0L
    @Volatile private var lastStatusAt = 0L
    /** Oldest status query not answered yet (0 = none). */
    @Volatile private var firstQueryAt = 0L
    /** Everything sent before this reached the machine: a query sent then was answered, and TCP keeps order. */
    @Volatile private var reachedAt = 0L
    private var job: Job? = null
    /** null = not known yet, true = machine replies "ok" per line, false = it doesn't. */
    @Volatile var acksSeen: Boolean? = null; private set

    private val window = 2
    private val ackTimeoutMs = 4000L
    private val stallMs = 1500L

    override val pendingLines: Int get() = synchronized(lock) { queue.size + inFlight.size }

    override fun setPollIntervalMs(ms: Long) { pollMs = ms.coerceIn(40, 2000) }

    /**
     * The "diagnose" line is answered with a 0x82 frame and no "ok" (the firmware intercepts it
     * before the command queue), so it's sent outside the line queue. Confirmed on the Z1:
     * `{S:…|L:…|V:…|F:…|G:light,…|T:…|C:…|E:…|P:…|I:…|RSSI:…}`; G[0] follows M821/M822.
     */
    override fun refreshOutputs() {
        if (conn.value != ConnState.Connected) return
        lastDiagAt = nowMs()
        scope.launch { sendRaw(MakeraFrame.line("diagnose")) }
    }

    override fun assumeOutput(name: String, on: Boolean) { outputs.value = outputs.value + (name to on) }

    private fun parseDiag(text: String) {
        val body = text.substringAfter('{').substringBefore('}')
        val f = body.split('|').associate { it.substringBefore(':') to it.substringAfter(':').split(',') }
        val m = outputs.value.toMutableMap()
        // G = light, beep, extendin, extendout state, extendout pwm (community SimpleShell::diagnose_command)
        f["G"]?.getOrNull(0)?.trim()?.let { m["light"] = it == "1" }
        f["G"]?.getOrNull(3)?.trim()?.let { m["vacuum"] = it == "1" }
        outputs.value = m
    }

    override fun connect(target: MachineTarget) {
        disconnect()
        firmware.value = null              // belongs to the previous machine
        jobFile.value = null               // asked again for whatever job is running now
        this.target = target
        job = scope.launch { runLoop(target) }
    }

    override fun disconnect() {
        job?.cancel(); job = null
        closeSocket()
        target = null
        failAll("disconnected")
        conn.value = ConnState.Disconnected
        status.value = null
        latencyMs.value = null
    }

    private suspend fun runLoop(t: MachineTarget) {
        var first = true
        while (scope.isActive && target == t) {
            conn.value = if (first) ConnState.Connecting else ConnState.Lost
            first = false
            try {
                // Linger 0: closing the socket drops whatever it still holds unsent (a reset, not a
                // graceful close), so a line stuck in it can't reach the machine later.
                val s = withTimeout(3000) {
                    aSocket(selector).tcp().connect(t.host, t.port) { noDelay = true; lingerSeconds = 0 }
                }
                socket = s; out = s.openWriteChannel(autoFlush = false)
                lastStatusAt = nowMs()
                queryAt = 0; firstQueryAt = 0; reachedAt = 0
                val reader = scope.launch { readLoop(s) }
                queryAt = nowMs(); firstQueryAt = queryAt
                sendRaw(MakeraFrame.rt('?'.code))
                // Wait for the first status before calling it connected.
                val t0 = nowMs()
                while (status.value == null || status.value!!.atMs < t0) {
                    if (nowMs() - t0 > 2500) throw LinkException("no status reply")
                    delay(20)
                }
                outputs.value = emptyMap()           // light state etc. belong to the machine we just reached
                conn.value = ConnState.Connected
                pumpLoop()
                reader.cancel()
            } catch (e: Exception) {
                PLog.w(TAG, "link: ${e.message}")
            }
            // Disconnected (this job cancelled): disconnect() cleans up and sets the state.
            currentCoroutineContext().ensureActive()
            closeSocket()
            failAll("link lost")
            if (target != t) break
            conn.value = ConnState.Lost
            latencyMs.value = null
            delay(1500)
        }
    }

    /** Runs while connected: status polls, line sending, ack timeouts, loss detection. */
    private suspend fun pumpLoop() {
        while (scope.isActive && socket?.isClosed == false) {
            val now = nowMs()
            // Output states every 3 s, but not while jogging (fast status polls mean a jog is on).
            if (now - lastDiagAt > 3000 && pollMs >= 200) refreshOutputs()
            val silentMs = now - lastStatusAt
            if (silentMs > giveUpMs) throw LinkException("no status for $silentMs ms")
            // The machine went quiet (a Wi-Fi gap, or the Z1's network stalling).
            val stalled = silentMs > stallMs
            if (stalled && conn.value == ConnState.Connected) {
                // A line sent after the last answered query may still be in the phone's socket, and
                // would run whenever the machine wakes: reset the socket now, which throws it away.
                // (A line the machine has, like a $H still homing, runs either way.)
                val unsure = synchronized(lock) { inFlight.count { it.sentAt >= reachedAt } }
                if (unsure > 0) throw LinkException("no status for $silentMs ms, $unsure line(s) maybe not delivered: dropped")
                // Everything sent reached the machine: keep the socket and send nothing more until
                // the machine answers again.
                PLog.w(TAG, "no status for $silentMs ms: stalled")
                conn.value = ConnState.Stalled
                latencyMs.value = null
                failQueued("machine not answering")
            } else if (!stalled && conn.value == ConnState.Stalled) {
                PLog.i(TAG, "answering again")
                conn.value = ConnState.Connected
            }
            if (queryAt == 0L && now - lastStatusAt >= pollMs || queryAt != 0L && now - queryAt > 600) {
                queryAt = now
                if (firstQueryAt == 0L) firstQueryAt = now
                sendRaw(MakeraFrame.rt('?'.code))
            }
            var toSend: Pending? = null
            synchronized(lock) {
                // Ack timeouts: release the slot so a missing "ok" never jams the pendant.
                // Confirmed on the Z1 (fw 1.1.3.0.1.13): G-code / $ lines get "ok"; a timeout here
                // means something is wrong, so it's logged but never changes the flow-control mode.
                while (!stalled && inFlight.isNotEmpty() && now - inFlight.first().sentAt > ackTimeoutMs) {
                    val p = inFlight.removeFirst()
                    PLog.w(TAG, "no ok for '${p.line}' in $ackTimeoutMs ms")
                    p.onAck?.invoke(true, "")
                }
                if (!stalled && queue.isNotEmpty() && inFlight.size < window) {
                    val p = queue.removeFirst()
                    p.sentAt = now
                    // Shell commands (version, ls, …) reply with text and no "ok": don't wait for one,
                    // or the next line's "ok" would be matched to the wrong command.
                    if (expectsOk(p.line)) inFlight.addLast(p) else p.onAck?.invoke(true, "")
                    toSend = p
                }
            }
            toSend?.let {
                PLog.d(TAG, "-> ${it.line}")
                sendRaw(MakeraFrame.line(it.line))
                if (!it.quiet) sent.tryEmit(it.line)
            }
            delay(if (toSend != null) 1 else 5)
        }
    }

    private suspend fun readLoop(s: Socket) {
        val dec = MakeraFrame.Decoder()
        val buf = ByteArray(4096)
        try {
            val ins = s.openReadChannel()         // throws if the socket was already replaced/closed
            while (true) {
                val n = ins.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                dec.feed(buf, n) { type, payload ->
                    if (!onDownloadPacket(type, payload) && fsSink?.invoke(type, payload) != true) onPacket(type, payload.decodeToString())
                }
            }
        } catch (_: Exception) {
        }
        if (socket === s) closeSocket()
    }

    private fun onPacket(type: Int, text: String) {
        val now = nowMs()
        when (type) {
            MakeraFrame.STATUS_RES -> {
                lastStatusAt = now
                if (queryAt != 0L) { latencyMs.value = (now - queryAt).toInt(); queryAt = 0 }
                // Replies come in order, so this answers the oldest query out.
                if (firstQueryAt != 0L) { reachedAt = firstQueryAt; firstQueryAt = 0 }
                StatusParser.parse(text, now)?.let { status.value = it }
            }
            MakeraFrame.FIRM_VER -> firmware.value = text.trim()
            MakeraFrame.DIAG_RES -> parseDiag(text)
            // "path|md5" of the playing file, or "|" when none.
            MakeraFrame.JOB_FILE -> {
                val path = text.substringBefore('|').trim(); val md5 = text.substringAfter('|', "").trim()
                jobFile.value = if (path.isEmpty()) null else com.z1pendant.link.JobFile(path, md5)
            }
            else -> {
                for (raw in text.split('\n')) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val isOk = line.startsWith("ok", true)
                    val isErr = line.startsWith("error", true)
                    if (isOk || isErr) {
                        acksSeen = true
                        val p = synchronized(lock) { inFlight.removeFirstOrNull() }
                        p?.onAck?.invoke(isOk, line)
                        if (isOk && p?.quiet == true) continue      // a jog move's "ok"
                    }
                    messages.tryEmit(line)
                }
            }
        }
    }

    override fun sendLine(line: String, quiet: Boolean, onAck: ((ok: Boolean, reply: String) -> Unit)?) {
        if (conn.value != ConnState.Connected) {
            val why = if (conn.value == ConnState.Stalled) "machine not answering" else "not connected"
            if (!quiet) messages.tryEmit("not sent ($why): $line")
            onAck?.invoke(false, why); return
        }
        synchronized(lock) { queue.addLast(Pending(line, quiet, onAck)) }
    }

    override fun feedHold() { rt('!'.code); sent.tryEmit("! (feed hold)") }
    /**
     * `~` only ends a feed hold ("Hold"). A file's M600 suspends the job ("Pause"), and the firmware
     * ignores `~` then: only the `resume` command (or M601) ends it, moving back to the saved XYZ and
     * restoring the spindle. Confirmed on the Z1 2026-11-24.
     */
    override fun resume() {
        if (status.value?.state.equals("Pause", true)) sendLine("resume")
        else { rt('~'.code); sent.tryEmit("~ (resume)") }
    }
    override fun reset() {
        synchronized(lock) { queue.clear() }
        rt(0x18); sent.tryEmit("Ctrl-X (reset)")
    }

    private fun rt(c: Int) { scope.launch { sendRaw(MakeraFrame.rt(c)) } }

    private suspend fun sendRaw(frame: ByteArray) {
        try {
            writeLock.withLock { out?.let { o -> o.writeFully(frame); o.flush() } }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PLog.w(TAG, "send: ${e.message}")
            closeSocket()
        }
    }

    /** Lines not sent yet: they will never go out on this socket. */
    private fun failQueued(why: String) {
        val dropped = synchronized(lock) { queue.toList().also { queue.clear() } }
        dropped.forEach { it.onAck?.invoke(false, why) }
    }

    private fun failAll(why: String) {
        val dropped = synchronized(lock) { (inFlight + queue).also { inFlight.clear(); queue.clear() } }
        dropped.forEach { it.onAck?.invoke(false, why) }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null
    }

    private class LinkException(msg: String) : Exception(msg)

    companion object {
        const val TAG = "MakeraLink"
        private val GCODE = Regex("""^\s*(\$|[GMTFSXYZABN]\s*[-+.\d])""", RegexOption.IGNORE_CASE)
        fun expectsOk(line: String) = GCODE.containsMatchIn(line)
    }
}
