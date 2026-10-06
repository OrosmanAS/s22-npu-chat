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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.npuchat.engine.EngineState
import com.npuchat.engine.StepStatus

val OkGreen = Color(0xFF3FA65B)
val WarnAmber = Color(0xFFD9A400)
val FailRed = Color(0xFFD64545)

fun EngineState.label() = when (this) {
    EngineState.IDLE -> "Not initialised"
    EngineState.INITIALIZING -> "Initialising…"
    EngineState.READY -> "NPU ready"
    EngineState.FAILED -> "Init failed"
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
fun EngineBadge(state: EngineState) {
    Row(
        Modifier.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(state.color())
        Text(state.label(), style = MaterialTheme.typography.labelLarge)
    }
}

fun formatGb(bytes: Long) = "%.2f GB".format(bytes / 1e9)
