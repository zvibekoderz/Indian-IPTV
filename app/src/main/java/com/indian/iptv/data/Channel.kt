package com.indian.iptv.data

enum class ContentType { TV, MOVIE, SERIES }

/**
 * A channel / VOD item from the playlist. Extended with language, genre, channel number
 * and HD flag for fast navigation and filtering on Android TV.
 */
data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val streamUrl: String?,
    val epgChannelId: String?,
    val type: ContentType,
    /** Days of catch-up archive the provider keeps for this live channel; 0 = none. */
    val archiveDays: Int = 0,
    /** ISO 639-1/2/3 language codes (e.g. "hi", "ta", "te"). Empty when unknown. */
    val languages: List<String> = emptyList(),
    /** Normalised genre tokens (News, Entertainment, Movies, Sports, Kids, etc.). */
    val genres: List<String> = emptyList(),
    /** Stable 1-based channel number within the TV playlist order. Null for non-TV. */
    val number: Int? = null,
    /** True when the stream or name indicates HD/720p/1080p/4K. */
    val isHd: Boolean = false,
)