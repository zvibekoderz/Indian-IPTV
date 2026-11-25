package com.indian.iptv.bridgeautostart

import android.content.Context

/** Tiny settings store for this standalone app. */
internal object Prefs {
    private const val NAME = "bridge_auto_start"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
