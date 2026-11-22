package com.indian.iptv.data.addon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioAddonTest {

    @Test
    fun normalizeAcceptsManifestRootAndBareHost() {
        assertEquals("http://127.0.0.1:8080", StremioAddon.normalize("http://127.0.0.1:8080/manifest.json"))
        assertEquals("http://127.0.0.1:8080", StremioAddon.normalize("  http://127.0.0.1:8080/  "))
        assertEquals("http://192.168.1.10:8080", StremioAddon.normalize("192.168.1.10:8080"))
        assertEquals("https://bridge.example.com", StremioAddon.normalize("https://bridge.example.com/MANIFEST.JSON"))
    }

    @Test
    fun normalizeRejectsBlankAndNonHttp() {
        assertNull(StremioAddon.normalize(null))
        assertNull(StremioAddon.normalize("   "))
        assertNull(StremioAddon.normalize("ftp://example.com/manifest.json"))
    }

    @Test
    fun parseManifestReadsCatalogsAndSkipsMalformed() {
        val json = JSONObject(
            """{
                "id":"org.cncverse.bridge","name":"CNC Bridge",
                "catalogs":[
                    {"type":"movie","id":"cnc.movies","name":"Movies"},
                    {"type":"series","id":"cnc.series"},
                    {"type":"movie"},
                    "not-an-object"
                ]
            }""".trimIndent(),
        )
        val manifest = StremioAddon.parseManifest(json)
        assertEquals("CNC Bridge", manifest.name)
        assertEquals(2, manifest.catalogs.size)
        assertEquals("cnc.movies", manifest.catalogs[0].id)
        assertEquals("Movies", manifest.catalogs[0].name)
        assertEquals("cnc.series", manifest.catalogs[1].name) // falls back to id
    }

    @Test
    fun parseCatalogFallsBackToRequestedTypeAndIgnoresRelativeArt() {
        val json = JSONObject(
            """{"metas":[
                {"id":"tt123","name":"A Film","poster":"/local.png","background":"https://img/b.jpg","releaseInfo":"2025"},
                {"id":"tt456"}
            ]}""".trimIndent(),
        )
        val metas = StremioAddon.parseCatalog(json, "movie")
        assertEquals(2, metas.size)
        assertEquals("movie", metas[0].type)
        assertNull(metas[0].poster)
        assertEquals("https://img/b.jpg", metas[0].background)
        assertEquals("tt456", metas[1].name)
    }

    @Test
    fun parseStreamsKeepsOnlyDirectHttpUrlsAndReadsHeaders() {
        val json = JSONObject(
            """{"streams":[
                {"url":"https://cdn.example/a.m3u8","name":"1080p",
                 "behaviorHints":{"headers":{"User-Agent":"TestAgent"}},"headers":{"Referer":"https://site"}},
                {"infoHash":"abcdef","sources":[]},
                {"ytId":"dQw4w9WgXcQ"},
                {"url":"https://cdn.example/a.m3u8","name":"duplicate"},
                {"url":"ftp://cdn.example/b.mp4"}
            ]}""".trimIndent(),
        )
        val streams = StremioAddon.parseStreams(json)
        assertEquals(1, streams.size)
        assertEquals("https://cdn.example/a.m3u8", streams[0].url)
        assertEquals("1080p", streams[0].label)
        assertEquals("TestAgent", streams[0].headers["User-Agent"])
        assertEquals("https://site", streams[0].headers["Referer"])
        assertTrue(streams[0].headers.size == 2)
    }
}
