package com.roberrini.audiopro.net

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL

/** A UPnP MediaRenderer found on the network, with the control URLs we care about. */
data class UpnpDevice(
    val host: String,
    val location: String,
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val udn: String?,
    val renderingControlUrl: String?,
    val avTransportUrl: String?,
    val playQueueUrl: String? = null,
) {
    /**
     * The PlayQueue service is the reliable sign: people rename their speakers, and Audio Pro
     * firmware leaves the manufacturer and model fields empty.
     */
    val looksLikeLinkPlay: Boolean
        get() = playQueueUrl != null || listOfNotNull(manufacturer, modelName, friendlyName).any {
            val s = it.lowercase()
            "audio pro" in s || "audiopro" in s || "linkplay" in s || "wiimu" in s
        }
}

/** A preset stored on the speaker, the same one its physical button plays. */
data class SpeakerPreset(
    val number: Int,
    val name: String,
    val url: String,
    val pictureUrl: String?,
    /** The stored entry, still XML-escaped, for rebuilding the button's queue. */
    val rawName: String,
    val rawUrl: String,
    val rawMetadata: String,
    val source: String,
) {
    /** Only radio presets are one URL. Rebuilding a Spotify or TIDAL preset here would break it. */
    val isRadio: Boolean
        get() = url.startsWith("http") && ("radio" in source.lowercase() || "tunein" in source.lowercase())
}

/** What the speaker is playing, from the LinkPlay GetInfoEx action. */
data class UpnpTrack(
    val title: String?,
    val artist: String?,
    val album: String?,
    val source: String?,
    /** Empty for HLS streams started over HTTP. */
    val url: String?,
)

/**
 * Minimal UPnP helper.
 *
 * On Audio Pro MkII firmware the HTTP API does not report volume or play state,
 * but the standard UPnP RenderingControl / AVTransport services do. UPnP SSDP is
 * also a second way to discover the speaker when mDNS is filtered by the router.
 */
object Upnp {
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val PLAY_QUEUE = "urn:schemas-wiimu-com:service:PlayQueue:1"
    private val KEY_BLOCK = Regex("<Key(\\d+)>(.*?)</Key\\1>", RegexOption.DOT_MATCHES_ALL)
    private val NO_VALUE = setOf("unknown", "unknow", "un_known")

