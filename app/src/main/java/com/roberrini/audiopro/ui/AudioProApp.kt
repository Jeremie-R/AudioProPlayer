package com.roberrini.audiopro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roberrini.audiopro.Screen
import com.roberrini.audiopro.SpeakerViewModel
import com.roberrini.audiopro.UiState

@Composable
fun AudioProApp(vm: SpeakerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.messageShown()
    }

    // Back from setup/diagnostics returns to the player when a speaker is chosen.
    BackHandler(enabled = state.screen != Screen.PLAYER && state.saved != null) {
        vm.show(Screen.PLAYER)
    }

    val snackbarHost = @Composable { SnackbarHost(snackbar) }

    when (state.screen) {
        Screen.SETUP -> SetupScreen(state, vm, snackbarHost)
        Screen.PLAYER -> PlayerScreen(state, vm, snackbarHost)
        Screen.RADIO -> RadioScreen(state, vm, snackbarHost)
        Screen.DIAGNOSTICS -> DiagnosticsScreen(state, vm, snackbarHost)
    }
}

@Composable
fun MainTabs(state: UiState, vm: SpeakerViewModel) {
    NavigationBar {
        NavigationBarItem(
            selected = state.screen == Screen.PLAYER,
            onClick = { vm.show(Screen.PLAYER) },
            icon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
            label = { Text("Player") },
        )
        NavigationBarItem(
            selected = state.screen == Screen.RADIO,
            onClick = { vm.show(Screen.RADIO) },
            icon = { Icon(Icons.Filled.Radio, contentDescription = null) },
            label = { Text("Radio") },
        )
    }
}
