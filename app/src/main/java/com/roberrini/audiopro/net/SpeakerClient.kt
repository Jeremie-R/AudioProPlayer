package com.roberrini.audiopro.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** One way of reaching the speaker's HTTP API. */
data class Endpoint(val scheme: String, val port: Int) {
    fun base(host: String) = "$scheme://$host:$port"
    override fun toString() = "$scheme:$port"

    companion object {
        /**
         * Order matters: Audio Pro MkII / W firmware answers on 4443 with mutual TLS,
         * other LinkPlay firmware on 443, a few on 8443, very old ones on plain HTTP.
         */
        val PROBE_ORDER = listOf(
            Endpoint("https", 4443),
            Endpoint("https", 443),
            Endpoint("https", 8443),
            Endpoint("http", 80),
        )

        fun parse(text: String?): Endpoint? {
            val parts = text?.split(":") ?: return null
            if (parts.size != 2) return null
            val port = parts[1].toIntOrNull() ?: return null
            return Endpoint(parts[0], port)
        }
    }
}

data class DeviceInfo(
    val name: String,
    val uuid: String?,
    val firmware: String?,
    val model: String?,
    val ssid: String?,
    val rssi: Int?,
    val ip: String?,
    val presetCount: Int,
    val raw: String,
)

enum class PlayState { PLAYING, PAUSED, STOPPED, LOADING, UNKNOWN }

data class PlayerStatus(
    val state: PlayState,
    val volume: Int?,
    val muted: Boolean?,
    val source: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val raw: String,
)

enum class Source(val label: String, val command: String) {
    WIFI("Wi‑Fi", "wifi"),
    BLUETOOTH("Bluetooth", "bluetooth"),
    LINE_IN("Line in", "line-in"),
}

class SpeakerException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Talks to one LinkPlay speaker through `/httpapi.asp?command=...`.
 *
 * It finds out which port/protocol the speaker answers on the first time and
 * remembers it. If a request later fails, it probes again — that is what keeps
 * the app working when the speaker restarts its web server or changes mode.
 */
