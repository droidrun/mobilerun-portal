package com.mobilerun.portal.service

import android.app.Notification
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
        private const val ACTION_STOP = "com.mobilerun.portal.action.LOCAL_SERVER_FOREGROUND_STOP"

        // Main thread only.
        private var requested = false

        /** Call on the main thread. */
        fun sync(context: Context, serverRunning: Boolean) {
            if (serverRunning == requested) return
            val intent = Intent(context, LocalServerForegroundService::class.java)
            if (serverRunning) {
                try {
                    context.startForegroundService(intent)
                    requested = true
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Start refused: ${e.message}")
                }
                return
            }
            requested = false
            // stopService() crashes the app if it lands before startForeground(); a stop
            // start is ordered after any pending start.
            val stopIntent = Intent(ACTION_STOP, null, context, LocalServerForegroundService::class.java)
            try {
                context.startForegroundService(stopIntent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Stop start refused, using stopService: ${e.message}")
                context.stopService(intent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (intent?.action == ACTION_STOP) stopSelf(startId)
        // The AccessibilityService restarts this when it starts a server again.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.local_server_notification_title))
            .setContentText(getString(R.string.local_server_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.local_server_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
