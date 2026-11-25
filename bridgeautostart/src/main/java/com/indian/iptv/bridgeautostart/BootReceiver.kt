package com.indian.iptv.bridgeautostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Starts the short-lived launch service when the TV boots. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!Prefs.enabled(context) || !Bridge.isInstalled(context)) return

        Log.i(TAG, "Boot received ($action); starting Bridge via launch service")
        val service = Intent(context, LaunchService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start launch service", e)
        }
    }

    private companion object {
        const val TAG = "BridgeBootReceiver"
    }
}
