package com.z1pendant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.files.FilesController
import com.z1pendant.files.FilesController.InfoState
import com.z1pendant.files.RemoteFiles
import com.z1pendant.files.ShareInbox
import com.z1pendant.link.ConnState
import com.z1pendant.link.RemoteEntry
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.platform.epochSeconds
import com.z1pendant.platform.rememberFilePicker
import com.z1pendant.platform.systemPickerHasCancel
import com.z1pendant.platform.utcOffsetSeconds

private val Danger = Color(0xFFF87171)
private val Playing = Color(0xFF86EFAC)
private val WarnBg = Color(0xFF3A2A0B)
private val WarnText = Color(0xFFFDE68A)
private val DropBg = Color(0xFF14532D)
private val DropEdge = Color(0xFF4ADE80)

/** A hold-and-drag in progress: what's being moved, where the finger is (root coordinates), and whether it has moved yet. */
private data class Drag(val names: List<String>, val at: Offset, val start: Offset, val moved: Boolean = false)

/**
 * Files on the Z1's SD card (/sd/gcodes and its folders): Job info, rename, delete, new folder, upload from
 * the phone. Mockup: pendant/mockups/files_mock.html.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel, pickForRun: Boolean = false, onRun: (String) -> Unit = {}, onClose: () -> Unit) {
    val f = vm.files
    val path by f.path.collectAsState()
    val raw by f.entries.collectAsState()
    val loading by f.loading.collectAsState()
    val loadError by f.loadError.collectAsState()
    val sort by f.sort.collectAsState()
    val selected by f.selected.collectAsState()
    val upload by f.upload.collectAsState()
    val conflict by f.conflict.collectAsState()
    val working by f.working.collectAsState()
    val readOnly by f.readOnly.collectAsState()
    val playing by f.playing.collectAsState()
    val fresh by f.justUploaded.collectAsState()
    // Read so the Run gate below follows the machine.
    vm.link.status.collectAsState().value; vm.link.conn.collectAsState().value
    val runBlock = vm.runBlock()
    val entries = remember(raw, sort) { raw?.let { RemoteFiles.sorted(it, sort) } }
    val selecting = selected.isNotEmpty()

    var sheetFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteAsk by remember { mutableStateOf<List<String>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    // Upload opens our "Upload to the Z1" sheet first (it has a Cancel; Android's picker doesn't). The sheet
    // stays open while the phone's picker is up, so backing out of the picker lands on it again.
    var uploadSheet by remember { mutableStateOf(false) }
    val pick = rememberFilePicker { name, bytes -> uploadSheet = false; f.upload(name, bytes) }
    val shared by ShareInbox.file.collectAsState()
    val conn by vm.link.conn.collectAsState()
    /** Where an upload lands: "SD card › gcodes › jobs". */
    val into = "SD card › " + RemoteFiles.crumbs(path).joinToString(" › ") { it.first }

    // ---- hold and drag onto a folder (a row or a crumb) to move ----
    /** Rows on screen by name, and folders you can drop on by full path, in root coordinates. */
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    val folderBounds = remember { mutableStateMapOf<String, Rect>() }
    var drag by remember { mutableStateOf<Drag?>(null) }
    var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    /** The folder under the finger that the dragged items can go into. */
    val dropOn = drag?.takeIf { it.moved }?.let { d ->
        folderBounds.entries.firstOrNull { (p, r) -> r.contains(d.at) && f.canDrop(d.names, p) }?.key
    }
    // Near the top or bottom of the list while dragging: scroll it, so a folder further up or down can be reached.
    LaunchedEffect(drag != null) {
        val edge = with(density) { 56.dp.toPx() }
        while (drag != null) {
            val d = drag; val box = listCoords?.boundsInRoot()
            if (d != null && d.moved && box != null) {
                val by = when {
                    d.at.y < box.top + edge -> -(box.top + edge - d.at.y) / 3
                    d.at.y > box.bottom - edge -> (d.at.y - (box.bottom - edge)) / 3
                    else -> 0f
                }
                if (by != 0f) listState.scrollBy(by)
            }
            kotlinx.coroutines.delay(16)
        }
    }
    LaunchedEffect(path) { rowBounds.clear(); folderBounds.clear(); drag = null }

    LaunchedEffect(Unit) { f.open() }
    PlatformBackHandler { if (!f.back()) onClose() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.positionInRoot() }) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
            // ---- header: title bar, or "N selected" while selecting ----
            if (selecting) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0E2A3D)),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = f::clearSelection) { Icon(Icons.Filled.Close, "Stop selecting") }
                    Text("${selected.size} selected", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { deleteAsk = selected.toList() }, enabled = readOnly == null) {
                        Icon(Icons.Filled.Delete, "Delete", tint = if (readOnly == null) Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text(if (pickForRun) "Select File" else "Files", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { newFolder = true }, enabled = readOnly == null) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
                    IconButton(onClick = { uploadSheet = true }, enabled = readOnly == null && upload == null) { Icon(Icons.Filled.FileUpload, "Upload from the phone") }
                }
            }

            // ---- where we are ----
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("SD", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                val crumbs = RemoteFiles.crumbs(path)
                crumbs.forEachIndexed { i, (label, p) ->
                    Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    val last = i == crumbs.lastIndex
                    // While dragging, the folders above this one are drop targets too (the way to move something up).
                    val target = drag?.moved == true && !last && f.canDrop(drag!!.names, p)
                    Text(label, fontSize = 13.sp,
                        color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = (if (last) Modifier else Modifier
                            .onGloballyPositioned { folderBounds[p] = it.boundsInRoot().inflate(with(density) { 8.dp.toPx() }) }
                            .clickable { f.goTo(p) })
                            .clip(RoundedCornerShape(6.dp))
                            .then(when {
                                dropOn == p -> Modifier.background(DropBg).border(1.dp, DropEdge, RoundedCornerShape(6.dp))
                                target -> Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                else -> Modifier
                            })
                            .padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                for (s in RemoteFiles.Sort.entries) FilterChip(selected = sort == s, onClick = { f.setSort(s) }, label = { Text(s.name) })
            }

            readOnly?.let { why ->
                Text(why, color = WarnText, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(WarnBg).padding(10.dp))
            }

            upload?.let { u ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer).padding(10.dp)) {
                    Text("Uploading ${u.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Before the first packet (reconnecting, the start handshake): a moving bar and what it waits for.
                    if (u.note != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    else LinearProgressIndicator(progress = { if (u.total > 0) u.sent.toFloat() / u.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.note ?: "${RemoteFiles.sizeLabel(u.sent)} of ${RemoteFiles.sizeLabel(u.total)}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = f::cancelUpload) { Text("Cancel") }
                    }
                }
            }

            // ---- the list ----
            PullToRefreshBox(isRefreshing = loading && entries != null, onRefresh = f::refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    entries == null && loadError != null -> Message(loadError!!)
                    entries == null -> Message("Reading the SD card…")
                    entries.isEmpty() -> Message(if (path == RemoteFiles.ROOT) "No files yet." else "This folder is empty.")
                    else -> LazyColumn(Modifier.fillMaxSize()
                        .onGloballyPositioned { listCoords = it }
                        // Hold a row: it's selected (as before). Keep holding and drag: the selection follows the
                        // finger and drops into the folder it's let go over. Watched on the Initial pass so that,
                        // once the hold has fired, the list doesn't scroll and the row doesn't also take a tap.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val lc = listCoords ?: return@awaitEachGesture
                                val at0 = lc.localToRoot(down.position)
                                val name = rowBounds.entries.firstOrNull { it.value.contains(at0) }?.key ?: return@awaitEachGesture
                                val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                                        if (c == null || !c.pressed || c.isConsumed ||
                                            (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                    }
                                    true
                                }
                                if (endedEarly != null) return@awaitEachGesture
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                f.select(name)
                                val canMove = readOnly == null
                                drag = Drag(emptyList(), at0, at0)
                                val slop = viewConfiguration.touchSlop
                                while (true) {
                                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                    c.consume()
                                    if (!c.pressed) break
                                    val d = drag ?: break
                                    val at = lc.localToRoot(c.position)
                                    drag = when {
                                        d.moved -> d.copy(at = at)
                                        canMove && (at - d.start).getDistance() > slop ->
                                            d.copy(at = at, moved = true, names = (f.selected.value + name).toList())
                                        else -> d.copy(at = at)
                                    }
                                }
                                val d = drag
                                val target = d?.takeIf { it.moved }?.let { dd ->
                                    folderBounds.entries.firstOrNull { (p, r) -> r.contains(dd.at) && f.canDrop(dd.names, p) }?.key
                                }
                                drag = null
                                if (d != null && target != null) f.move(d.names, target)
                            }
                        }, state = listState) {
                        items(entries, key = { it.name }) { e ->
                            val full = RemoteFiles.child(path, e.name)
                            DisposableEffect(full) { onDispose { rowBounds.remove(e.name); folderBounds.remove(full) } }
                            EntryRow(e, isSelected = e.name in selected, selecting = selecting,
                                isPlaying = playing == full, isFresh = e.name in fresh,
                                lifted = drag?.moved == true && e.name in drag!!.names,
                                dropHere = dropOn == full,
                                modifier = Modifier.onGloballyPositioned { c ->
                                    val r = c.boundsInRoot()
                                    rowBounds[e.name] = r
                                    if (e.isDir) folderBounds[full] = r
                                },
                                onClick = {
                                    when {
                                        selecting -> f.toggleSelected(e.name)
                                        e.isDir -> f.enter(e.name)
                                        pickForRun -> if (runBlock == null) onRun(full)
                                        else -> sheetFor = e
                                    }
                                },
                                onLongClick = { f.toggleSelected(e.name) })
                        }
                    }
                }
            }

            // ---- footer ----
            val files = entries.orEmpty().filter { !it.isDir }
            Text(working ?: "${files.size} file${if (files.size == 1) "" else "s"} · ${RemoteFiles.sizeLabel(files.sumOf { it.size })}" +
                    when {
                        selecting -> if (readOnly == null) "   ·   hold and drag onto a folder to move" else ""
                        pickForRun -> "   ·   " + (runBlock ?: "tap a file to set it up")
                        else -> "   ·   tap a file, hold to select or drag"
                    },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
    }

    // ---- what's being dragged, just above and right of the finger so the finger doesn't hide it ----
    drag?.takeIf { it.moved }?.let { d ->
        val one = d.names.singleOrNull()
        val isDir = one != null && entries.orEmpty().any { it.name == one && it.isDir }
        val to = dropOn?.let { RemoteFiles.crumbs(it).last().first }
        Row(Modifier
            .offset { IntOffset((d.at.x - screenOrigin.x + 20.dp.toPx()).toInt(), (d.at.y - screenOrigin.y - 64.dp.toPx()).toInt()) }
            .clip(RoundedCornerShape(10.dp))
            .background(if (to != null) DropBg else Color(0xFF1E293B))
            .border(1.dp, if (to != null) DropEdge else MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isDir) Icons.Filled.Folder else Icons.Outlined.Description, null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text((one ?: "${d.names.size} items") + (to?.let { "  →  $it" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
        }
    }
    }

    // ---- the file's sheet ----
    sheetFor?.let { e ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, sheetState = state, containerColor = Color(0xFF111A2B)) {
            FileSheet(vm, f, e, path, readOnly, playing == RemoteFiles.child(path, e.name), runBlock,
                onRename = { sheetFor = null; renameFor = e },
                onDelete = { sheetFor = null; deleteAsk = listOf(e.name) },
                onRun = { sheetFor = null; onRun(RemoteFiles.child(path, e.name)) })
        }
    }

    // ---- "Upload to the Z1": where it goes, the way into the phone's picker, and Cancel ----
    if (uploadSheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        ModalBottomSheet(onDismissRequest = { uploadSheet = false }, sheetState = state, containerColor = Color(0xFF111A2B)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text("Upload to the Z1", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text("  Goes into ", fontSize = 13.sp, color = muted)
                    Text(into, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(onClick = pick).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Choose a file on this phone…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Downloads, Drive, anywhere", fontSize = 12.sp, color = muted)
                    }
                }
                if (!systemPickerHasCancel) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF162033))
                        .padding(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.SwipeRight, null, tint = muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("The next screen is Android's and has no Cancel button. To back out of it, swipe in from the " +
                            "left or right edge. You'll come back here.", fontSize = 12.5.sp, color = Color(0xFFCBD5E1))
                    }
                }
                FilledTonalButton(onClick = { uploadSheet = false }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Cancel") }
            }
        }
    }

    // ---- a file shared from another app: ask before sending it ----
    shared?.let { s ->
        val ready = conn == ConnState.Connected && raw != null && readOnly == null && upload == null
        val wait = when {
            ready -> null
            readOnly != null -> readOnly
            upload != null -> "Another upload is running."
            conn == ConnState.Disconnected -> "Not connected to a Z1. Connect in Settings first."
            conn != ConnState.Connected -> "Connecting to the Z1…"
            else -> "Reading the SD card…"
        }
        AlertDialog(onDismissRequest = { ShareInbox.file.value = null },
            title = { Text("Upload to the Z1?") },
            text = {
                Column {
                    Text(s.name, fontWeight = FontWeight.SemiBold)
                    Text(RemoteFiles.sizeLabel(s.bytes.size.toLong()), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Goes into $into. Spaces in the name become _.")
                    wait?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = WarnText, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ShareInbox.file.value = null; f.upload(s.name, s.bytes) }, enabled = ready) { Text("Upload") }
            },
            dismissButton = { TextButton(onClick = { ShareInbox.file.value = null }) { Text("Cancel") } })
    }

    deleteAsk?.let { names ->
        AlertDialog(onDismissRequest = { deleteAsk = null },
            title = { Text(if (names.size == 1) "Delete ${names.first()}?" else "Delete ${names.size} items?") },
            text = {
                Column {
                    Text((if (names.size == 1) "It's removed" else "They're removed") + " from the Z1's SD card and can't be brought back." +
                        if (names.any { n -> entries.orEmpty().any { it.name == n && it.isDir } }) " A folder is deleted with everything in it." else "")
                    if (names.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        names.take(8).forEach { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (names.size > 8) Text("and ${names.size - 8} more", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { f.delete(names); deleteAsk = null }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("Cancel") } })
    }

    renameFor?.let { e ->
        val dot = e.name.lastIndexOf('.').takeIf { !e.isDir && it > 0 }
        val ext = dot?.let { e.name.substring(it) } ?: ""
        var base by remember(e) { mutableStateOf(dot?.let { e.name.substring(0, it) } ?: e.name) }
        AlertDialog(onDismissRequest = { renameFor = null },
            title = { Text("Rename") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(base, { base = it }, singleLine = true, modifier = Modifier.weight(1f))
                    if (ext.isNotEmpty()) Text(ext, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                }
            },
            confirmButton = { TextButton(onClick = { f.rename(e, base + ext); renameFor = null }, enabled = base.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } })
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = false },
            title = { Text("New folder in ${RemoteFiles.crumbs(path).joinToString(" › ") { it.first }}") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Folder name") }) },
            confirmButton = { TextButton(onClick = { f.makeFolder(name); newFolder = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } })
    }

    conflict?.let { c ->
        val existing = entries.orEmpty().firstOrNull { it.name == c.name }
        AlertDialog(onDismissRequest = { f.resolveConflict(FilesController.Clash.Cancel) },
            title = { Text("${c.name} is already on the Z1") },
            text = {
                existing?.let { Text("${RemoteFiles.sizeLabel(it.size)} · ${RemoteFiles.timeLabel(it.modified, epochSeconds(), ::utcOffsetSeconds)}") }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { f.resolveConflict(FilesController.Clash.Replace) }) { Text("Replace it") }
                    TextButton(onClick = { f.resolveConflict(FilesController.Clash.KeepBoth) }) {
                        Text("Keep both (${RemoteFiles.uniqueName(c.name, entries.orEmpty().map { it.name }.toSet())})")
                    }
                    TextButton(onClick = { f.resolveConflict(FilesController.Clash.Cancel) }) { Text("Cancel") }
                }
            })
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: RemoteEntry, isSelected: Boolean, selecting: Boolean, isPlaying: Boolean, isFresh: Boolean,
                     lifted: Boolean, dropHere: Boolean, modifier: Modifier,
                     onClick: () -> Unit, onLongClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The hold itself is watched by the list (it starts a drag); here it's only offered to accessibility services.
    Row(modifier.fillMaxWidth().alpha(if (lifted) 0.4f else 1f).clip(RoundedCornerShape(8.dp))
        .background(when { dropHere -> DropBg; isSelected || isPlaying -> Color(0xFF0E2A3D); else -> Color.Transparent })
        .then(if (dropHere) Modifier.border(1.5.dp, DropEdge, RoundedCornerShape(8.dp)) else Modifier)
        .clickable(onClick = onClick)
        .semantics { onLongClick("Select") { onLongClick(); true } }
        .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        when {
            selecting -> Icon(if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else muted)
            e.isDir -> Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
            isPlaying -> Icon(Icons.Filled.PlayArrow, null, tint = Playing)
            else -> Icon(Icons.Outlined.Description, null, tint = muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (isPlaying) Tag("Playing", Playing, Color(0xFF123B23))
                else if (isFresh) Tag("just uploaded", MaterialTheme.colorScheme.primary, Color(0xFF0E2A3D))
            }
            Text(if (e.isDir) "folder" else "${RemoteFiles.sizeLabel(e.size)} · ${RemoteFiles.timeLabel(e.modified, epochSeconds(), ::utcOffsetSeconds)}",
                fontSize = 12.sp, color = muted)
        }
        if (e.isDir && !selecting) Icon(Icons.Filled.ChevronRight, null, tint = muted)
    }
}

@Composable
private fun Tag(text: String, fg: Color, bg: Color) {
    Text(text, color = fg, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(bg)
        .padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
private fun FileSheet(vm: AppViewModel, f: FilesController, e: RemoteEntry, dir: String, readOnly: String?, isPlaying: Boolean, runBlock: String?,
                      onRename: () -> Unit, onDelete: () -> Unit, onRun: () -> Unit) {
    val full = RemoteFiles.child(dir, e.name)
    val infos by f.jobInfo.collectAsState()
    val info = infos[full]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val canChange = readOnly == null && !isPlaying
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        Text(e.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("${RemoteFiles.sizeLabel(e.size)} · ${RemoteFiles.timeLabel(e.modified, epochSeconds(), ::utcOffsetSeconds)} · ${dir.removePrefix("/sd")}",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetAction(Icons.Filled.PlayArrow, "Run", null, enabled = !isPlaying && runBlock == null, modifier = Modifier.weight(1f), onClick = onRun)
            SheetAction(Icons.Filled.Build, "Job info", null, enabled = info !is InfoState.Loading, modifier = Modifier.weight(1f)) { f.loadJobInfo(e) }
            SheetAction(Icons.Filled.DriveFileRenameOutline, "Rename", null, enabled = canChange, modifier = Modifier.weight(1f), onClick = onRename)
            SheetAction(Icons.Filled.Delete, "Delete", null, enabled = canChange, tint = Danger, modifier = Modifier.weight(1f), onClick = onDelete)
        }
        if (isPlaying) Text("This is the file the Z1 is playing.", color = Playing, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        else (readOnly ?: runBlock)?.let { Text(it, color = WarnText, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }

        when (info) {
            null -> {}
            InfoState.Loading -> Text("Reading the file's header…", color = muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            is InfoState.Failed -> Text(info.why, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            is InfoState.Ready -> Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                val ji = info.info
                Row { Text("Job info", color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                      Text("read in ${(info.seconds * 10).toInt() / 10.0} s", color = muted, fontSize = 12.sp) }
                ji.stockHeightMm?.let { InfoRow("Stock height", "$it mm") }
                ji.zero?.let { InfoRow("Zero", it) }
                Row(Modifier.padding(top = 8.dp)) {
                    Text("Tools", color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${ji.tools.size}", color = muted, fontSize = 12.sp)
                }
                if (ji.tools.isEmpty()) Text("No tools listed in the header.", fontSize = 13.sp, color = muted)
                ji.tools.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text("T${t.number}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.width(40.dp))
                        Text(t.description, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        t.diameterMm?.let { Text("$it mm", fontSize = 13.sp, color = muted, modifier = Modifier.padding(start = 8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(100.dp))
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, sub: String?, enabled: Boolean, modifier: Modifier = Modifier,
                        tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = tint.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Text(label, fontSize = 13.sp, color = tint.copy(alpha = alpha), modifier = Modifier.padding(top = 4.dp))
        sub?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
    }
}
