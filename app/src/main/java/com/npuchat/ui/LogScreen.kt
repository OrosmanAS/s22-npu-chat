package com.npuchat.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.npuchat.log.AppLog
import com.npuchat.log.Diagnostics
import com.npuchat.log.Level
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by AppLog.version.collectAsStateWithLifecycle()
    var minLevel by rememberSaveable { mutableStateOf(Level.DEBUG) }
    var query by rememberSaveable { mutableStateOf("") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    val lines = remember(version, minLevel, query) {
        AppLog.snapshot().filter { it.level >= minLevel && (query.isBlank() || it.tag.contains(query, true) || it.message.contains(query, true)) }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val ok = runCatching {
                val f = vm.buildLogBundle()
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                }
            }.onFailure { AppLog.e("Diag", "save failed", it) }.isSuccess
            busy = false
            Toast.makeText(ctx, if (ok) "Logs saved" else "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching { vm.buildLogBundle() }
                        .onSuccess { ctx.startActivity(Diagnostics.shareIntent(ctx, it)) }
                        .onFailure { AppLog.e("Diag", "share failed", it) }
                    busy = false
                }
            }) { Text(if (busy) "Collecting…" else "Share logs") }
            OutlinedButton(enabled = !busy, onClick = { saver.launch("npuchat-logs.txt") }) { Text("Save…") }
            OutlinedButton(onClick = AppLog::clearBuffer) { Text("Clear") }
        }
        Text(
            "Share/Save bundles device info, init steps, previous crashes, the app log and this app's logcat (incl. native LiteRT/QNN lines).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Level.entries.forEach { l ->
                FilterChip(selected = minLevel == l, onClick = { minLevel = l }, label = { Text("${l.letter}+") })
            }
            FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Filter (tag or text)") }, singleLine = true)

        val listState = rememberLazyListState()
        LaunchedEffect(lines.size, follow) {
            if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
            items(lines) { line ->
                Text(
                    line.format(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = when (line.level) {
                        Level.ERROR -> FailRed
                        Level.WARN -> WarnAmber
                        Level.VERBOSE -> Color.Gray
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
