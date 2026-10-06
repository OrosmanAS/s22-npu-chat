package com.npuchat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.npuchat.ChatMode
import com.npuchat.RestartActivity
import com.npuchat.engine.EngineState
import com.npuchat.engine.GenStats

@Composable
fun ChatScreen(vm: AppViewModel, onOpenStatus: () -> Unit) {
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    if (!modelReady) {
        SetupScreen(vm)
        return
    }
    val engine by vm.engine.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sys by vm.sys.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // Keep the screen on while the NPU is generating.
    val view = LocalView.current
    DisposableEffect(generating) {
        view.keepScreenOn = generating
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (vm.lastInitCrashed && engine.state == EngineState.IDLE) {
            Banner("The last NPU init crashed the app, so auto-init is off for this launch. Check Logs, then initialise manually.")
        }
        if (engine.state != EngineState.READY) {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(engine.state.label(), style = MaterialTheme.typography.titleMedium)
                    when (engine.state) {
                        EngineState.INITIALIZING -> {
                            val running = engine.steps.firstOrNull { it.status == com.npuchat.engine.StepStatus.RUNNING }
                            Text(running?.title ?: "Working…")
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        EngineState.FAILED -> {
                            Text(engine.error ?: "Unknown error", color = FailRed)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { RestartActivity.restartApp(ctx) }) { Text("Restart app") }
                                OutlinedButton(onClick = onOpenStatus) { Text("Details") }
                            }
                        }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::initEngine) { Text("Initialise NPU") }
                            OutlinedButton(onClick = onOpenStatus) { Text("Details") }
                        }
                    }
                }
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, messages.lastOrNull()?.thought?.length) {
            if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 2)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && engine.state == EngineState.READY) {
                item {
                    Text(
                        "Ready. Prompts are read at about the same speed as answers are written (~16 tok/s), so long prompts take a while." +
                            if (settings.chatMode == ChatMode.RAW_SESSION) "\nRaw session mode re-reads the whole chat every turn." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(messages, key = { it.id }) { m ->
                when {
                    m.notice -> Notice(m.text)
                    m.fromUser -> UserBubble(m.text)
                    else -> AssistantBubble(m, engine.contextTokens)
                }
            }
        }

        if (engine.state == EngineState.READY) ActivityStrip(if (generating) messages.lastOrNull()?.stats else null, generating, sys)

        InputBar(
            enabled = engine.state == EngineState.READY,
            generating = generating,
            thinking = settings.thinking,
            onThinking = { on -> vm.updateSettings { it.copy(thinking = on) } },
            onSend = vm::send,
            onStop = vm::stop,
            onNewChat = vm::newChat,
        )
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(FailRed.copy(alpha = 0.15f)).padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Live readings. Android gives apps no Hexagon utilisation counter, so the NPU is shown as
 * throughput (tok/s, ms per token) plus its own temperature sensors; the rest are real readings.
 */
@Composable
private fun ActivityStrip(stats: GenStats?, generating: Boolean, sys: com.npuchat.engine.SysSnapshot?) {
    val parts = mutableListOf<String>()
    val tps = stats?.liveTps
    parts += when {
        !generating -> "NPU: idle"
        stats?.ttftMs == null -> "NPU: reading prompt"
        tps != null -> "NPU: %.1f tok/s (%.0f ms/token)".format(tps, 1000 / tps)
        else -> "NPU: generating"
    }
    sys?.npuTempC?.let { parts += "NPU %.0f°C".format(it) }
    sys?.batteryTempC?.let { parts += "battery %.0f°C".format(it) }
    sys?.appCpuPercent?.let { parts += "app CPU %.0f%%".format(it) }
    if (sys?.bigCoreMhz != null) parts += "cores %.1f/%.1f GHz".format(sys.bigCoreMhz / 1000.0, (sys.primeCoreMhz ?: 0) / 1000.0)
    sys?.gpuBusyPercent?.let { parts += "GPU $it%" }
    if (sys != null && sys.cpusAllowed != "0-7") parts += "cpus ${sys.cpusAllowed}"
    sys?.ramUsedPercent?.let { parts += "RAM $it% used" + (sys?.appRamGb?.let { gb -> " (app %.1f GB)".format(gb) } ?: "") }
    sys?.storageUsedPercent?.let { parts += "storage $it% used" }
    sys?.appDiskReadMBps?.let { if (it >= 0.1) parts += "disk read %.1f MB/s".format(it) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(if (generating) OkGreen else Color.Gray)
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SelectionContainer {
            Text(
                text,
                Modifier
                    .widthIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantBubble(m: ChatMessage, contextTokens: Int) {
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val thinkingLive = m.streaming && m.text.isEmpty() && m.thought.isNotEmpty()
        if (m.thought.isNotEmpty()) ThinkingSection(m.id, m.thought, thinkingLive)
        when {
            m.text.isNotEmpty() -> SelectionContainer { Text(m.text) }
            m.streaming && m.thought.isEmpty() -> Text(
                if (m.stats?.ttftMs == null) "Reading prompt…" else "…",
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        m.error?.let { Text(it, color = FailRed, style = MaterialTheme.typography.bodySmall) }
        m.stats?.let { StatsLine(it, m.streaming, contextTokens) }
    }
}

@Composable
private fun ThinkingSection(id: Long, thought: String, live: Boolean) {
    var userExpanded by rememberSaveable(id) { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: live
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.clickable { userExpanded = !expanded }.padding(10.dp)) {
            Text(
                (if (expanded) "▾ " else "▸ ") + if (live) "Thinking…" else "Thought (${thought.length} chars)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Spacer(Modifier.padding(top = 4.dp))
                SelectionContainer {
                    Text(thought, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatsLine(s: GenStats, streaming: Boolean, contextTokens: Int) {
    val parts = mutableListOf<String>()
    if (!streaming && s.decodeTps != null) {
        parts += "%.1f tok/s".format(s.decodeTps)
        s.prefillTps?.let { parts += "prefill %.1f tok/s (%d tok)".format(it, s.prefillTokens ?: 0) }
    } else {
        s.liveTps?.let { parts += "%.1f tok/s".format(it) }
        parts += "${s.chunks} tok"
    }
    s.ttftMs?.let { parts += "first token %.1f s".format(it / 1000.0) }
    if (s.contextUsed != null && contextTokens > 0) parts += "ctx ${s.contextUsed}/$contextTokens"
    when (s.stopReason) {
        "loop" -> parts += "stopped: repetition loop"
        "limit" -> parts += "stopped: max output length"
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InputBar(
    enabled: Boolean,
    generating: Boolean,
    thinking: Boolean,
    onThinking: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = thinking, onClick = { onThinking(!thinking) }, label = { Text("Thinking") }, enabled = !generating)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onNewChat, enabled = enabled && !generating) {
                Icon(Icons.Default.Refresh, contentDescription = "New chat")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "Message" else "NPU not ready") },
                enabled = enabled,
                maxLines = 6,
            )
            if (generating) {
                FilledIconButton(onClick = onStop) { Icon(Icons.Default.Close, contentDescription = "Stop") }
            } else {
                FilledIconButton(
                    onClick = { onSend(text); text = "" },
                    enabled = enabled && text.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}
