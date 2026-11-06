package com.roberrini.audiopro

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roberrini.audiopro.net.DeviceInfo
import com.roberrini.audiopro.net.Discovery
import com.roberrini.audiopro.net.FoundSpeaker
import com.roberrini.audiopro.net.LiveShow
import com.roberrini.audiopro.net.NtsApi
import com.roberrini.audiopro.net.PlayState
import com.roberrini.audiopro.net.PlayerStatus
import com.roberrini.audiopro.net.Source
import com.roberrini.audiopro.net.SpeakerClient
import com.roberrini.audiopro.net.SpeakerPreset
import com.roberrini.audiopro.net.Station
import com.roberrini.audiopro.net.Stations
import com.roberrini.audiopro.net.Upnp
import com.roberrini.audiopro.net.UpnpDevice
import com.roberrini.audiopro.net.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class Screen { SETUP, PLAYER, RADIO, DIAGNOSTICS }

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data object Online : Connection
    data class Reconnecting(val attempt: Int) : Connection
    data object Searching : Connection
}

data class UiState(
    val screen: Screen = Screen.SETUP,
    val saved: SavedSpeaker? = null,
    val connection: Connection = Connection.Idle,
    val device: DeviceInfo? = null,
    val player: PlayerStatus? = null,
    val discovering: Boolean = false,
    val found: List<FoundSpeaker> = emptyList(),
    val presetNames: Map<Int, String> = emptyMap(),
    val speakerPresets: List<SpeakerPreset> = emptyList(),
    val mixtapes: List<Station> = Stations.ntsMixtapes,
    val customStations: List<Station> = emptyList(),
    val current: Station? = null,
    val liveShows: Map<String, LiveShow> = emptyMap(),
    val endpointTests: List<String> = emptyList(),
    val testing: Boolean = false,
    val log: List<String> = emptyList(),
    val message: String? = null,
)

class SpeakerViewModel(app: Application) : AndroidViewModel(app) {

    // Declared first: log() can be called while the init block is still running.
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val store = SpeakerStore(app)
    private val discovery = Discovery(app, ::log)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var client: SpeakerClient? = null
    private var upnp: UpnpDevice? = null
    private var upnpRetryAt = 0L
    private var pollJob: Job? = null
    private var foreground = false
    private var pausePollingUntil = 0L
    private var presetsRefreshAt = 0L
    private var liveShowsRefreshAt = 0L
    private var presetQueuesChecked = false
    private var ignoreSpeakerTrackUntil = 0L

