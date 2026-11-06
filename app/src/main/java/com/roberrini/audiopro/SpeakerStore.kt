package com.roberrini.audiopro

import android.content.Context
import com.roberrini.audiopro.net.Endpoint
import com.roberrini.audiopro.net.Station
import org.json.JSONObject

data class SavedSpeaker(
    val host: String,
    val name: String,
    val uuid: String?,
    val endpoint: Endpoint?,
)

/** Remembers the chosen speaker and preset names between launches. */
class SpeakerStore(context: Context) {
    private val prefs = context.getSharedPreferences("speaker", Context.MODE_PRIVATE)

    fun load(): SavedSpeaker? {
        val host = prefs.getString("host", null) ?: return null
        return SavedSpeaker(
            host = host,
            name = prefs.getString("name", null) ?: host,
            uuid = prefs.getString("uuid", null),
            endpoint = Endpoint.parse(prefs.getString("endpoint", null)),
        )
    }

    fun save(s: SavedSpeaker) {
        prefs.edit()
            .putString("host", s.host)
            .putString("name", s.name)
            .putString("uuid", s.uuid)
            .putString("endpoint", s.endpoint?.let { "${it.scheme}:${it.port}" })
            .apply()
    }

    fun forget() {
        prefs.edit().remove("host").remove("name").remove("uuid").remove("endpoint").apply()
    }

    fun customStations(): List<Station> = Station.listFromJson(prefs.getString("custom_stations", null))

    fun saveCustomStations(list: List<Station>) {
        prefs.edit().putString("custom_stations", Station.listToJson(list)).apply()
    }

    /** The station Play restarts when the speaker has nothing loaded. */
    fun lastStation(): Station? =
        prefs.getString("last_station", null)?.let { Station.fromJson(JSONObject(it)) }

    fun saveLastStation(station: Station) {
        prefs.edit().putString("last_station", station.toJson().toString()).apply()
    }

    fun presetName(number: Int): String? = prefs.getString("preset_$number", null)

    fun setPresetName(number: Int, name: String?) {
        prefs.edit().apply {
            if (name.isNullOrBlank()) remove("preset_$number") else putString("preset_$number", name.trim())
        }.apply()
    }
}
