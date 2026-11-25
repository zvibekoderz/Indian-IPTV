package com.indian.iptv.bridgeautostart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Opens Bridge's own activity from the boot broadcast (Android allows activity
 * starts from a foreground service). The activity is launched with no animation
 * and the accessibility service immediately presses Bridge's own Start Server
 * button and returns home, so no user interaction is needed.
 */
class LaunchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var released = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        tryLaunch()
        return START_NOT_STICKY
    }

    private fun tryLaunch() {
        if (released) return
        attempts++
        val result = runCatching { startActivity(Bridge.launchIntent()) }
        if (result.isSuccess) {
            Log.i(TAG, "Opened Bridge UI on attempt $attempts")
            finish()
        } else if (attempts < MAX_ATTEMPTS) {
            Log.w(TAG, "Bridge not ready (attempt $attempts), retrying", result.exceptionOrNull())
            handler.postDelayed({ tryLaunch() }, RETRY_DELAY_MS)
        } else {
            Log.w(TAG, "Gave up opening Bridge after $attempts attempts", result.exceptionOrNull())
            finish()
        }
    }

    private fun finish() {
        if (released) return
        released = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        released = true
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bridge auto start", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Starting CNCVerse Bridge")
            .setContentText("Server start in the background")
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private companion object {
        const val TAG = "BridgeLaunchService"
        const val CHANNEL_ID = "bridge_auto_start"
        const val NOTIFICATION_ID = 1003
        const val MAX_ATTEMPTS = 8
        const val RETRY_DELAY_MS = 3_000L
    }
}