    init {
        val saved = store.load()
        _state.update {
            it.copy(
                saved = saved,
                screen = if (saved != null) Screen.PLAYER else Screen.SETUP,
                presetNames = loadPresetNames(),
                customStations = store.customStations(),
            )
        }
        viewModelScope.launch {
            try {
                val list = NtsApi.mixtapes()
                if (list.isNotEmpty()) _state.update { it.copy(mixtapes = list) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("NTS mixtapes failed: ${e.describe()}")
            }
        }
        if (saved != null) {
            client = SpeakerClient(app, saved.host, saved.endpoint, ::log)
        } else {
            startDiscovery()
        }
    }

    // ---------------- lifecycle ----------------

    fun setForeground(fg: Boolean) {
        foreground = fg
        if (fg) startPolling() else stopPolling()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The core of the "doesn't drop" fix: poll the speaker, and when it stops
     * answering, keep retrying with backoff, re-probe ports, and if needed
     * rediscover it on the network (in case its IP address changed).
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        if (client == null || !foreground) return
        pollJob = viewModelScope.launch {
            var failures = 0
            var needInfo = true
            if (_state.value.device == null) _state.update { it.copy(connection = Connection.Connecting) }
            while (isActive) {
                val wait = pausePollingUntil - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                val ok = try {
                    if (needInfo) {
                        refreshInfo()
                        needInfo = false
                    }
                    refreshPlayer()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (failures == 0) log("Lost contact: ${e.describe()}")
                    false
                }

                if (ok) {
                    if (failures > 0) log("Speaker is back after $failures failed attempt(s)")
                    failures = 0
                    _state.update { it.copy(connection = Connection.Online) }
                } else {
                    failures++
                    needInfo = true
                    // The speaker may be restarting: check its button queues when it returns.
                    presetQueuesChecked = false
                    presetsRefreshAt = 0
                    _state.update { it.copy(connection = Connection.Reconnecting(failures)) }
                    if (failures == 3 || failures % 8 == 0) relocate()
                }
                delay(if (ok) 2500L else (1500L * failures).coerceAtMost(12_000L))
            }
        }
    }

    private suspend fun refreshInfo() {
        val c = client ?: return
        val info = c.deviceInfo()
        val prev = _state.value.saved
        val updated = SavedSpeaker(host = c.host, name = info.name, uuid = info.uuid, endpoint = c.endpoint)
        if (updated != prev) store.save(updated)
        _state.update { it.copy(device = info, saved = updated) }
    }

    private suspend fun refreshPlayer() {
        val c = client ?: return
        var p = c.playerStatus()
        // MkII firmware leaves volume / play state out of the HTTP reply: ask UPnP instead.
        if (p.volume == null || p.state == PlayState.UNKNOWN) {
            val dev = upnpDevice(c.host)
            if (dev != null) {
                try {
                    val vol = p.volume ?: Upnp.getVolume(dev)
                    val st = if (p.state == PlayState.UNKNOWN) Upnp.getPlayState(dev) ?: p.state else p.state
                    val mute = p.muted ?: Upnp.getMute(dev)
                    val track = if (p.title == null || p.source == null) Upnp.getTrack(dev) else null
                    track?.url?.let(::followSpeaker)
                    p = p.copy(
                        volume = vol,
                        state = st,
                        muted = mute,
                        source = p.source ?: track?.source,
                        title = p.title ?: track?.title,
                        artist = p.artist ?: track?.artist,
                        album = p.album ?: track?.album,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    upnp = null
                }
            }
        }
        _state.update { it.copy(player = p) }
        refreshPresets(c.host)
        refreshLiveShows()
    }

    /** Re-read now and then: presets can be changed on the speaker or in another app. */
    private suspend fun refreshPresets(host: String) {
        if (System.currentTimeMillis() < presetsRefreshAt) return
        presetsRefreshAt = System.currentTimeMillis() + 30_000
        val dev = upnpDevice(host) ?: return
        try {
            val presets = Upnp.getPresets(dev)
            _state.update { it.copy(speakerPresets = presets) }
            if (!presetQueuesChecked) {
                val added = Upnp.ensurePresetQueues(dev, presets)
                presetQueuesChecked = true
                if (added > 0) log("Restored $added preset button queue(s)")
            }
        } catch (e: IOException) {
            log("Reading presets failed: ${e.describe()}")
        }
    }

    /** Something else started this URL, like a physical preset button: show it as the station. */
    private fun followSpeaker(url: String) {
        if (System.currentTimeMillis() < ignoreSpeakerTrackUntil) return
        val s = _state.value
        if (s.current?.url?.substringBefore('?') == url.substringBefore('?')) return
        val preset = s.speakerPresets.firstOrNull { it.url == url }
        val known = Stations.match(url, s.mixtapes) ?: s.customStations.firstOrNull { it.url == url }
        val station = when {
            known != null && preset != null -> known.copy(name = preset.name, url = url)
            known != null -> known
            preset != null -> Station("preset-${preset.number}", preset.name, url, imageUrl = preset.pictureUrl)
            else -> null
        }
        _state.update { it.copy(current = station) }
    }

    private suspend fun refreshLiveShows() {
        if (System.currentTimeMillis() < liveShowsRefreshAt) return
        liveShowsRefreshAt = System.currentTimeMillis() + 60_000
        try {
            val shows = NtsApi.liveShows()
            _state.update { it.copy(liveShows = shows) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("NTS schedule failed: ${e.describe()}")
        }
    }

    private suspend fun upnpDevice(host: String): UpnpDevice? {
        upnp?.takeIf { it.host == host }?.let { return it }
        if (System.currentTimeMillis() < upnpRetryAt) return null
        return try {
            Upnp.describe(host, Upnp.guessLocation(host)).also {
                upnp = it
                log("UPnP control found: ${it.friendlyName ?: host}")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            upnpRetryAt = System.currentTimeMillis() + 60_000
            null
        }
    }

    /** Looks for the same speaker on the network, in case it got a new IP address. */
    private suspend fun relocate() {
        val saved = _state.value.saved ?: return
        _state.update { it.copy(connection = Connection.Searching) }
        log("Searching the network for ${saved.name}…")
        var match: FoundSpeaker? = null
        try {
            discovery.scan(6000).collect { f ->
                if (match == null && isSameSpeaker(saved, f)) match = f
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("Search failed: ${e.describe()}")
        }
        val m = match ?: return log("Not found yet, will keep trying")
        if (m.host != saved.host) {
            log("Speaker moved: ${saved.host} → ${m.host}")
            client?.host = m.host
            client?.endpoint = null
            upnp = null
            val moved = saved.copy(host = m.host, endpoint = null)
            store.save(moved)
            _state.update { it.copy(saved = moved) }
        }
    }

    private fun isSameSpeaker(saved: SavedSpeaker, f: FoundSpeaker): Boolean {
        val a = saved.uuid?.hexOnly()
        val b = f.udn?.hexOnly()
        if (a != null && b != null && a.length >= 12 && b.length >= 12) {
            return a.take(16) == b.take(16)
        }
        return f.name.equals(saved.name, ignoreCase = true)
    }

    private fun String.hexOnly() = lowercase().removePrefix("uuid:").filter { it.isDigit() || it in 'a'..'f' }

    // ---------------- setup ----------------

    fun startDiscovery() {
        if (_state.value.discovering) return
        _state.update { it.copy(discovering = true, found = emptyList()) }
        viewModelScope.launch {
            try {
                discovery.scan(8000).collect { f ->
                    _state.update { s ->
                        val existing = s.found.indexOfFirst { it.host == f.host }
                        val list = s.found.toMutableList()
                        if (existing >= 0) {
                            // Prefer the entry with more detail, keep the LinkPlay flag if either says so.
                            val old = list[existing]
                            list[existing] = old.copy(
                                model = old.model ?: f.model,
                                udn = old.udn ?: f.udn,
                                via = if (old.via.contains(f.via)) old.via else "${old.via} + ${f.via}",
                                likelyLinkPlay = old.likelyLinkPlay || f.likelyLinkPlay,
                            )
                        } else {
                            list += f
                        }
                        s.copy(found = list.sortedByDescending { it.likelyLinkPlay })
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Discovery error: ${e.describe()}")
            } finally {
                _state.update { it.copy(discovering = false) }
            }
        }
    }

    fun choose(found: FoundSpeaker) = connectTo(found.host, found.name)

    fun chooseManual(ip: String) {
        val host = ip.trim()
        if (host.isEmpty()) return
        connectTo(host, host)
    }

    private fun connectTo(host: String, name: String) {
        stopPolling()
        upnp = null
        presetQueuesChecked = false
        presetsRefreshAt = 0
        val saved = SavedSpeaker(host, name, null, null)
        store.save(saved)
        client = SpeakerClient(getApplication(), host, null, ::log)
        _state.update {
            it.copy(saved = saved, device = null, player = null, screen = Screen.PLAYER, connection = Connection.Connecting)
        }
        log("Connecting to $host")
        startPolling()
    }

    fun forgetSpeaker() {
        stopPolling()
        store.forget()
        client = null
        upnp = null
        _state.update { it.copy(saved = null, device = null, player = null, screen = Screen.SETUP, connection = Connection.Idle) }
        startDiscovery()
    }

    fun show(screen: Screen) {
        _state.update { it.copy(screen = screen) }
        if (screen == Screen.SETUP) startDiscovery()
    }

    // ---------------- controls ----------------

    fun playPause() {
        val st = _state.value.player?.state
        // Nothing is loaded after a source switch or a restart, so resuming would do nothing.
        val last = store.lastStation()
        if (st == PlayState.STOPPED && last != null) return playStation(last)
        action("Play/pause") { c ->
            when (st) {
                PlayState.PLAYING, PlayState.LOADING -> c.pause()
                PlayState.PAUSED, PlayState.STOPPED -> c.play()
                else -> c.togglePlay()
            }
        }
        // Instant feedback while the speaker catches up.
        _state.update { s ->
            val p = s.player ?: return@update s
            val next = if (p.state == PlayState.PLAYING) PlayState.PAUSED else PlayState.PLAYING
            s.copy(player = p.copy(state = next))
        }
    }

    fun next() = action("Next") { it.next() }
    fun previous() = action("Previous") { it.previous() }

    fun setVolume(v: Int) {
        _state.update { s -> s.copy(player = s.player?.copy(volume = v)) }
        action("Volume") { c ->
            try {
                c.setVolume(v)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val dev = upnpDevice(c.host) ?: throw e
                Upnp.setVolume(dev, v)
            }
        }
    }

    fun toggleMute() {
        val muted = _state.value.player?.muted ?: false
        _state.update { s -> s.copy(player = s.player?.copy(muted = !muted)) }
        action("Mute") { it.setMute(!muted) }
    }

    fun switchSource(source: Source) {
        _state.update { it.copy(current = null) }
        action("Switch to ${source.label}") { it.switchSource(source) }
    }

    /** Plays what the speaker has stored, so the app matches the physical preset button. */
    fun playPreset(n: Int) {
        val s = _state.value
        val preset = s.speakerPresets.firstOrNull { it.number == n }
        val label = s.presetNames[n] ?: preset?.name ?: "Preset $n"
        if (preset == null) return action(label, toast = "Playing $label") { it.playPreset(n) }
        val known = Stations.match(preset.url, s.mixtapes)
        playStation(
            (known ?: Station(id = "preset-$n", name = preset.name, url = preset.url, imageUrl = preset.pictureUrl))
                .copy(name = preset.name, url = preset.url),
            preset,
        )
    }

    fun playStation(station: Station, preset: SpeakerPreset? = null) {
        store.saveLastStation(station)
        liveShowsRefreshAt = 0
        ignoreSpeakerTrackUntil = System.currentTimeMillis() + 5000
        _state.update { s ->
            s.copy(current = station, player = s.player?.copy(state = PlayState.LOADING, title = null, artist = null, album = null))
        }
        action(station.name, toast = "Playing ${station.name}") { c ->
            val dev = if (preset != null) upnpDevice(c.host) else null
            try {
                if (dev == null) playStream(c, station) else Upnp.playPreset(dev, preset!!)
            } catch (e: IOException) {
                log("Preset queue failed, playing its URL: ${e.describe()}")
                playStream(c, station)
            }
        }
    }

    private suspend fun playStream(c: SpeakerClient, station: Station) {
        val isHls = ".m3u8" in station.url.lowercase()
        val dev = if (isHls) null else upnpDevice(c.host)
        if (dev != null) {
            try {
                Upnp.playUrl(dev, station.url, station.name)
                return
            } catch (e: IOException) {
                log("UPnP play failed, trying HTTP: ${e.describe()}")
            }
        }
        c.playUrl(station.url)
    }

    /** Stores a station on a preset button, so the speaker plays it without the app. */
    fun assignPreset(n: Int, station: Station) {
        val name = Stations.presetName(station)
        action("Saving to button $n", toast = "Saved $name to button $n") { c ->
            val dev = upnpDevice(c.host) ?: throw IOException("UPnP not available")
            Upnp.savePreset(dev, n, name, station.url, station.imageUrl)
            store.setPresetName(n, null)
            val presets = Upnp.getPresets(dev)
            _state.update { it.copy(speakerPresets = presets, presetNames = loadPresetNames()) }
            log("Saved $name to button $n")
        }
    }

    fun saveStation(original: Station?, name: String, url: String, image: String, icon: String, color: Long) {
        val station = Station(
            id = original?.id ?: (Station.CUSTOM_PREFIX + UUID.randomUUID()),
            name = name.trim(),
            url = url.trim(),
            color = color,
            imageUrl = image.trim().ifEmpty { null },
            icon = icon,
        )
        val others = _state.value.customStations.filterNot { it.id == station.id }
        val list = if (original == null) others + station else _state.value.customStations.map {
            if (it.id == station.id) station else it
        }
        store.saveCustomStations(list)
        _state.update { it.copy(customStations = list) }
    }

    fun deleteStation(station: Station) {
        val list = _state.value.customStations.filterNot { it.id == station.id }
        store.saveCustomStations(list)
        _state.update { it.copy(customStations = list) }
    }

    /** Copies a picked image into app storage, because the picker's permission doesn't last. */
    fun importImage(uri: Uri): String? {
        val app = getApplication<Application>()
        return try {
            val dir = File(app.filesDir, "station-art").apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString())
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Can't open image")
            input.use { src -> file.outputStream().use { src.copyTo(it) } }
            file.path
        } catch (e: IOException) {
            log("Image import failed: ${e.describe()}")
            null
        }
    }

    fun logMessage(line: String) = log(line)

    fun renamePreset(n: Int, name: String?) {
        store.setPresetName(n, name)
        _state.update { it.copy(presetNames = loadPresetNames()) }
    }

    private fun action(label: String, toast: String? = null, block: suspend (SpeakerClient) -> Any) {
        val c = client ?: return
        // Hold the next poll a moment so it doesn't overwrite the optimistic UI with stale data.
        pausePollingUntil = System.currentTimeMillis() + 1200
        viewModelScope.launch {
            try {
                block(c)
                if (toast != null) _state.update { it.copy(message = toast) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("$label failed: ${e.describe()}")
                _state.update { it.copy(message = "$label failed — retrying connection") }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    // ---------------- diagnostics ----------------

    fun runEndpointTest() {
        val c = client ?: return
        _state.update { it.copy(testing = true, endpointTests = emptyList()) }
        viewModelScope.launch {
            val results = c.testAllEndpoints().toMutableList()
            results += try {
                val d = Upnp.describe(c.host, Upnp.guessLocation(c.host))
                "UPnP OK: ${d.friendlyName} / ${d.manufacturer} ${d.modelName} / volume=${Upnp.getVolume(d)}"
            } catch (e: Exception) {
                "UPnP FAILED: ${e.describe()}"
            }
            results.forEach { log("Test: $it") }
            _state.update { it.copy(testing = false, endpointTests = results) }
        }
    }

    fun diagnosticsReport(): String {
        val s = _state.value
        return buildString {
            appendLine("Audio Pro Player diagnostics — ${Date()}")
            appendLine("Host: ${s.saved?.host}  endpoint: ${client?.endpoint}  connection: ${s.connection}")
            appendLine()
            appendLine("getStatusEx:")
            appendLine(s.device?.raw ?: "(none)")
            appendLine()
            appendLine("getPlayerStatus:")
            appendLine(s.player?.raw ?: "(none)")
            appendLine()
            appendLine("Endpoint tests:")
            s.endpointTests.forEach { appendLine(it) }
            appendLine()
            appendLine("Log:")
            s.log.forEach { appendLine(it) }
        }
    }

    // ---------------- helpers ----------------

    private fun loadPresetNames(): Map<Int, String> =
        (1..10).mapNotNull { n -> store.presetName(n)?.let { n to it } }.toMap()


    private fun log(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        _state.update { it.copy(log = (it.log + stamped).takeLast(300)) }
    }
}
