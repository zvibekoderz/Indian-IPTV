package com.indian.iptv.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Short-lived foreground service used to legally open Bridge's activity from
 * the boot broadcast (Android blocks background activity starts, but an app
 * with a running foreground service is exempt).
 *
 * During early boot the package manager can briefly report Bridge as
 * uninstalled, so we use its explicit component name and retry a few times.
 * The [BridgeStartAccessibilityService] then presses Bridge's own
 * "Start Server" button; no Bridge code is modified or embedded.
 */
class BridgeLaunchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var released = false

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

        tryLaunch()
        return START_NOT_STICKY
    }

    private fun tryLaunch() {
        if (released) return
        attempts++
        val launch = Intent().apply {
            component = ComponentName(BRIDGE_PACKAGE, BRIDGE_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = runCatching { startActivity(launch) }
        if (result.isSuccess) {
            Log.i(TAG, "Opened CNCVerse Bridge UI on attempt $attempts")
            finish()
        } else if (attempts < MAX_ATTEMPTS) {
            Log.w(TAG, "Bridge UI not ready (attempt $attempts), retrying", result.exceptionOrNull())
            handler.postDelayed({ tryLaunch() }, RETRY_DELAY_MS)
        } else {
            Log.w(TAG, "Gave up opening CNCVerse Bridge after $attempts attempts")
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
                NotificationChannel(CHANNEL_ID, "Bridge auto-start", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private companion object {
        const val TAG = "BridgeLaunchService"
        const val CHANNEL_ID = "bridge_auto_start"
        const val NOTIFICATION_ID = 1002
        const val BRIDGE_PACKAGE = "com.cncverse.stremiobridge"
        const val BRIDGE_ACTIVITY = "com.cncverse.stremiobridge.android.MainActivity"
        const val MAX_ATTEMPTS = 6
        const val RETRY_DELAY_MS = 3_000L
    }
}
