package com.roberrini.audiopro.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roberrini.audiopro.Connection
import com.roberrini.audiopro.Screen
import com.roberrini.audiopro.SpeakerViewModel
import com.roberrini.audiopro.UiState
import com.roberrini.audiopro.net.PhoneNetwork

private enum class Fix { WIFI_SETTINGS, VPN_SETTINGS, DIAGNOSTICS }

private data class Problem(val icon: ImageVector, val title: String, val body: String, val fix: Fix?)

/** Why the speaker can't be reached, when the phone's network explains it. Null when there's nothing to say. */
private fun problemFor(state: UiState): Problem? {
    val net = state.network ?: return null
    if (state.connection == Connection.Online) return null
    val looking = state.saved == null || state.screen == Screen.SETUP
    return when (net.kind) {
        PhoneNetwork.NONE -> Problem(
            Icons.Filled.CloudOff,
            "Your phone is offline",
            "Connect to the Wi‑Fi your speaker uses.",
            Fix.WIFI_SETTINGS,
        )
        PhoneNetwork.MOBILE_DATA -> Problem(
            Icons.Filled.SignalCellularAlt,
            "Your phone is using mobile data",
            if (net.wifiConnected) {
                "Wi‑Fi is connected, but Android isn't using it, often because that Wi‑Fi has no internet. " +
                    "The speaker can only be reached over Wi‑Fi."
            } else {
                "The speaker can only be reached over your home Wi‑Fi. Connect to the same Wi‑Fi as the speaker."
            },
            Fix.WIFI_SETTINGS,
        )
        PhoneNetwork.VPN -> Problem(
            Icons.Filled.VpnLock,
            "A VPN may be blocking the speaker",
            "Your phone's traffic goes through a VPN, which often can't reach devices at home. " +
                "Turn the VPN off, or allow local network access in its settings.",
            Fix.VPN_SETTINGS,
        )
        PhoneNetwork.WIFI, PhoneNetwork.OTHER -> {
            // On Wi‑Fi a short drop is normal, so wait a few attempts before raising it.
            val stuck = state.connection is Connection.Searching ||
                (state.connection as? Connection.Reconnecting)?.attempt?.let { it >= 3 } == true
            if (looking || !stuck) return null
            val host = state.saved?.host
            val phone = net.addresses.firstOrNull()
            val differentNetwork = host != null && phone != null &&
                host.substringBeforeLast('.') != phone.substringBeforeLast('.')
            Problem(
                Icons.Filled.WifiOff,
                "The speaker isn't answering",
                buildString {
                    append("Your phone is on Wi‑Fi but the speaker at $host doesn't respond. ")
                    if (differentNetwork) {
                        append("Your phone ($phone) may be on a different network, such as a guest Wi‑Fi. ")
                    }
                    append("Check that the speaker is on and on the same Wi‑Fi.")
                },
                Fix.DIAGNOSTICS,
            )
        }
    }
}

/** A banner across the top of the screen saying why the speaker can't be reached, with a button to fix it. */
@Composable
fun ProblemRail(state: UiState, vm: SpeakerViewModel, modifier: Modifier = Modifier) {
    val problem = problemFor(state) ?: return
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(problem.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(problem.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(problem.body, style = MaterialTheme.typography.bodyMedium)
            problem.fix?.let { fix ->
                FilledTonalButton(onClick = {
                    when (fix) {
                        Fix.WIFI_SETTINGS -> context.startActivity(
                            Intent(
                                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS,
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        Fix.VPN_SETTINGS -> context.startActivity(
                            Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        Fix.DIAGNOSTICS -> vm.show(Screen.DIAGNOSTICS)
                    }
                }) {
                    Text(
                        when (fix) {
                            Fix.WIFI_SETTINGS -> "Wi‑Fi settings"
                            Fix.VPN_SETTINGS -> "VPN settings"
                            Fix.DIAGNOSTICS -> "Open diagnostics"
                        },
                    )
                }
            }
        }
    }
}
