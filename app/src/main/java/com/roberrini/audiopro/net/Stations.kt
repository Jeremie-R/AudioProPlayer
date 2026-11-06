package com.roberrini.audiopro.net

import org.json.JSONArray
import org.json.JSONObject

/** Something the speaker can stream. [imageUrl] is an http(s) URL or a local file path. */
data class Station(
    val id: String,
    val name: String,
    val url: String,
    val subtitle: String? = null,
    val color: Long = 0xFF2E3440,
    val imageUrl: String? = null,
    val iconUrl: String? = null,
    val icon: String = "radio",
    /** Short text drawn over the picture, like the 1 and 2 on the live NTS tiles. */
    val badge: String? = null,
) {
    val isCustom get() = id.startsWith(CUSTOM_PREFIX)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("subtitle", subtitle)
        .put("color", color).put("imageUrl", imageUrl).put("iconUrl", iconUrl).put("icon", icon)

    companion object {
        const val CUSTOM_PREFIX = "custom-"

        fun fromJson(j: JSONObject) = Station(
            id = j.getString("id"),
            name = j.getString("name"),
            url = j.getString("url"),
            subtitle = j.optString("subtitle").takeIf { it.isNotEmpty() && j.has("subtitle") },
            color = j.optLong("color", 0xFF2E3440),
            imageUrl = j.optString("imageUrl").takeIf { it.isNotEmpty() },
            iconUrl = j.optString("iconUrl").takeIf { it.isNotEmpty() },
            icon = j.optString("icon", "radio"),
        )

        fun listToJson(list: List<Station>): String =
            JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<Station> {
            if (text.isNullOrBlank()) return emptyList()
            val a = JSONArray(text)
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
    }
}

/** Built-in stations. Every URL here was checked against the speaker's accepted formats. */
object Stations {
    private const val NTS = "https://stream-mixtape-geo.ntslive.net"
    private const val BBC = "http://as-hls-ww-live.akamaized.net"

    private fun tunein(id: String, file: String = "logoq.png") = "https://cdn-profiles.tunein.com/$id/images/$file"

    private fun bbc(id: String, name: String, pool: String, slug: String, color: Long, tuneinId: String) = Station(
        id = id,
        name = name,
        url = "$BBC/pool_$pool/live/ww/$slug/$slug.isml/$slug-audio%3d320000.norewind.m3u8",
        color = color,
        imageUrl = tunein(tuneinId),
    )

    val ntsLive = listOf(
        Station("nts1", "NTS 1", "https://stream-relay-geo.ntslive.net/stream", "Live", 0xFF000000, badge = "1"),
        Station("nts2", "NTS 2", "https://stream-relay-geo.ntslive.net/stream2", "Live", 0xFF000000, badge = "2"),
    )

    /** Shown until the NTS API answers, or if it can't be reached. */
    val ntsMixtapes = listOf(
        "Poolside" to "mixtape4", "Slow Focus" to "mixtape", "Low Key" to "mixtape2",
        "Memory Lane" to "mixtape6", "4 To The Floor" to "mixtape5", "Island Time" to "mixtape21",
        "The Tube" to "mixtape26", "Sheet Music" to "mixtape35", "Feelings" to "mixtape27",
        "Expansions" to "mixtape3", "Rap House" to "mixtape22", "Labyrinth" to "mixtape31",
        "Sweat" to "mixtape24", "Otaku" to "mixtape36", "The Pit" to "mixtape34",
        "Field Recordings" to "mixtape23",
    ).map { (name, path) -> Station("nts-$path", name, "$NTS/$path", color = 0xFF000000) }

    val bbc = listOf(
        bbc("bbc1", "Radio 1", "01505109", "bbc_radio_one", 0xFFD6006D, "s24939"),
        bbc("bbc2", "Radio 2", "74208725", "bbc_radio_two", 0xFFE5571F, "s24940"),
        bbc("bbc3", "Radio 3", "23461179", "bbc_radio_three", 0xFF8A1538, "s24941"),
        bbc("bbc4", "Radio 4", "55057080", "bbc_radio_fourfm", 0xFF1F5FA8, "s25419"),
        bbc("bbc5", "Radio 5 Live", "89021708", "bbc_radio_five_live", 0xFF00838F, "s24943"),
        bbc("bbc6", "6 Music", "81827798", "bbc_6music", 0xFF2E7D32, "s44491"),
    )

    val npr = listOf(
        Station("npr", "NPR", "https://npr-ice.streamguys1.com/live.mp3", "News", 0xFF237BBD, imageUrl = tunein("p3020988")),
    )

    val indie = listOf(
        Station("kexp", "KEXP", "https://kexp-mp3-128.streamguys1.com/kexp128.mp3", "Seattle", 0xFF7A3E9D, imageUrl = tunein("s32537")),
        Station("fip", "FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3", "Paris", 0xFFE2007A, imageUrl = tunein("s15200")),
        Station("wfmu", "WFMU", "https://stream0.wfmu.org/freeform-128k", "New Jersey", 0xFFB23A2E, imageUrl = tunein("s28808", "logoq.jpg")),
        Station("dublab", "dublab", "https://dublab.out.airtime.pro/dublab_a", "Los Angeles", 0xFF00897B, imageUrl = tunein("s17176")),
        Station("paradise", "Radio Paradise", "https://stream.radioparadise.com/mp3-192", "California", 0xFF3F6C9B, imageUrl = tunein("s13606")),
    )

    /** Finds the catalogue entry for a URL, ignoring query strings like `?client=direct`. */
    fun match(url: String, mixtapes: List<Station>): Station? {
        val key = url.substringBefore('?')
        return (ntsLive + mixtapes + bbc + npr + indie).firstOrNull {
            it.url.substringBefore('?') == key || sameBbcStream(it.url, key)
        }
    }

    /** BBC URLs differ only by bitrate, for example a preset stored at a different quality. */
    private fun sameBbcStream(a: String, b: String) =
        "-audio%3d" in a && "-audio%3d" in b && a.substringBefore("-audio%3d") == b.substringBefore("-audio%3d")
}
