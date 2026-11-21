package com.indian.iptv.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Short-lived foreground service used only to legally launch Bridge's activity
 * from the boot broadcast (Android blocks background activity starts, but an
 * app with a running foreground service is exempt). It stops itself immediately
 * afterwards — nothing is left running in the background.
 */
class BridgeLaunchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Starting CNCVerse Bridge")
            .setContentText("Opening Bridge after TV boot")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build())

        runCatching {
            packageManager.getLaunchIntentForPackage(BRIDGE_PACKAGE)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            } ?: Log.w(TAG, "CNCVerse Bridge is not installed; skipping auto-launch")
        }.onFailure { Log.w(TAG, "Could not open CNCVerse Bridge", it) }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bridge auto-start", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private companion object {
        const val TAG = "BridgeLaunchService"
        const val CHANNEL_ID = "bridge_auto_start"
        const val NOTIFICATION_ID = 1002
        const val BRIDGE_PACKAGE = "com.cncverse.stremiobridge"
    }
}
