package com.aiyu.switchboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiyu.switchboard.data.PairSecret
import com.aiyu.switchboard.data.PairTarget
import com.aiyu.switchboard.net.PairState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val connections = (app as App).connections
    val vault = connections.vault

    /** The pairing on screen, if any. */
    data class Pairing(val target: PairTarget, val state: StateFlow<PairState>)

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing = _pairing.asStateFlow()

    fun pair(target: PairTarget, secret: PairSecret) {
        _pairing.value = Pairing(target, connections.pair(target, secret))
    }

    /** The computer chosen for typed-code pairing. */
    val codeTarget = MutableStateFlow<PairTarget?>(null)

    fun clearPairing() {
        _pairing.value = null
    }

    fun forget(id: String) = viewModelScope.launch { connections.forget(id) }

    fun select(id: String) = connections.select(id)

    fun setDynamicColor(on: Boolean) = vault.update { it.copy(dynamicColor = on) }

    fun setTheme(mode: String) = vault.update { it.copy(theme = mode) }
}
