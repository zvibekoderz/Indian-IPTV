package com.indian.iptv.data.repo

import com.indian.iptv.data.Channel

data class PlaylistSnapshot(
    val channels: List<Channel>,
    /** EPG programmes, when the source provides them (Xtream's xmltv.php). Best-effort. */
    val programmes: List<com.indian.iptv.data.db.ProgrammeEntity> = emptyList(),
    val etag: String? = null,
    val lastModified: String? = null,
    val notModified: Boolean = false,
)

/** What a running import has done so far, for the first-run loading screen. */
data class ImportProgress(val stage: Stage, val count: Int) {
    enum class Stage { Downloading, Live, Movies, Series, Saving }
}

interface PlaylistRepository {
    suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit = {},
    ): PlaylistSnapshot

    /** Only the EPG, for channels whose key is in [epgKeys]; null when the source has none. */
    suspend fun fetchProgrammes(epgKeys: Set<String>): List<com.indian.iptv.data.db.ProgrammeEntity>? = null
}
