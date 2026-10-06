package com.npuchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Get a model", style = MaterialTheme.typography.headlineSmall)
        Text(
            "These models are compiled for the Snapdragon 8 Gen 1 NPU. Each one is stored in the app's " +
                "private storage and checked with SHA-256 before use. Wi-Fi recommended (2–3 GB each).",
            style = MaterialTheme.typography.bodyMedium,
        )
        ModelList(vm)
    }
}
