package com.aiyu.switchboard

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aiyu.switchboard.data.Vault
import com.aiyu.switchboard.net.Connections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var connections: Connections
        private set

    override fun onCreate() {
        super.onCreate()
        connections = Connections(this, Vault(this))
        // Connected while the app is on screen; let go 20 s after it leaves, so
        // a quick app switch does not drop every desktop.
        val scope = CoroutineScope(Dispatchers.Main)
        var stopper: Job? = null
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                stopper?.cancel()
                connections.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                stopper = scope.launch {
                    delay(20_000)
                    // Files still moving need the link; the collector below lets go when they finish.
                    if (!connections.transfers.busy.value) connections.stop()
                }
            }
        })
        scope.launch {
            connections.transfers.busy.collect { busy ->
                if (!busy && !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) connections.stop()
            }
        }
    }
}
