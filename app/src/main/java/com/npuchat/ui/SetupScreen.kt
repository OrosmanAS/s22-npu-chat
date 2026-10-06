package com.npuchat.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.npuchat.model.ModelVariant

@Composable
fun SetupScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val variant = settings.variant

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startDownload() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }

    val busy = (download != null && download?.error == null) || (importing != null && importing?.error == null)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Get the model", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Qwen3.5-2B compiled for the Snapdragon 8 Gen 1 NPU (Hexagon v69). Text only, Apache-2.0. " +
                "It is stored in the app's private storage and checked with SHA-256 before use.",
            style = MaterialTheme.typography.bodyMedium,
        )

        ModelVariant.entries.forEach { v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = v == variant, onClick = { vm.updateSettings { it.copy(variant = v) } }, enabled = !busy)
                Column {
                    Text(v.label)
                    Text(formatGb(v.sizeBytes), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val d = download
        if (d != null) {
            if (d.error != null) {
                Text(d.error, color = FailRed)
            } else {
                Text("${d.phase.replaceFirstChar { it.uppercase() }}: ${formatGb(d.done)} / ${formatGb(d.total)}")
                LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
                Text("Continues in the background (see the notification). Interrupted downloads resume.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel") }
            }
        }

        val im = importing
        if (im != null) {
            if (im.error != null) {
                Text(im.error, color = FailRed)
                OutlinedButton(onClick = vm::dismissImportError) { Text("OK") }
            } else {
                Text("Importing: ${formatGb(im.done)} / ${formatGb(im.total)}")
                LinearProgressIndicator(progress = { im.fraction }, modifier = Modifier.fillMaxWidth())
                Text("Keep the app open until the copy finishes.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (!busy) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.startDownload()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download from Hugging Face (${formatGb(variant.sizeBytes)})") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Import a .litertlm file from storage")
            }
            Text(
                "Wi-Fi recommended. Either way the app needs about 2.2 GB of free storage.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
