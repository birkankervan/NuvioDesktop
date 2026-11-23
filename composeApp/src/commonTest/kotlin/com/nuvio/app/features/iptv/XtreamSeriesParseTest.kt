package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamSeriesParseTest {
    private val account = IptvAccount("http://example.test", "u", "p")
    private fun info(s: String) = parseSeriesInfo(Json.parseToJsonElement(s), account)

    @Test
    fun objectShapedEpisodes() {
        val r = info(
            """{"info":{"name":"Show","cover":"http://x/c.jpg"},"episodes":{
            "2":[{"id":"20","episode_num":1,"title":"B1","container_extension":"mkv","info":{"duration_secs":90,"plot":"p"}}],
            "1":[{"id":"12","episode_num":2,"title":"A2"},{"id":"11","episode_num":"1","title":"A1"}]}}""",
        )
        assertEquals("Show", r.series?.name)
        assertEquals(listOf(1, 2), r.seasons)
        assertEquals(listOf("11", "12"), r.episodesBySeason[1]!!.map { it.id })
        assertEquals(90, r.episodesBySeason[2]!!.single().durationSecs)
    }

    @Test
    fun arrayOfArraysShape() {
        val r = info("""{"episodes":[[{"id":"1","episode_num":1,"title":"a"}],[{"id":"2","episode_num":1,"title":"b"}]]}""")
        assertEquals(listOf(1, 2), r.seasons)
        assertEquals("2", r.episodesBySeason[2]!!.single().id)
    }

    @Test
    fun emptyArrayShape() {
        val r = info("""{"info":[],"episodes":[]}""")
        assertTrue(r.seasons.isEmpty())
        assertTrue(r.episodesBySeason.isEmpty())
        assertNull(r.series)
    }

    @Test
    fun durationHhMmSs() {
        val r = info("""{"episodes":{"1":[{"id":"1","title":"t","info":{"duration":"01:02:03"}}]}}""")
        assertEquals(3723, r.episodesBySeason[1]!!.single().durationSecs)
    }

    @Test
    fun defaultExtensionAndUrlFormat() {
        val r = info("""{"episodes":{"1":[{"id":"77","title":"t"}]}}""")
        assertEquals("http://example.test/series/u/p/77.mp4", r.episodesBySeason[1]!!.single().url)
    }

    @Test
    fun seriesListDropsBadCoverAndDuplicates() {
        val l = parseSeriesList(
            Json.parseToJsonElement("""[{"series_id":1,"name":"A","cover":"nope"},{"series_id":1,"name":"dup"},{"name":"noid"}]"""),
            "9",
        )
        assertEquals(1, l.size)
        assertNull(l[0].cover)
        assertEquals("9", l[0].categoryId)
    }

    @Test
    fun vodInfo() {
        fun vod(s: String) = parseVodInfo(Json.parseToJsonElement(s))
        assertEquals(IptvVodInfo("A plot", "7.4"), vod("""{"info":{"plot":"A plot","rating":"7.4"}}"""))
        assertEquals("Desc", vod("""{"info":{"description":"Desc","rating":8}}""").plot)
        assertEquals("8", vod("""{"info":{"rating":8}}""").rating)
        assertEquals(IptvVodInfo(null, null), vod("""{"info":{"plot":"null","rating":"0.0"}}"""))
        assertEquals(IptvVodInfo(null, null), vod("[]"))
    }
}
