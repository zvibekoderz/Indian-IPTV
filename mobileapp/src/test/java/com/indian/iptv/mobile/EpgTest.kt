package com.indian.iptv.mobile

import com.indian.iptv.mobile.data.Epg
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.TimeZone

class EpgTest {

    private fun utcMillis(value: String): Long {
        val format = SimpleDateFormat("yyyyMMddHHmmss").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return format.parse(value)!!.time
    }

    @Test
    fun `parses now and next per channel from xmltv`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="StarPlus.in">
                <display-name>Star Plus</display-name>
                <icon src="https://example.com/logo.png"/>
              </channel>
              <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="StarPlus.in"><title>Show A</title></programme>
              <programme start="20260101130000 +0000" stop="20260101140000 +0000" channel="StarPlus.in"><title>Show B</title></programme>
              <programme start="20260101140000 +0000" stop="20260101150000 +0000" channel="StarPlus.in"><title>Show C</title></programme>
              <programme start="20260101100000 +0000" stop="20260101120000 +0000" channel="StarPlus.in"><title>Already Over</title></programme>
            </tv>
        """.trimIndent()

        val now = utcMillis("20260101125000") // inside "Show A"
        val map = Epg.parse(ByteArrayInputStream(xml.toByteArray()), now)

        val nowNext = map["starplus.in"]!!
        assertEquals("Show A", nowNext.now)
        assertEquals("Show B", nowNext.next)

        // Also indexed by normalized display name for tvg-id-less playlists.
        assertEquals(nowNext, map[Epg.normalizeName("Star Plus")])
    }

    @Test
    fun `lookup prefers tvg-id then falls back to name`() {
        val xml = """
            <tv>
              <channel id="AajTak.in"><display-name>Aaj Tak HD</display-name></channel>
              <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="AajTak.in"><title>News</title></programme>
            </tv>
        """.trimIndent()
        val now = utcMillis("20260101125000")
        val map = Epg.parse(ByteArrayInputStream(xml.toByteArray()), now)

        assertEquals("News", Epg.lookup(map, "AajTak.in", "Aaj Tak")!!.now)
        assertEquals("News", Epg.lookup(map, null, "Aaj Tak HD")!!.now)
        assertEquals(null, Epg.lookup(map, null, "Totally Different Channel"))
    }
}