class SpeakerClient(
    context: Context,
    @Volatile var host: String,
    @Volatile var endpoint: Endpoint? = null,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    // ---------- transport ----------

    /** Sends a raw command and returns the response body. Probes ports if needed. */
    suspend fun command(cmd: String): String = withContext(Dispatchers.IO) {
        endpoint?.let { ep ->
            try {
                return@withContext request(ep, cmd)
            } catch (e: IOException) {
                log("$ep failed for '$cmd': ${e.describe()} — probing again")
                endpoint = null
            }
        }
        val ep = probe()
        request(ep, cmd)
    }

    /** Tries every known endpoint and keeps the first that answers getStatusEx. */
    suspend fun probe(): Endpoint = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (ep in Endpoint.PROBE_ORDER) {
            try {
                val body = request(ep, "getStatusEx", connectTimeoutMs = 2000, readTimeoutMs = 5000)
                if (body.trim().startsWith("{")) {
                    log("Connected to $host on $ep")
                    endpoint = ep
                    return@withContext ep
                }
                errors += "$ep: unexpected reply '${body.take(40)}'"
            } catch (e: IOException) {
                errors += "$ep: ${e.describe()}"
            }
        }
        errors.forEach(log)
        throw SpeakerException("Speaker at $host did not answer on any port")
    }

    /** Diagnostic: try every endpoint and report what happened on each. */
    suspend fun testAllEndpoints(): List<String> = withContext(Dispatchers.IO) {
        Endpoint.PROBE_ORDER.map { ep ->
            val started = System.currentTimeMillis()
            try {
                val body = request(ep, "getStatusEx", connectTimeoutMs = 2000, readTimeoutMs = 5000)
                "$ep OK in ${System.currentTimeMillis() - started} ms: ${body.take(80)}"
            } catch (e: IOException) {
                "$ep FAILED: ${e.describe()}"
            }
        }
    }

    private fun request(
        ep: Endpoint,
        cmd: String,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 6000,
    ): String {
        val url = URL("${ep.base(host)}/httpapi.asp?command=$cmd")
        val conn = url.openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = LinkPlayTls.socketFactory(appContext)
            conn.hostnameVerifier = LinkPlayTls.trustAllHostnames
        }
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.setRequestProperty("Connection", "close")
        try {
            val code = conn.responseCode
            if (code != 200) throw SpeakerException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- reading ----------

    suspend fun deviceInfo(): DeviceInfo {
        val raw = command("getStatusEx")
        val j = JSONObject(raw)
        return DeviceInfo(
            name = j.str("DeviceName") ?: j.str("ssid") ?: host,
            uuid = j.str("uuid"),
            firmware = j.str("firmware"),
            model = j.str("project") ?: j.str("hardware"),
            ssid = j.str("essid")?.let(::decodeHex) ?: j.str("ssid"),
            rssi = j.str("RSSI")?.toIntOrNull(),
            ip = j.str("apcli0") ?: j.str("eth0"),
            presetCount = j.str("preset_key")?.toIntOrNull()?.takeIf { it in 1..20 } ?: 6,
            raw = raw,
        )
    }

    suspend fun playerStatus(): PlayerStatus {
        val raw = command("getPlayerStatus")
        // Audio Pro MkII firmware answers "unknown command" here. The speaker did reply, so it is
        // online: return an empty status and let the caller fill it in from UPnP.
        val j = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return PlayerStatus(PlayState.UNKNOWN, null, null, null, null, null, null, raw)
        }
        val state = when (j.str("status")?.lowercase()) {
            "play", "playing" -> PlayState.PLAYING
            "pause", "paused" -> PlayState.PAUSED
            "stop", "stopped", "none" -> PlayState.STOPPED
            "load", "loading", "buffering" -> PlayState.LOADING
            else -> PlayState.UNKNOWN
        }
        return PlayerStatus(
            state = state,
            volume = j.str("vol")?.toIntOrNull(),
            muted = j.str("mute")?.let { it == "1" },
            source = j.str("mode")?.let { MODE_NAMES[it] ?: "mode $it" },
            title = j.str("Title")?.let(::decodeHex),
            artist = j.str("Artist")?.let(::decodeHex),
            album = j.str("Album")?.let(::decodeHex),
            raw = raw,
        )
    }

    // ---------- controls ----------

    suspend fun play() = command("setPlayerCmd:resume")
    suspend fun pause() = command("setPlayerCmd:pause")
    suspend fun togglePlay() = command("setPlayerCmd:onepause")
    suspend fun next() = command("setPlayerCmd:next")
    suspend fun previous() = command("setPlayerCmd:prev")
    suspend fun setVolume(v: Int) = command("setPlayerCmd:vol:${v.coerceIn(0, 100)}")
    suspend fun setMute(muted: Boolean) = command("setPlayerCmd:mute:${if (muted) 1 else 0}")
    suspend fun switchSource(source: Source) = command("setPlayerCmd:switchmode:${source.command}")

    /** Plays a stream without naming it. This is the only route that accepts HLS (.m3u8) playlists. */
    suspend fun playUrl(url: String): String {
        val escaped = url.replace("%", "%25").replace("&", "%26").replace("#", "%23").replace("+", "%2B")
        return command("setPlayerCmd:play:$escaped")
    }

    /**
     * Simulates preset button [number] (1-based). Only a fallback for firmware whose presets can't be
     * read: on C10 MkII firmware 1.56 this replies OK but acts as play/pause, unlike the real button.
     */
    suspend fun playPreset(number: Int) = command("MCUKeyShortClick:$number")

    companion object {
        val MODE_NAMES = mapOf(
            "0" to "Idle", "1" to "AirPlay", "2" to "DLNA", "3" to "Wi‑Fi", "4" to "Line in",
            "5" to "Bluetooth", "6" to "Optical", "10" to "Wi‑Fi", "11" to "USB", "20" to "Wi‑Fi",
            "31" to "Spotify", "32" to "TIDAL", "36" to "Qobuz", "40" to "Line in",
            "41" to "Bluetooth", "43" to "Optical", "44" to "RCA", "47" to "Line in 2",
            "51" to "USB", "60" to "Line in", "99" to "Group follower",
        )

        /** LinkPlay sends titles as hex-encoded UTF-8. Leaves plain text untouched. */
        fun decodeHex(value: String): String? {
            val v = value.trim()
            if (v.isEmpty() || v.equals("unknow", true) || v.equals("unknown", true)) return null
            if (v.length % 2 != 0 || !v.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return v
            return try {
                val bytes = ByteArray(v.length / 2) { i -> v.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                val text = String(bytes, Charsets.UTF_8).trim()
                when {
                    // Not valid UTF-8 / binary: it was probably plain text that only looked like hex.
                    text.any { it == '�' || (it < ' ' && it != '\n') } -> v
                    text.isEmpty() || text.equals("unknow", true) || text.equals("unknown", true) -> null
                    else -> text
                }
            } catch (e: Exception) {
                v
            }
        }

        private fun JSONObject.str(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    }
}

fun Throwable.describe(): String = "${javaClass.simpleName}: ${message ?: "no message"}"
