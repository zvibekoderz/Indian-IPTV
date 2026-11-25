package com.indian.iptv.addonserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** Keeps the local Stremio server alive and shows its LAN URL. */
class ServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        val local = server(this)
        if (!local.isRunning) {
            runCatching { local.start() }
                .onFailure { Log.w(TAG, "Could not start server", it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _server?.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Add-on server", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CNC Add-on Server running")
            .setContentText(serverUrl(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AddonServerService"
        private const val CHANNEL_ID = "addon_server"
        private const val NOTIFICATION_ID = 1004

        @Volatile private var _server: StremioServer? = null

        internal fun server(context: Context): StremioServer = _server ?: synchronized(this) {
            _server ?: StremioServer(context.applicationContext).also { _server = it }
        }

        fun serverUrl(context: Context): String =
            "http://${lanAddress(context)}:${StremioServer.PORT}/manifest.json"

        fun localUrl(): String = "http://127.0.0.1:${StremioServer.PORT}/manifest.json"

        private fun lanAddress(context: Context): String = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }
}
