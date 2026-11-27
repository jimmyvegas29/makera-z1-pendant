package com.z1pendant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.update.Release
import com.z1pendant.update.UpdateController

/** Settings › App: the version, the startup-check switch, Check now, and Update when there is one. */
@Composable
fun UpdatesSection(u: UpdateController) {
    val enabled by u.enabled.collectAsState()
    val available by u.available.collectAsState()
    val status by u.status.collectAsState()
    val progress by u.progress.collectAsState()
    val checking by u.checking.collectAsState()
    Text("Z1 Pendant ${u.current}", fontSize = 14.sp)
    SwitchRow("Check for updates when the app starts", enabled) { u.setEnabled(it) }
    status?.let { Text(it, fontSize = 13.sp) }
    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PendantOutlinedButton(onClick = { u.checkNow() }, enabled = !checking && progress == null) { Text("Check now") }
        if (available != null) {
            Button(onClick = { u.install() }, enabled = progress == null) { Text(if (u.canInstall) "Update" else "Get the update") }
            TextButton(onClick = { u.showPrompt() }) { Text("What's new") }
        }
    }
    Text(
        "The check asks GitHub for the latest release; nothing about you or your machine is sent." +
            if (u.canInstall) " Update downloads the new version, checks it, and Android asks you to confirm the install. " +
                "Settings and hotkeys are kept."
            else " An iPhone app can't update itself: Get the update opens the release page, then install the new .ipa " +
                "with Sideloadly as before. Settings and hotkeys are kept.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Version x is available": the release notes, Update / Later / Skip this version. */
@Composable
fun UpdateDialog(u: UpdateController, p: UpdateController.Prompt) {
    val status by u.status.collectAsState()
    val progress by u.progress.collectAsState()
    val r = p.release
    val downloading = progress != null
    AlertDialog(
        onDismissRequest = { u.dismiss() },
        title = { Text("Z1 Pendant ${r.version} is available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You have ${u.current}.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (r.title != r.tag) Text(r.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(Release.plainNotes(r.notes).ifBlank { "(No release notes.)" }, fontSize = 13.sp)
                }
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                // Download progress and anything that went wrong (permission, busy, errors).
                status?.takeIf { downloading || !it.startsWith("Version ") }?.let { Text(it, fontSize = 13.sp) }
                if (!u.canInstall) Text("Install it with Sideloadly, as before: Get the update opens the release page with the .ipa.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { u.install() }, enabled = !downloading) { Text(if (u.canInstall) "Update" else "Get the update") }
        },
        dismissButton = {
            if (!downloading) Row {
                TextButton(onClick = { u.skip() }) { Text("Skip this version") }
                TextButton(onClick = { u.dismiss() }) { Text("Later") }
            }
        },
    )
}
