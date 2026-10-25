package com.mobilerun.portal.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mobilerun.portal.R
import com.mobilerun.portal.ui.MainActivity

/**
 * Keeps Portal in the foreground while the AccessibilityService hosts a local
 * HTTP or WebSocket server. Without it, OEM app freezers (e.g. Samsung
 * Freecess) freeze Portal and block its network, so connections time out.
 */
class LocalServerForegroundService : Service() {

    companion object {
        private const val TAG = "LocalServerFgService"
        private const val CHANNEL_ID = "local_server_channel"
        private const val NOTIFICATION_ID = 3003

        fun sync(context: Context, serverRunning: Boolean) {
            val intent = Intent(context, LocalServerForegroundService::class.java)
            try {
                if (serverRunning) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not ${if (serverRunning) "start" else "stop"}: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.local_server_notification_title))
            .setContentText(getString(R.string.local_server_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        // The AccessibilityService restarts this when it starts a server again.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.local_server_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
