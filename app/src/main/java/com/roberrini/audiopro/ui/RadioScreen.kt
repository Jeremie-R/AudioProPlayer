package com.roberrini.audiopro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roberrini.audiopro.Connection
import com.roberrini.audiopro.SpeakerViewModel
import com.roberrini.audiopro.UiState
import com.roberrini.audiopro.net.Station
import com.roberrini.audiopro.net.Stations

val StationIcons: Map<String, ImageVector> = linkedMapOf(
    "radio" to Icons.Filled.Radio,
    "music" to Icons.Filled.MusicNote,
    "headphones" to Icons.Filled.Headphones,
    "album" to Icons.Filled.Album,
    "mic" to Icons.Filled.Mic,
    "heart" to Icons.Filled.Favorite,
    "world" to Icons.Filled.Public,
    "wave" to Icons.Filled.GraphicEq,
)

private val StationColors = listOf(
    0xFFB23A2E, 0xFFE5571F, 0xFFC99A2E, 0xFF2E7D32,
    0xFF00838F, 0xFF1F5FA8, 0xFF7A3E9D, 0xFF2E3440,
)

/** A station being added or edited. A null original means it is new. */
private data class Editing(val original: Station?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(state: UiState, vm: SpeakerViewModel, snackbarHost: @Composable () -> Unit) {
    var editing by remember { mutableStateOf<Editing?>(null) }
    val online = state.connection == Connection.Online

    Scaffold(
        snackbarHost = snackbarHost,
        bottomBar = { MainTabs(state, vm) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Radio")
                        ConnectionLabel(state.connection)
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            fun group(title: String, list: List<Station>) = section(title, list, state, online, vm) {}
            group("NTS", Stations.ntsLive)
            group("NTS Infinite Mixtapes", state.mixtapes)
            group("BBC Radio", Stations.bbc)
            group("NPR", Stations.npr)
            group("Independent radio", Stations.indie)
            section("My stations", state.customStations, state, online, vm) { editing = Editing(it) }
            item(key = "add") {
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Muted, RoundedCornerShape(14.dp))
                        .clickable { editing = Editing(null) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Add station", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    editing?.let { e ->
        StationDialog(
            original = e.original,
            vm = vm,
            onDismiss = { editing = null },
        )
    }
}

private fun LazyGridScope.section(
    title: String,
    list: List<Station>,
    state: UiState,
    online: Boolean,
    vm: SpeakerViewModel,
    onEdit: (Station) -> Unit,
) {
    item(key = "h-$title", span = { GridItemSpan(maxLineSpan) }) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
    }
    items(list, key = { it.id }) { s ->
        StationTile(
            station = s.copy(imageUrl = state.liveShows[s.id]?.imageUrl ?: s.imageUrl),
            selected = state.current?.id == s.id,
            enabled = online,
            onClick = { vm.playStation(s) },
            onLongClick = { if (s.isCustom) onEdit(s) },
            onImageError = vm::logMessage,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationTile(
    station: Station,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onImageError: (String) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Color(station.color))
            .then(if (selected) Modifier.border(3.dp, Amber, shape) else Modifier)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = station.name },
    ) {
        RemoteImage(station.imageUrl, Modifier.fillMaxSize(), onError = onImageError)
        // NTS draws a white icon or number over its picture, which needs a darker image behind it.
        val overlaid = station.iconUrl != null || station.badge != null
        // Built-in logos already contain the station name. A custom image might not.
        val logoOnly = station.imageUrl != null && !overlaid && !station.isCustom
        if (station.imageUrl != null && !logoOnly) {
            Box(
                Modifier.fillMaxSize().background(
                    if (overlaid) SolidColor(Color(0x66000000))
                    else Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000)),
                ),
            )
        }
        if (station.badge != null) {
            Text(
                station.badge,
                color = Color.White,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (station.iconUrl != null) {
            RemoteImage(
                station.iconUrl,
                Modifier.size(52.dp).align(Alignment.Center),
                contentScale = ContentScale.Fit,
                onError = onImageError,
            )
        } else if (station.imageUrl == null) {
            Icon(
                StationIcons[station.icon] ?: Icons.Filled.Radio,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(34.dp).align(Alignment.Center),
            )
        }
        if (!logoOnly) {
            Text(
                station.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
    }
}

@Composable
private fun StationDialog(original: Station?, vm: SpeakerViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var url by remember { mutableStateOf(original?.url ?: "") }
    var image by remember { mutableStateOf(original?.imageUrl ?: "") }
    var icon by remember { mutableStateOf(original?.icon ?: "radio") }
    var color by remember { mutableStateOf(original?.color ?: StationColors.first()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.importImage(uri)?.let { image = it }
    }
    val validUrl = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add station" else "Edit station") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = image,
                    onValueChange = { image = it },
                    label = { Text("Image URL (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Choose an image") }
                Text("Or use an icon and colour", style = MaterialTheme.typography.labelMedium, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    StationIcons.forEach { (key, vector) ->
                        Icon(
                            vector,
                            contentDescription = key,
                            tint = if (key == icon) Amber else Muted,
                            modifier = Modifier.weight(1f).clickable { icon = key }.padding(vertical = 6.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StationColors.forEach { c ->
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(Color(c))
                                .then(if (c == color) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && validUrl,
                onClick = {
                    vm.saveStation(original, name, url, image, icon, color)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (original != null) {
                    TextButton(onClick = {
                        vm.deleteStation(original)
                        onDismiss()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
