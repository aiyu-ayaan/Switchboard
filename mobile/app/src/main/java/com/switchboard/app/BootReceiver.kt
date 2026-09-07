package com.switchboard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.switchboard.app.data.HostStore

/**
 * Automatically restores the background connection service on device boot or update,
 * when the user has enabled "Stay Connected" and auto startup on boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val store = HostStore(context)
            if (store.alwaysOn && store.startOnBoot) {
                val last = store.lastHostId?.let { id -> store.hosts().find { it.daemonId == id } }
                    ?: store.hosts().firstOrNull()
                if (last != null) {
                    ConnectionService.start(context)
                }
            }
        }
    }
}
