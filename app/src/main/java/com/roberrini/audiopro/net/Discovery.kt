package com.roberrini.audiopro.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class FoundSpeaker(
    val host: String,
    val name: String,
    val via: String,
    val model: String? = null,
    val udn: String? = null,
    val likelyLinkPlay: Boolean = true,
)

/**
 * Finds speakers two ways at once:
 *  - mDNS / Bonjour: LinkPlay advertises `_linkplay._tcp`
 *  - UPnP SSDP: any MediaRenderer; LinkPlay ones are flagged by manufacturer name
 * Some routers block one of the two, so running both makes discovery much more reliable.
 */
class Discovery(context: Context, private val log: (String) -> Unit = {}) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun scan(durationMs: Long = 8000): Flow<FoundSpeaker> = callbackFlow {
        val lock = wifi.createMulticastLock("audiopro-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }

        // ---- mDNS ----
        // Older Android versions can only resolve one service at a time, so queue them.
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log("mDNS: searching $serviceType")
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                log("mDNS: could not start (error $errorCode)")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        var nsdStarted = false
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdStarted = true
        } catch (e: Exception) {
            log("mDNS: ${e.describe()}")
        }

        launch {
            for (info in toResolve) {
                val resolved = resolve(info) ?: continue
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress ?: continue
                if (':' in host) continue // skip IPv6 link-local, the speaker always has IPv4
                trySend(FoundSpeaker(host = host, name = resolved.serviceName, via = "mDNS"))
            }
        }

        // ---- SSDP ----
        launch(Dispatchers.IO) {
            try {
                repeat(2) { round ->
                    val hits = Upnp.search(timeoutMs = 3000)
                    for ((host, location) in hits) {
                        val dev = try {
                            Upnp.describe(host, location)
                        } catch (e: Exception) {
                            null
                        } ?: continue
                        trySend(
                            FoundSpeaker(
                                host = host,
                                name = dev.friendlyName ?: host,
                                via = "UPnP",
                                model = listOfNotNull(dev.manufacturer, dev.modelName).joinToString(" ").ifBlank { null },
                                udn = dev.udn,
                                likelyLinkPlay = dev.looksLikeLinkPlay,
                            ),
                        )
                    }
                    if (round == 0) delay(1000)
                }
            } catch (e: Exception) {
                log("UPnP search: ${e.describe()}")
            }
        }

        launch {
            delay(durationMs)
            close()
        }

        awaitClose {
            toResolve.close()
            if (nsdStarted) runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine { cont ->
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_linkplay._tcp."
    }
}
