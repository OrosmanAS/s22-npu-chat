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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.npuchat.engine.NpuEnv
import com.npuchat.log.Level
import com.npuchat.model.ModelFamily
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val isQwen = s.variant.family == ModelFamily.QWEN

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("Models")
        ModelList(vm)

        Section("Chat")
        Toggle("Thinking (reasoning before answering)", s.thinking) { on -> vm.updateSettings { it.copy(thinking = on) } }
        ChatMode.entries.forEach { m ->
            val enabled = isQwen || m == ChatMode.CONVERSATION
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.chatMode == m, onClick = { vm.updateSettings { it.copy(chatMode = m) } }, enabled = enabled)
                Text(m.label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Hint(
            if (isQwen) "Changing the mode starts a new chat."
            else "Raw session mode builds Qwen prompts by hand, so ${s.variant.label} always uses the Conversation API.",
        )
        var sys by remember(s.systemPrompt) { mutableStateOf(s.systemPrompt) }
        OutlinedTextField(
            sys, { sys = it; vm.updateSettings { st -> st.copy(systemPrompt = it) } },
            Modifier.fillMaxWidth(), label = { Text("System prompt (applies to new chats)") }, maxLines = 4,
        )
        NumberField("Max output tokens per reply (0 = no limit)", s.maxOutputTokens) { v -> vm.updateSettings { it.copy(maxOutputTokens = v) } }

        Section("Stop repetition loops")
        Hint(
            "The NPU always picks the most likely next token (greedy), so temperature/top-k do nothing and small " +
                "models can repeat themselves forever, especially while thinking. These are applied to the model's " +
                "scores before each pick, on the NPU path too.",
        )
        NumberField("Thinking budget in tokens (0 = unlimited)", s.thinkingBudget) { v -> vm.updateSettings { it.copy(thinkingBudget = v) } }
        Text("Repetition penalty: %.2f%s".format(s.repetitionPenalty, if (s.repetitionPenalty <= 1.0f) " (off)" else ""))
        Slider(
            value = s.repetitionPenalty,
            onValueChange = { v -> vm.updateSettings { it.copy(repetitionPenalty = (v * 20).roundToInt() / 20f) } },
            valueRange = 1.0f..1.5f,
            steps = 9,
        )
        NumberField("Block repeated phrases of N tokens (0 = off)", s.noRepeatNgram) { v -> vm.updateSettings { it.copy(noRepeatNgram = v) } }
        Hint("If a reply still loops, the app stops it and starts a fresh context.")

        Section("Engine")
        Toggle("Load the model on launch (never for experimental models)", s.autoInit) { on -> vm.updateSettings { it.copy(autoInit = on) } }
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
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { v -> text = v.filter(Char::isDigit).take(6); onChange(text.toIntOrNull() ?: 0) },
        Modifier.fillMaxWidth(), label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
    )
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
