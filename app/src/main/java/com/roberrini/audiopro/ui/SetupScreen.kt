package com.roberrini.audiopro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roberrini.audiopro.Screen
import com.roberrini.audiopro.SpeakerViewModel
import com.roberrini.audiopro.UiState
import com.roberrini.audiopro.net.FoundSpeaker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(state: UiState, vm: SpeakerViewModel, snackbarHost: @Composable () -> Unit) {
    var ip by rememberSaveable { mutableStateOf(state.saved?.host ?: "") }
    val speakers = state.found.filter { it.likelyLinkPlay }
    val others = state.found.filterNot { it.likelyLinkPlay }

    Scaffold(
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = { Text("Find your speaker") },
                navigationIcon = {
                    if (state.saved != null) {
                        IconButton(onClick = { vm.show(Screen.PLAYER) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (state.discovering) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::startDiscovery) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Search again")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ProblemRail(state, vm) }
            item {
                Text(
                    if (state.discovering) "Searching your Wi‑Fi for Audio Pro / LinkPlay speakers…"
                    else if (state.found.isEmpty()) "No speakers found. Make sure your phone is on the same Wi‑Fi, or enter the IP address below."
                    else "Tap your speaker to connect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(speakers, key = { it.host }) { SpeakerRow(it) { vm.choose(it) } }
            if (others.isNotEmpty()) {
                item {
                    Text(
                        "Other devices on the network",
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(others, key = { "o" + it.host }) { SpeakerRow(it) { vm.choose(it) } }
            }
            item {
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connect by IP address", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your router's app lists the speaker's address (for example 192.168.1.42). " +
                            "Giving it a fixed address in the router makes reconnecting even more reliable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it },
                            label = { Text("IP address") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { vm.chooseManual(ip) }),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { vm.chooseManual(ip) }, enabled = ip.isNotBlank()) { Text("Connect") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakerRow(f: FoundSpeaker, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Speaker,
                contentDescription = null,
                tint = if (f.likelyLinkPlay) Amber else Muted,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(f.host, f.model).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(f.via, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}
