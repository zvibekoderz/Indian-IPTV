package com.indian.iptv.mobile.data

import android.content.Context

/**
 * "Continue watching" store: the last channels played, newest first.
 * Encoded as one line per entry with unit separators — deliberately
 * framework-free (no JSON/Room), like the rest of the mobile app's storage.
 */
class RecentlyWatched(context: Context) {

    private val prefs = context.getSharedPreferences("iptv_mobile", Context.MODE_PRIVATE)

    fun all(): List<Channel> =
        prefs.getString(KEY_RECENT, null)
            ?.split('\n')
            ?.mapNotNull(::decode)
            .orEmpty()

    /** Adds [channel] as the most recent, keeping the rest in order. */
    fun record(channel: Channel) {
        val others = all().filterNot { it.url == channel.url }
        val entries = (listOf(channel) + others).take(MAX_ENTRIES)
        prefs.edit()
            .putString(KEY_RECENT, entries.joinToString("\n") { encode(it) })
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_RECENT).apply()
    }

    private fun encode(c: Channel): String = listOf(
        c.url,
        c.name,
        c.logo.orEmpty(),
        c.category.orEmpty(),
        c.tvgId.orEmpty(),
        c.userAgent.orEmpty(),
        c.referrer.orEmpty(),
    ).joinToString(SEPARATOR)

    private fun decode(line: String): Channel? {
        val f = line.split(SEPARATOR)
        if (f.size < 7 || f[0].isBlank() || f[1].isBlank()) return null
        return Channel(
            url = f[0],
            name = f[1],
            logo = f[2].ifEmpty { null },
            category = f[3].ifEmpty { null },
            tvgId = f[4].ifEmpty { null },
            userAgent = f[5].ifEmpty { null },
            referrer = f[6].ifEmpty { null },
        )
    }

    companion object {
        private const val KEY_RECENT = "recent_channels"
        private const val SEPARATOR = ""
        private const val MAX_ENTRIES = 12
    }
}
