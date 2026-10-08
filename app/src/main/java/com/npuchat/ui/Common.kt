package com.npuchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.npuchat.engine.EngineState
import com.npuchat.engine.StepStatus

val OkGreen = Color(0xFF3FA65B)
val WarnAmber = Color(0xFFD9A400)
val FailRed = Color(0xFFD64545)

fun EngineState.label(backend: com.npuchat.model.ModelBackend) = when (this) {
    EngineState.IDLE -> "Not loaded"
    EngineState.INITIALIZING -> "Loading…"
    EngineState.READY -> "${backend.label} ready"
    EngineState.FAILED -> "Load failed"
}

fun EngineState.color() = when (this) {
    EngineState.IDLE -> Color.Gray
    EngineState.INITIALIZING -> WarnAmber
    EngineState.READY -> OkGreen
    EngineState.FAILED -> FailRed
}

fun StepStatus.color() = when (this) {
    StepStatus.PENDING, StepStatus.SKIPPED -> Color.Gray
    StepStatus.RUNNING, StepStatus.WARN -> WarnAmber
    StepStatus.OK -> OkGreen
    StepStatus.FAIL -> FailRed
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun EngineBadge(state: EngineState, backend: com.npuchat.model.ModelBackend) {
    Row(
        Modifier.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(state.color())
        Text(state.label(backend), style = MaterialTheme.typography.labelLarge)
    }
}

fun formatGb(bytes: Long) = "%.2f GB".format(bytes / 1e9)

const val EXPERIMENTAL_WARNING =
    "This model is several times bigger than the phone's free RAM. Its weights are read from storage " +
        "for every token, so it is very slow, and loading or generating can freeze the phone until it " +
        "restarts itself (that happened once already with the 21 GB version). Close other apps first."

/** A "load model" button that asks first for experimental models. */
@Composable
fun LoadModelButton(vm: AppViewModel, label: String = "Load model", enabled: Boolean = true) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    androidx.compose.material3.Button(
        onClick = { if (s.variant.experimental) confirm = true else vm.initEngine() },
        enabled = enabled,
    ) { Text(label) }
    if (confirm) ExperimentalDialog(s.variant.label, onDismiss = { confirm = false }) { confirm = false; vm.initEngine() }
}

@Composable
fun ExperimentalDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load $name?") },
        text = { Text(EXPERIMENTAL_WARNING) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text("Load anyway") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
