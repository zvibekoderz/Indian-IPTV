package com.indian.iptv.addonserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Starts the local add-on server after boot so its HTTP link is always available. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        if (!Prefs.autoStart(context)) return
        val service = Intent(context, ServerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service)
            else context.startService(service)
        } catch (_: Exception) {
            // Boot restrictions may briefly block this; the setup screen can start it manually.
        }
    }
}
