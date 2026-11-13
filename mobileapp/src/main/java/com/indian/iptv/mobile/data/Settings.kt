package com.indian.iptv.mobile.data

import android.content.Context

/**
 * Tiny SharedPreferences-backed settings store. Deliberately framework-free:
 * no DataStore, no DI — the mobile app keeps its own simple state.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("iptv_mobile", Context.MODE_PRIVATE)

    init {
        // One-time migration: scale mode was persisted during the era of the
        // cycle chip, which could silently leave Zoom (cropped picture)
        // selected. Reset to Fit exactly once after this update.
        if (!prefs.getBoolean(KEY_SCALE_RESET, false)) {
            prefs.edit()
                .putBoolean(KEY_SCALE_RESET, true)
                .putInt(KEY_SCALE_MODE, 0)
                .apply()
        }
    }

    /** Playlist sources, newest first in the UI. One URL per entry. */
    var m3uUrls: List<String>
        get() = prefs.getString(KEY_URLS, null)
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(DEFAULT_URL)
        set(value) {
            prefs.edit()
                .putString(KEY_URLS, value.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n"))
                .apply()
        }

    /** Favourited channel stream URLs. */
    var favourites: Set<String>
        get() = prefs.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_FAVOURITES, value).apply()
        }

    /** Preferred player scale mode: 0 = Fit, 1 = Fill, 2 = Zoom. */
    var scaleMode: Int
        get() = prefs.getInt(KEY_SCALE_MODE, 0).coerceIn(0, 2)
        set(value) {
            prefs.edit().putInt(KEY_SCALE_MODE, value.coerceIn(0, 2)).apply()
        }

    /** Optional XMLTV guide URL for Now/Next programme info (blank = off). */
    var epgUrl: String
        get() = prefs.getString(KEY_EPG_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_EPG_URL, value.trim()).apply()
        }

    fun resetUrls() {
        prefs.edit().remove(KEY_URLS).apply()
    }

    companion object {
        const val DEFAULT_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"
        private const val KEY_URLS = "m3u_urls"
        private const val KEY_FAVOURITES = "favourite_urls"
        private const val KEY_SCALE_MODE = "scale_mode"
        private const val KEY_SCALE_RESET = "scale_mode_reset_v2"
        private const val KEY_EPG_URL = "epg_url"
    }
}
