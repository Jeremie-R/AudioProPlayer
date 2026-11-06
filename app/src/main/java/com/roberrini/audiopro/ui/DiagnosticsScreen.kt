package com.roberrini.audiopro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roberrini.audiopro.Screen
import com.roberrini.audiopro.SpeakerViewModel
import com.roberrini.audiopro.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(state: UiState, vm: SpeakerViewModel, snackbarHost: @Composable () -> Unit) {
    val context = LocalContext.current
    val d = state.device

    Scaffold(
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = { vm.show(Screen.PLAYER) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                InfoCard(
                    listOf(
                        "Address" to (state.saved?.host ?: "—"),
                        "Connected via" to (state.saved?.endpoint?.toString() ?: "not yet"),
                        "Status" to state.connection.toString(),
                        "Name" to (d?.name ?: "—"),
                        "Model" to (d?.model ?: "—"),
                        "Firmware" to (d?.firmware ?: "—"),
                        "UUID" to (d?.uuid ?: "—"),
                        "Wi‑Fi" to listOfNotNull(d?.ssid, d?.rssi?.let { "$it dBm" }).joinToString("  ").ifEmpty { "—" },
                        "Presets" to (d?.presetCount?.toString() ?: "—"),
                    ),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = vm::runEndpointTest, enabled = !state.testing && state.saved != null) {
                        if (state.testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("  Testing…")
                        } else {
                            Text("Test connection")
                        }
                    }
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Audio Pro diagnostics", vm.diagnosticsReport()))
                    }) { Text("Copy report") }
                }
            }
            if (state.endpointTests.isNotEmpty()) {
                item { Label("Connection test") }
                items(state.endpointTests) { Mono(it) }
            }
            item { Label("Log") }
            items(state.log.asReversed()) { Mono(it) }
            item {
                TextButton(onClick = vm::forgetSpeaker, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Forget this speaker", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (k, v) ->
                Row {
                    Text(k, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
                    Text(v, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
