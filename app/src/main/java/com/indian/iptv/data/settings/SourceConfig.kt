package com.indian.iptv.data.settings

sealed interface SourceConfig {
    /**
     * One or more M3U playlists. Channels from every playlist are merged into a single
     * catalogue; on an id clash between playlists the later one gets an `@s<index>` suffix,
     * so the first playlist keeps exactly the ids it had before multi-playlist support.
     */
    data class M3u(val urls: List<String>) : SourceConfig {
        /** Primary (first) playlist — used where a single URL is needed. */
        val url: String get() = urls.firstOrNull().orEmpty()
    }

    data class Xtream(val host: String, val username: String, val password: String) : SourceConfig
}