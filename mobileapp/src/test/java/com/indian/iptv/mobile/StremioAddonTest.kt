package com.indian.iptv.mobile

import com.indian.iptv.mobile.data.StremioAddon
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StremioAddonTest {

    @Test
    fun normalizeHandlesBridgeStyleUrls() {
        assertEquals("http://127.0.0.1:8080", StremioAddon.normalize("127.0.0.1:8080/manifest.json"))
        assertEquals("https://bridge.example.com", StremioAddon.normalize("https://bridge.example.com/"))
        assertNull(StremioAddon.normalize("ftp://example.com"))
        assertNull(StremioAddon.normalize(""))
    }

    @Test
    fun parseStreamsFiltersUnplayableEntries() {
        val json = JSONObject(
            """{"streams":[
                {"url":"http://192.168.1.5:8080/stream/1.m3u8","title":"720p",
                 "headers":{"Referer":"http://192.168.1.5"}},
                {"infoHash":"1234"},
                {"ytId":"abc"},
                {"url":"http://192.168.1.5:8080/stream/1.m3u8"}
            ]}""".trimIndent(),
        )
        val streams = StremioAddon.parseStreams(json)
        assertEquals(1, streams.size)
        assertEquals("720p", streams[0].label)
        assertEquals("http://192.168.1.5", streams[0].headers["Referer"])
    }

    @Test
    fun parseManifestAndCatalog() {
        val manifest = StremioAddon.parseManifest(
            JSONObject("""{"name":"Bridge","catalogs":[{"type":"movie","id":"all"}]}"""),
        )
        assertEquals("Bridge", manifest.name)
        assertEquals("all", manifest.catalogs.single().id)

        val metas = StremioAddon.parseCatalog(
            JSONObject("""{"metas":[{"id":"x1","name":"Title"}]}"""),
            "movie",
        )
        assertEquals("Title", metas.single().name)
        assertEquals("movie", metas.single().type)
        assertNull(metas.single().poster)
    }
}
