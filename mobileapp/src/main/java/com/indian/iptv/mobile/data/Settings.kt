package com.indian.iptv.mobile.data

import android.content.Context

/**
 * Tiny SharedPreferences-backed settings store. Deliberately framework-free:
 * no DataStore, no DI — the mobile app keeps its own simple state.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("iptv_mobile", Context.MODE_PRIVATE)

    var m3uUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    fun resetUrl() {
        prefs.edit().remove(KEY_URL).apply()
    }

    companion object {
        const val DEFAULT_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"
        private const val KEY_URL = "m3u_url"
    }
}
