package com.aiyu.switchboard.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aiyu.switchboard.App
import com.aiyu.switchboard.MainActivity
import com.aiyu.switchboard.ui.system.sizeText

/** Tells the user a computer wants to send a file, with Save and Decline on the notification. */
object OfferNotifier {
    private const val CHANNEL = "offers"
    const val SAVE = "com.aiyu.switchboard.OFFER_SAVE"
    const val DECLINE = "com.aiyu.switchboard.OFFER_DECLINE"
    private const val HOST = "host"
    private const val XFER = "xfer"

    private fun id(xfer: String) = xfer.hashCode()

    fun post(context: Context, hostId: String, hostName: String, offer: Event.FileOffer) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Incoming files", NotificationManager.IMPORTANCE_HIGH))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun action(what: String) = PendingIntent.getBroadcast(
            context,
            id(offer.xfer) + what.hashCode(),
            Intent(context, OfferReceiver::class.java).setAction(what).putExtra(HOST, hostId).putExtra(XFER, offer.xfer),
            flags,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("$hostName wants to send a file")
            .setContentText("${offer.name} · ${sizeText(offer.size)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags))
            .addAction(0, "Save", action(SAVE))
            .addAction(0, "Decline", action(DECLINE))
            .build()
        nm.notify(id(offer.xfer), n)
    }

    fun cancel(context: Context, xfer: String) = context.getSystemService(NotificationManager::class.java).cancel(id(xfer))

    internal fun targets(intent: Intent): Pair<String, String>? {
        val host = intent.getStringExtra(HOST) ?: return null
        return host to (intent.getStringExtra(XFER) ?: return null)
    }
}

class OfferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (hostId, xfer) = OfferNotifier.targets(intent) ?: return
        val connections = (context.applicationContext as App).connections
        val conn = connections.live.value[hostId] ?: return OfferNotifier.cancel(context, xfer)
        val offer = conn.offers.value.find { it.xfer == xfer } ?: return OfferNotifier.cancel(context, xfer)
        when (intent.action) {
            OfferNotifier.SAVE -> connections.transfers.download(conn, hostId, offer)
            OfferNotifier.DECLINE -> connections.transfers.decline(conn, offer)
        }
    }
}