    /** Sends an SSDP M-SEARCH and returns the description URLs that answered. Caller holds a MulticastLock. */
    suspend fun search(timeoutMs: Int = 3000): Map<String, String> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, String>() // host -> location
        val msg = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            ).toByteArray()
        MulticastSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(null)
            socket.soTimeout = 500
            val group = InetAddress.getByName(SSDP_ADDR)
            repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, SSDP_PORT)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length)
                val location = text.lineSequence()
                    .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                val host = packet.address.hostAddress
                if (location != null && host != null) found.putIfAbsent(host, location)
            }
        }
        found
    }

    /** LinkPlay modules usually publish their description here. */
    fun guessLocation(host: String) = "http://$host:49152/description.xml"

    suspend fun describe(host: String, location: String): UpnpDevice = withContext(Dispatchers.IO) {
        val xml = httpGet(location)
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        var friendlyName: String? = null
        var manufacturer: String? = null
        var modelName: String? = null
        var udn: String? = null
        var urlBase: String? = null
        val services = mutableMapOf<String, String>()
        var currentType: String? = null
        var currentControl: String? = null
        var tag: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> tag = parser.name
                XmlPullParser.TEXT -> {
                    val t = parser.text.trim()
                    if (t.isNotEmpty()) when (tag) {
                        "friendlyName" -> if (friendlyName == null) friendlyName = t
                        "manufacturer" -> if (manufacturer == null) manufacturer = t
                        "modelName" -> if (modelName == null) modelName = t
                        "UDN" -> if (udn == null) udn = t
                        "URLBase" -> urlBase = t
                        "serviceType" -> currentType = t
                        "controlURL" -> currentControl = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "service") {
                        val type = currentType
                        val control = currentControl
                        if (type != null && control != null) services[type] = control
                        currentType = null
                        currentControl = null
                    }
                    tag = null
                }
            }
            parser.next()
        }

        val base = URL(urlBase ?: location)
        fun abs(path: String?) = path?.let { URL(base, it).toString() }
        UpnpDevice(
            host = host,
            location = location,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            modelName = modelName,
            udn = udn,
            renderingControlUrl = abs(services.entries.firstOrNull { it.key.startsWith(RC.dropLast(1)) }?.value),
            avTransportUrl = abs(services.entries.firstOrNull { it.key.startsWith(AVT.dropLast(1)) }?.value),
            playQueueUrl = abs(services.entries.firstOrNull { it.key.startsWith(PLAY_QUEUE.dropLast(1)) }?.value),
        )
    }

    suspend fun getVolume(dev: UpnpDevice): Int? {
        val url = dev.renderingControlUrl ?: return null
        val body = soap(url, RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        return tagValue(body, "CurrentVolume")?.toIntOrNull()
    }

    suspend fun setVolume(dev: UpnpDevice, volume: Int) {
        val url = dev.renderingControlUrl ?: throw IOException("No RenderingControl service")
        soap(
            url, RC, "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
        )
    }

    suspend fun getMute(dev: UpnpDevice): Boolean? {
        val url = dev.renderingControlUrl ?: return null
        val body = soap(url, RC, "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        return tagValue(body, "CurrentMute")?.let { it == "1" || it.equals("true", true) }
    }

    suspend fun getPlayState(dev: UpnpDevice): PlayState? {
        val url = dev.avTransportUrl ?: return null
        val body = soap(url, AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        return when (tagValue(body, "CurrentTransportState")) {
            "PLAYING" -> PlayState.PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlayState.PAUSED
            "STOPPED", "NO_MEDIA_PRESENT" -> PlayState.STOPPED
            "TRANSITIONING" -> PlayState.LOADING
            null -> null
            else -> PlayState.UNKNOWN
        }
    }

    /**
     * Plays a stream and names it, so the speaker reports [title] as the track.
     * Rejects HLS playlists with UPnP error 714: send those through SpeakerClient.playUrl.
     */
    suspend fun playUrl(dev: UpnpDevice, url: String, title: String) {
        val control = dev.avTransportUrl ?: throw IOException("No AVTransport service")
        val didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"0\" parentID=\"0\" restricted=\"1\"><dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/mpeg:*\">${escapeXml(url)}</res></item></DIDL-Lite>"
        soap(
            control, AVT, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${escapeXml(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>",
        )
        soap(control, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    /** Reads the presets stored on the speaker. Empty slots are left out. */
    suspend fun getPresets(dev: UpnpDevice): List<SpeakerPreset> {
        val control = dev.playQueueUrl ?: return emptyList()
        val body = soap(control, PLAY_QUEUE, "GetKeyMapping", "")
        val xml = tagValue(body, "QueueContext")?.let(::unescapeXml).orEmpty()
        return KEY_BLOCK.findAll(xml).mapNotNull { m ->
            val block = m.groupValues[2]
            val rawUrl = tagValue(block, "Url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawName = tagValue(block, "Name").orEmpty()
            val url = unescapeXml(rawUrl)
            SpeakerPreset(
                number = m.groupValues[1].toInt(),
                // Stored names carry a timestamp suffix: "NTS1_#~1793181170".
                name = unescapeXml(rawName).substringBefore("_#~").ifBlank { url },
                url = url,
                pictureUrl = tagValue(block, "PicUrl")?.let(::unescapeXml)?.takeIf { it.isNotBlank() },
                rawName = rawName,
                rawUrl = rawUrl,
                rawMetadata = tagValue(block, "Metadata").orEmpty(),
                source = tagValue(block, "Source").orEmpty(),
            )
        }.toList()
    }

    /**
     * A physical preset button only starts when the speaker holds a queue named exactly like the
     * preset, tagged with that button number (PressType) and holding its track. Without a queue the
     * button just flashes. A restart keeps the queue but empties it, so the light stays on in silence.
     * Returns how many queues had to be rebuilt.
     */
    suspend fun ensurePresetQueues(dev: UpnpDevice, presets: List<SpeakerPreset>): Int {
        val control = dev.playQueueUrl ?: return 0
        val broken = presets.filter { it.isRadio && !queueIsReady(control, it) }
        broken.forEach {
            removeQueue(control, it)
            addQueue(control, it)
        }
        return broken.size
    }

    /** Plays a preset the way its physical button does, so the speaker lights that button. */
    suspend fun playPreset(dev: UpnpDevice, preset: SpeakerPreset) {
        val control = dev.playQueueUrl ?: throw IOException("No PlayQueue service")
        soap(control, PLAY_QUEUE, "PlayQueueWithIndex", "<QueueName>${preset.rawName}</QueueName><Index>1</Index>")
    }

    private suspend fun queueIsReady(control: String, preset: SpeakerPreset): Boolean = try {
        val body = soap(control, PLAY_QUEUE, "BrowseQueue", "<QueueName>${preset.rawName}</QueueName>")
        val queue = tagValue(body, "QueueContext")?.let(::unescapeXml).orEmpty()
        tagValue(queue, "PressType")?.toIntOrNull() == preset.number && !tagValue(queue, "URL").isNullOrBlank()
    } catch (e: IOException) {
        false // The speaker answers 501 for a queue it doesn't have.
    }

    private suspend fun removeQueue(control: String, preset: SpeakerPreset) {
        try {
            soap(control, PLAY_QUEUE, "DeleteQueue", "<QueueName>${preset.rawName}</QueueName>")
        } catch (e: IOException) {
            // Nothing to remove.
        }
    }

    private suspend fun addQueue(control: String, p: SpeakerPreset) {
        val context = "<?xml version=\"1.0\"?><PlayList><ListName>${p.rawName}</ListName><ListInfo>" +
            "<SourceName>${p.source}</SourceName><Radio>1</Radio><MarkSearch>0</MarkSearch>" +
            "<TrackNumber>1</TrackNumber><Quality>0</Quality><UpdateTime>0</UpdateTime>" +
            "<LastPlayIndex>1</LastPlayIndex><RealIndex>0</RealIndex><SwitchPageMode>0</SwitchPageMode>" +
            "<CurrentPage>0</CurrentPage><TotalPages>0</TotalPages><searching>0</searching>" +
            "<PressType>${p.number}</PressType><Volume>0</Volume></ListInfo><Tracks><Track1>" +
            "<URL>${p.rawUrl}</URL><Metadata>${p.rawMetadata}</Metadata><Source>${p.source}</Source>" +
            "<ChapterNumber>0</ChapterNumber><Chapters></Chapters></Track1></Tracks></PlayList>"
        val args = "<QueueContext>${escapeXml(context)}</QueueContext>"
        // CreateQueue can replace what's there, so it is only for a speaker with no queues to append to.
        try {
            soap(control, PLAY_QUEUE, "AppendQueue", args)
        } catch (e: IOException) {
            soap(control, PLAY_QUEUE, "CreateQueue", args)
        }
    }

    /** Returns null when the firmware has no GetInfoEx action: it is LinkPlay-specific, not standard UPnP. */
    suspend fun getTrack(dev: UpnpDevice): UpnpTrack? {
        val url = dev.avTransportUrl ?: return null
        val body = try {
            soap(url, AVT, "GetInfoEx", "<InstanceID>0</InstanceID>")
        } catch (e: IOException) {
            return null
        }
        // TrackMetaData is a DIDL-Lite document escaped inside the SOAP reply.
        val meta = tagValue(body, "TrackMetaData")?.let(::unescapeXml).orEmpty()
        fun field(vararg tags: String) = tags.firstNotNullOfOrNull { tag ->
            tagValue(meta, tag)?.let(::unescapeXml)?.trim()
                ?.takeUnless { it.isEmpty() || it.lowercase() in NO_VALUE }
        }
        return UpnpTrack(
            title = field("dc:title"),
            artist = field("upnp:artist", "dc:creator"),
            album = field("upnp:album"),
            source = sourceName(tagValue(body, "PlayMedium"), tagValue(body, "TrackSource")),
            url = tagValue(body, "TrackURI")?.let(::unescapeXml)?.takeIf { it.startsWith("http") },
        )
    }

    private fun sourceName(medium: String?, trackSource: String?): String? {
        val m = medium?.uppercase().orEmpty()
        return when {
            "BLUETOOTH" in m -> "Bluetooth"
            "LINE" in m -> "Line in"
            "OPTICAL" in m -> "Optical"
            "RADIO-NETWORK" in m -> "Internet radio"
            m.isEmpty() || m == "UNKNOWN" || m == "NONE" -> null
            else -> trackSource?.takeIf { it.isNotBlank() } ?: "Wi‑Fi"
        }
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescapeXml(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private suspend fun soap(url: String, service: String, action: String, args: String): String =
        withContext(Dispatchers.IO) {
            val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 2500
                conn.readTimeout = 4000
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPACTION", "\"$service#$action\"")
                conn.outputStream.use { it.write(envelope.toByteArray()) }
                if (conn.responseCode != 200) throw IOException("UPnP $action HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2500
            conn.readTimeout = 4000
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun tagValue(xml: String, tag: String): String? =
        Regex("<(?:\\w+:)?$tag>([^<]*)</(?:\\w+:)?$tag>").find(xml)?.groupValues?.get(1)?.trim()
}
