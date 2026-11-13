package com.roberrini.audiopro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roberrini.audiopro.Connection
import com.roberrini.audiopro.Screen
import com.roberrini.audiopro.SpeakerViewModel
import com.roberrini.audiopro.UiState
import com.roberrini.audiopro.net.PlayState
import com.roberrini.audiopro.net.Source
import com.roberrini.audiopro.net.SpeakerPreset
import com.roberrini.audiopro.net.Station
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(state: UiState, vm: SpeakerViewModel, snackbarHost: @Composable () -> Unit) {
    val online = state.connection == Connection.Online
    var renaming by remember { mutableStateOf<Int?>(null) }
    var choosing by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        snackbarHost = snackbarHost,
        bottomBar = { MainTabs(state, vm) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.device?.name ?: state.saved?.name ?: "Speaker",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ConnectionLabel(state.connection)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.show(Screen.DIAGNOSTICS) }) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Diagnostics")
                    }
                    IconButton(onClick = { vm.show(Screen.SETUP) }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "Change speaker")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ProblemRail(state, vm)
            NowPlaying(state)
            Transport(state, enabled = online, onPrev = vm::previous, onPlayPause = vm::playPause, onNext = vm::next)
            Volume(state, enabled = online, onVolume = vm::setVolume, onMute = vm::toggleMute)
            Sources(state, enabled = online, onSource = vm::switchSource)
            Presets(
                count = state.device?.presetCount?.coerceAtMost(10) ?: 6,
                names = state.presetNames,
                stored = state.speakerPresets,
                enabled = online,
                onPlay = vm::playPreset,
                onChoose = { choosing = it },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    choosing?.let { n ->
        StationPickerDialog(
            number = n,
            state = state,
            onPick = {
                vm.assignPreset(n, it)
                choosing = null
            },
            onRename = {
                renaming = n
                choosing = null
            },
            onDismiss = { choosing = null },
        )
    }

    renaming?.let { n ->
        RenamePresetDialog(
            number = n,
            current = state.presetNames[n],
            onDismiss = { renaming = null },
            onSave = {
                vm.renamePreset(n, it)
                renaming = null
            },
        )
    }
}

/** The station the app started, while the speaker is still playing it. */
private fun activeStation(state: UiState): Station? {
    val p = state.player ?: return null
    val active = p.state == PlayState.PLAYING || p.state == PlayState.PAUSED || p.state == PlayState.LOADING
    val otherInput = p.source == "Bluetooth" || p.source?.startsWith("Line in") == true
    val otherTrack = p.title != null && p.title != state.current?.name
    return state.current.takeIf { active && !otherInput && !otherTrack }
}

@Composable
fun ConnectionLabel(c: Connection) {
    val (color, text) = when (c) {
        Connection.Online -> OnlineGreen to "Connected"
        Connection.Connecting -> Warn to "Connecting…"
        is Connection.Reconnecting -> Warn to "Reconnecting… (attempt ${c.attempt})"
        Connection.Searching -> Warn to "Looking for the speaker on the network…"
        Connection.Idle -> Muted to "Not connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NowPlaying(state: UiState) {
    val p = state.player
    val station = activeStation(state)
    val fallbackTitle = when (p?.state) {
        PlayState.PLAYING -> "Playing"
        PlayState.PAUSED -> "Paused"
        PlayState.LOADING -> "Loading…"
        else -> "Nothing playing"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (p?.source ?: station?.let { "Internet radio" } ?: "—").uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Amber,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                p?.title ?: station?.name ?: fallbackTitle,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val paused = (p?.state == PlayState.PAUSED).takeIf { it && station != null }?.let { "Paused" }
            val sub = listOfNotNull(paused, p?.artist, p?.album, station?.let { state.liveShows[it.id]?.title })
                .joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val art = station?.let { state.liveShows[it.id]?.imageUrl ?: it.imageUrl }
        if (art != null) {
            Spacer(Modifier.width(14.dp))
            RemoteImage(art, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
        }
        }
    }
}

@Composable
private fun Transport(
    state: UiState,
    enabled: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val playing = state.player?.state == PlayState.PLAYING || state.player?.state == PlayState.LOADING
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(20.dp))
        FilledIconButton(
            onClick = onPlayPause,
            enabled = enabled,
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Amber),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        IconButton(onClick = onNext, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun Volume(state: UiState, enabled: Boolean, onVolume: (Int) -> Unit, onMute: () -> Unit) {
    val volume = state.player?.volume
    val muted = state.player?.muted == true
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: volume?.toFloat() ?: 0f

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMute, enabled = enabled) {
            Icon(
                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = if (muted) Amber else MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onVolume(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..100f,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            volume?.let { shown.roundToInt().toString() } ?: "–",
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Sources(state: UiState, enabled: Boolean, onSource: (Source) -> Unit) {
    val current = state.player?.source?.lowercase() ?: activeStation(state)?.let { "wi‑fi" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Source")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Source.entries.forEach { s ->
                val selected = when (s) {
                    Source.WIFI -> current != null && current !in setOf("bluetooth", "line in", "line in 2", "rca", "optical")
                    Source.BLUETOOTH -> current == "bluetooth"
                    Source.LINE_IN -> current == "line in" || current == "line in 2" || current == "rca"
                }
                FilterChip(
                    selected = selected,
                    onClick = { onSource(s) },
                    enabled = enabled,
                    label = { Text(s.label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Presets(
    count: Int,
    names: Map<Int, String>,
    stored: List<SpeakerPreset>,
    enabled: Boolean,
    onPlay: (Int) -> Unit,
    onChoose: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Presets")
        Text(
            "Stored on the speaker: tap to play, same as its buttons. Long‑press to change one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (1..count).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { n ->
                    val shape = RoundedCornerShape(16.dp)
                    Surface(
                        shape = shape,
                        color = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp)
                            .clip(shape)
                            .combinedClickable(
                                enabled = enabled,
                                onClick = { onPlay(n) },
                                onLongClick = { onChoose(n) },
                            ),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("$n", style = MaterialTheme.typography.titleLarge, color = Amber, fontWeight = FontWeight.Bold)
                            Text(
                                names[n] ?: stored.firstOrNull { it.number == n }?.name
                                    ?: if (stored.isEmpty()) "Preset $n" else "Empty",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted,
                            )
                        }
                    }
                }
                // Keep the grid aligned when the last row is short.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Color(0xFFD9D6CE))
}

@Composable
private fun RenamePresetDialog(number: Int, current: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name preset $number") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("e.g. P3 Radio, Morning playlist") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
