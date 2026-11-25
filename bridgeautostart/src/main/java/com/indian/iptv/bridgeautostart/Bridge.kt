package com.indian.iptv.bridgeautostart

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Public Android-level facts about the separately installed Bridge app. */
internal object Bridge {
    const val PACKAGE = "com.cncverse.stremiobridge"
    const val ACTIVITY = "com.cncverse.stremiobridge.android.MainActivity"

    fun isInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun launchIntent(): Intent = Intent().apply {
        component = ComponentName(PACKAGE, ACTIVITY)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
