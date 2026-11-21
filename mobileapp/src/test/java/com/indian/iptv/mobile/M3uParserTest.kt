package com.indian.iptv.mobile

import com.indian.iptv.mobile.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses attributes names urls and options`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="StarPlus.in" tvg-logo="http://logo/star.png" group-title="Entertainment",Star Plus
            #EXTVLCOPT:http-user-agent=MyPlayer/1.0
            #EXTVLCOPT:referrer=http://example.com/
            http://stream.example.com/star.m3u8
            #EXTINF:-1 tvg-logo="http://logo/zee.png" group-title="News",Zee News, Hindi
            http://stream.example.com/zee.m3u8

        """.trimIndent()

        val channels = M3uParser.parse(text)

        assertEquals(2, channels.size)

        val star = channels[0]
        assertEquals("Star Plus", star.name)
        assertEquals("http://stream.example.com/star.m3u8", star.url)
        assertEquals("StarPlus.in", star.tvgId)
        assertEquals("http://logo/star.png", star.logo)
        assertEquals("Entertainment", star.category)
        assertEquals("MyPlayer/1.0", star.userAgent)
        assertEquals("http://example.com/", star.referrer)

        // Commas inside the display name must be preserved.
        val zee = channels[1]
        assertEquals("Zee News, Hindi", zee.name)
        assertNull(zee.userAgent)
    }

    @Test
    fun `skips comment lines and entries without urls`() {
        val text = """
            #EXTM3U
            # A plain comment
            #EXTINF:-1 group-title="Sports",Broken entry
            #EXTINF:-1,Kim TV
            https://ok.example.com/live.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(text)

        // "Broken entry" has no URL after it, so only "Kim TV" is emitted —
        // and since each #EXTINF starts a fresh entry, Kim TV has no category.
        assertEquals(1, channels.size)
        assertEquals("Kim TV", channels[0].name)
        assertNull(channels[0].category)
    }
}
