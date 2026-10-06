package com.npuchat.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.npuchat.engine.EngineState
import com.npuchat.model.ModelBackend
import com.npuchat.model.ModelStore
import com.npuchat.model.ModelVariant

/** Every SM8450 NPU model with its download / use / delete actions, plus import. */
@Composable
fun ModelList(vm: AppViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val revision by vm.modelRevision.collectAsStateWithLifecycle()
    var pendingDownload by remember { mutableStateOf<ModelVariant?>(null) }
    var confirmDelete by remember { mutableStateOf<ModelVariant?>(null) }
    var confirmUse by remember { mutableStateOf<ModelVariant?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let(vm::downloadModel)
        pendingDownload = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }

    val downloading = download?.takeIf { it.error == null }
    val engineLoaded = engine.state != EngineState.IDLE

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelVariant.entries.forEachIndexed { i, v ->
            if (i == 0 || ModelVariant.entries[i - 1].backend != v.backend) {
                Text(
                    if (v.backend == ModelBackend.NPU) "NPU models (Hexagon v69, LiteRT-LM)" else "CPU models (llama.cpp, MoE, weights read from storage)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Re-read the files whenever the revision changes.
            val ready = remember(revision) { ModelStore.isReady(ctx, v) }
            val active = settings.variant == v
            val thisDownload = downloading?.takeIf { it.variant == v }
            Card(
                colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else CardDefaults.cardColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(v.label + if (active) "  ·  active" else "", style = MaterialTheme.typography.titleSmall)
                    Text(v.summary, style = MaterialTheme.typography.bodySmall)
                    if (v.experimental) Text(
                        "Experimental: bigger than RAM, very slow, may freeze the phone until it restarts.",
                        style = MaterialTheme.typography.bodySmall, color = FailRed,
                    )
                    when {
                        thisDownload != null -> {
                            Text("${thisDownload.phase.replaceFirstChar { it.uppercase() }}: ${formatGb(thisDownload.done)} / ${formatGb(thisDownload.total)}",
                                style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { thisDownload.fraction }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel") }
                        }
                        ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!active) Button(onClick = { if (v.experimental) confirmUse = v else vm.useModel(v) }) {
                                Text(if (engineLoaded) "Use (restarts app)" else "Use")
                            }
                            if (!(active && engineLoaded)) OutlinedButton(onClick = { confirmDelete = v }) { Text("Delete") }
                            if (active && engineLoaded) Text("Loaded", style = MaterialTheme.typography.bodySmall, color = OkGreen)
                        }
                        else -> OutlinedButton(
                            enabled = downloading == null,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    pendingDownload = v
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else vm.downloadModel(v)
                            },
                        ) { Text("Download") }
                    }
                }
            }
        }

        download?.error?.let { Text(it, color = FailRed) }

        val im = importing
        if (im != null) {
            if (im.error != null) {
                Text(im.error, color = FailRed)
                OutlinedButton(onClick = vm::dismissImportError) { Text("OK") }
            } else {
                Text("Importing: ${formatGb(im.done)} / ${formatGb(im.total)} (keep the app open)")
                LinearProgressIndicator(progress = { im.fraction }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Import a .litertlm from storage")
            }
        }
        Text(
            "NPU models must be compiled for the Snapdragon 8 Gen 1 (SM8450, Hexagon v69); anything else fails " +
                "with QNN error 1002. CPU models run in a bundled llama.cpp server. Downloads run in the background " +
                "and resume if interrupted. One model is loaded at a time: switching restarts the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    confirmUse?.let { v ->
        ExperimentalDialog(v.label, onDismiss = { confirmUse = null }) { confirmUse = null; vm.useModel(v) }
    }

    confirmDelete?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${v.label}?") },
            text = { Text("${v.fileName} (${formatGb(v.sizeBytes)}) will be removed and has to be downloaded again.") },
            confirmButton = { TextButton(onClick = { vm.deleteModel(v); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
