package com.npuchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.npuchat.RestartActivity
import com.npuchat.engine.EngineState
import com.npuchat.engine.InitStep
import com.npuchat.log.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StatusScreen(vm: AppViewModel) {
    val engine by vm.engine.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val exits by produceState("loading…") { value = withContext(Dispatchers.IO) { Diagnostics.previousExits(ctx) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(engine.state.color())
            Text(engine.state.label(), style = MaterialTheme.typography.titleLarge)
        }
        engine.modelName?.let { Text("Model: $it", style = MaterialTheme.typography.bodySmall) }
        engine.initMs?.let { Text("Init took %.1f s".format(it / 1000.0), style = MaterialTheme.typography.bodySmall) }
        engine.error?.let { Text(it, color = FailRed) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (engine.state) {
                EngineState.IDLE -> Button(onClick = vm::initEngine, enabled = modelReady) { Text("Initialise NPU") }
                EngineState.FAILED -> OutlinedButton(onClick = vm::initEngine) { Text("Retry in this process") }
                else -> {}
            }
            OutlinedButton(onClick = { RestartActivity.restartApp(ctx) }) { Text("Restart app") }
        }
        if (engine.state == EngineState.FAILED) {
            Text(
                "The NPU usually cannot be initialised twice in one process. Restart the app for a clean retry.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("Init steps (tap to expand)", style = MaterialTheme.typography.titleMedium)
        engine.steps.forEach { StepRow(it) }

        Text("Previous app exits", style = MaterialTheme.typography.titleMedium)
        Mono(exits)
    }
}

@Composable
private fun StepRow(step: InitStep) {
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(step.status.color())
                Text(step.title, Modifier.weight(1f))
                Text(step.status.name + (step.ms?.let { " · ${it} ms" } ?: ""), style = MaterialTheme.typography.labelSmall)
            }
            if (expanded && step.detail.isNotEmpty()) Mono(step.detail)
            else if (step.detail.isNotEmpty()) Text(step.detail.lineSequence().first(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
fun Mono(text: String) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
