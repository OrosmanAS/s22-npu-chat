package com.npuchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.npuchat.BuildConfig
import com.npuchat.ChatMode
import com.npuchat.engine.EngineState
import com.npuchat.engine.NpuEnv
import com.npuchat.log.Level
import com.npuchat.model.ModelStore
import com.npuchat.model.ModelVariant

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val engineLoaded = engine.state == EngineState.READY || engine.state == EngineState.INITIALIZING

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("Model")
        ModelVariant.entries.forEach { v ->
            val ready = ModelStore.isReady(ctx, v)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.variant == v, onClick = { vm.updateSettings { it.copy(variant = v) } }, enabled = !engineLoaded)
                Column(Modifier.weight(1f)) {
                    Text(v.label)
                    Text(
                        if (!ready) "not downloaded" else if (ModelStore.isVerified(ctx, v)) "downloaded, SHA-256 verified" else "downloaded, not verified",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (engineLoaded) Hint("One NPU model per process: restart the app to switch models.")
        if (modelReady && !engineLoaded) {
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete ${s.variant.fileName}") }
        }

        Section("Chat")
        Toggle("Thinking (reasoning before answering)", s.thinking) { on -> vm.updateSettings { it.copy(thinking = on) } }
        ChatMode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.chatMode == m, onClick = { vm.updateSettings { it.copy(chatMode = m) } })
                Text(m.label)
            }
        }
        Hint("Changing the mode starts a new chat. The NPU is greedy: temperature/top-k have no effect, so there are no sampling settings.")
        var sys by remember(s.systemPrompt) { mutableStateOf(s.systemPrompt) }
        OutlinedTextField(
            sys, { sys = it; vm.updateSettings { st -> st.copy(systemPrompt = it) } },
            Modifier.fillMaxWidth(), label = { Text("System prompt (applies to new chats)") }, maxLines = 4,
        )
        var maxOut by remember(s.maxOutputTokens) { mutableStateOf(if (s.maxOutputTokens > 0) s.maxOutputTokens.toString() else "") }
        OutlinedTextField(
            maxOut,
            { v -> maxOut = v.filter(Char::isDigit); vm.updateSettings { it.copy(maxOutputTokens = maxOut.toIntOrNull() ?: 0) } },
            Modifier.fillMaxWidth(), label = { Text("Max output tokens (empty = model default)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
        )

        Section("Engine")
        Toggle("Initialise NPU on launch", s.autoInit) { on -> vm.updateSettings { it.copy(autoInit = on) } }
        Toggle("Preload QNN + dispatch libs (dlopen evidence)", s.preloadLibs) { on -> vm.updateSettings { it.copy(preloadLibs = on) } }
        Toggle("Benchmark info (exact tok/s)", s.benchmark) { on -> vm.updateSettings { it.copy(benchmark = on) } }
        Hint("Engine options apply at the next initialisation.")

        Section("Logging")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Level.entries.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.logLevel == l, onClick = { vm.updateSettings { it.copy(logLevel = l) } })
                    Text(l.letter.toString())
                }
            }
        }
        Hint("App log level. Debug keeps every init step; Verbose also logs each streamed token.")
        Toggle("Verbose native LiteRT logs (next init)", s.nativeVerbose) { on -> vm.updateSettings { it.copy(nativeVerbose = on) } }

        Section("About")
        Mono("NPU Chat ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})\n\n${NpuEnv.nativeManifest(ctx)}")
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete model?") },
            text = { Text("${s.variant.fileName} (${formatGb(s.variant.sizeBytes)}) will be removed and has to be downloaded again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteModel() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
