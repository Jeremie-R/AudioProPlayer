package com.roberrini.audiopro.net

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The show on air. Each episode has its own picture, so its URL changes with the schedule. */
data class LiveShow(val title: String, val imageUrl: String?)

/** Public NTS API: mixtape artwork and what is on air. */
object NtsApi {
    private const val BASE = "https://www.nts.live/api/v2"

    /** The Infinite Mixtapes, with their picture and distinctive icon. */
    suspend fun mixtapes(): List<Station> = withContext(Dispatchers.IO) {
        val results = JSONObject(get("$BASE/mixtapes")).getJSONArray("results")
        (0 until results.length()).map { i ->
            val m = results.getJSONObject(i)
            val media = m.optJSONObject("media")
            Station(
                id = "nts-${m.getString("mixtape_alias")}",
                name = m.getString("title"),
                url = m.getString("audio_stream_endpoint"),
                subtitle = m.optString("subtitle").takeIf { it.isNotBlank() },
                color = 0xFF000000,
                imageUrl = media?.optString("picture_medium")?.takeIf { it.isNotBlank() },
                iconUrl = media?.optString("icon_white")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** Maps station id ("nts1", "nts2") to the show on air. */
    suspend fun liveShows(): Map<String, LiveShow> = withContext(Dispatchers.IO) {
        val results = JSONObject(get("$BASE/live")).getJSONArray("results")
        (0 until results.length()).mapNotNull { i ->
            val channel = results.getJSONObject(i)
            val now = channel.optJSONObject("now") ?: return@mapNotNull null
            val title = now.optString("broadcast_title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val image = now.optJSONObject("embeds")?.optJSONObject("details")?.optJSONObject("media")
                ?.optString("picture_medium")?.takeIf { it.isNotBlank() }
            val text = Html.fromHtml(title, Html.FROM_HTML_MODE_LEGACY).toString()
            "nts${channel.getString("channel_name")}" to LiveShow(text, image)
        }.toMap()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
