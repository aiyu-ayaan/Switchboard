package com.aiyu.switchboard.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.aiyu.switchboard.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ACTION = "com.aiyu.switchboard.QUICK"
private const val KIND = "kind"

/** A home-screen strip with mute, play/pause and lock for the computer last used. */
class SwitchboardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_switchboard)
            views.setOnClickPendingIntent(R.id.widget_mute, press(context, Quick.Mute))
            views.setOnClickPendingIntent(R.id.widget_play, press(context, Quick.PlayPause))
            views.setOnClickPendingIntent(R.id.widget_lock, press(context, Quick.Lock))
            manager.updateAppWidget(id, views)
        }
    }

    private fun press(context: Context, kind: Quick): PendingIntent {
        val i = Intent(context, QuickReceiver::class.java).setAction(ACTION).putExtra(KIND, kind.name)
        return PendingIntent.getBroadcast(context, kind.ordinal, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Receives a widget press; keeps the process alive just long enough to finish the action. */
class QuickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val kind = runCatching { Quick.valueOf(intent.getStringExtra(KIND).orEmpty()) }.getOrNull() ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Toast.makeText(context, QuickActions.run(context, kind), Toast.LENGTH_SHORT).show()
            } finally {
                pending.finish()
            }
        }
    }
}
