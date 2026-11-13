package com.roberrini.audiopro.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities

enum class PhoneNetwork { WIFI, MOBILE_DATA, VPN, NONE, OTHER }

/** Which network the phone sends the app's traffic over. The speaker is only reachable on the home Wi‑Fi. */
data class NetworkInfo(
    val kind: PhoneNetwork,
    /** A Wi‑Fi network is connected, even if Android isn't using it (for example because it has no internet). */
    val wifiConnected: Boolean,
    val addresses: List<String>,
) {
    fun describe(): String {
        val name = when (kind) {
            PhoneNetwork.WIFI -> "Wi‑Fi"
            PhoneNetwork.MOBILE_DATA -> if (wifiConnected) "mobile data (Wi‑Fi connected but not used)" else "mobile data"
            PhoneNetwork.VPN -> "VPN"
            PhoneNetwork.NONE -> "no network"
            PhoneNetwork.OTHER -> "other"
        }
        return if (addresses.isEmpty()) name else "$name, phone address ${addresses.joinToString(", ")}"
    }
}

/** Follows the phone's default network and reports every change. */
class NetworkMonitor(context: Context, private val onChange: (NetworkInfo) -> Unit) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(current())
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = onChange(current())
        override fun onLost(network: Network) = onChange(current())
    }

    fun start() {
        onChange(current())
        cm.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    fun current(): NetworkInfo {
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        @Suppress("DEPRECATION")
        val wifiConnected = cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val kind = when {
            caps == null -> PhoneNetwork.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> PhoneNetwork.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> PhoneNetwork.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> PhoneNetwork.MOBILE_DATA
            else -> PhoneNetwork.OTHER
        }
        val addresses = active?.let { cm.getLinkProperties(it) }?.linkAddresses.orEmpty()
            .mapNotNull { it.address.hostAddress }
            .filterNot { ':' in it }
        return NetworkInfo(kind, wifiConnected, addresses)
    }
}
