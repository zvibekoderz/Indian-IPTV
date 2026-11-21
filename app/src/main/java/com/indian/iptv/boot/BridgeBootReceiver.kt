package com.indian.iptv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.indian.iptv.IptvApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Best-effort auto-launch of CNCVerse Bridge after the TV boots.
 *
 * Bridge's own licence forbids modifying its codebase, and its shipped manifest
 * has no boot receiver (its foreground service is also not exported), so it
 * cannot start itself and cannot be started directly by another app. The most
 * we can do licence-safely is open Bridge's UI from our own boot receiver; the
 * server is then started with the single "Start" tap inside Bridge.
 */
class BridgeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = runCatching {
                    IptvApp.get().settings.bridgeAutoLaunch.first()
                }.getOrDefault(true)
                if (enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, BridgeLaunchService::class.java),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not auto-launch Bridge", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BridgeBootReceiver"
    }
}
