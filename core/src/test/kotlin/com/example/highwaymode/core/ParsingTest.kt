package com.example.highwaymode.core

import com.example.highwaymode.core.facility.FacilityNameParser
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.graph.RoadAttr
import com.example.highwaymode.core.json.JsonReader
import com.example.highwaymode.core.osm.OverpassException
import com.example.highwaymode.core.osm.OverpassParser
import com.example.highwaymode.core.osm.OverpassQuery
import com.example.highwaymode.core.osm.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParsingTest {

    @Test
    fun jsonReaderHandlesNestingEscapesAndNumbers() {
        val r = JsonReader("""{"a":[1,-2.5e3,{"b":"x\"yあ"}],"c":true,"d":null,"e":{}, "f":[]}""")
        r.beginObject()
        assertEquals("a", r.nextName())
        r.beginArray()
        assertEquals(1L, r.nextLong())
        assertEquals(-2500.0, r.nextDouble(), 1e-9)
        r.beginObject()
        assertEquals("b", r.nextName())
        assertEquals("x\"yあ", r.nextString())
        r.endObject()
        r.endArray()
        assertEquals("c", r.nextName())
        assertTrue(r.nextBoolean())
        assertEquals("d", r.nextName())
        r.nextNull()
        assertEquals("e", r.nextName())
        r.skipValue()
        assertEquals("f", r.nextName())
        r.beginArray()
        assertFalse(r.hasNext())
        r.endArray()
        r.endObject()
        assertEquals(JsonReader.Token.END_DOCUMENT, r.peek())
    }

    @Test
    fun overpassParserClassifiesElements() {
        val json = """
        {
          "version": 0.6, "generator": "Overpass API", "osm3s": {"timestamp_osm_base": "x"},
          "elements": [
            {"type":"way","id":10,"nodes":[1,2,3],"geometry":[{"lat":36.0,"lon":138.0},{"lat":36.001,"lon":138.0},{"lat":36.002,"lon":138.0}],
             "tags":{"highway":"motorway","name":"長野自動車道","ref":"E19","oneway":"yes","maxspeed":"100"}},
            {"type":"node","id":2,"lat":36.001,"lon":138.0,"tags":{"highway":"motorway_junction","name":"岡谷IC","ref":"16"}},
            {"type":"node","id":3,"lat":36.002,"lon":138.0,"tags":{"barrier":"toll_booth","payment:cash":"no"}},
            {"type":"way","id":20,"nodes":[5,6,7,8,5],"geometry":[{"lat":36.0,"lon":138.001},{"lat":36.0,"lon":138.002},{"lat":36.001,"lon":138.002},{"lat":36.001,"lon":138.001},{"lat":36.0,"lon":138.001}],
             "tags":{"highway":"rest_area","name":"辰野PA（下り線）"}},
            {"type":"way","id":30,"center":{"lat":36.0005,"lon":138.0015},"tags":{"amenity":"toilets"}},
            {"type":"node","id":40,"lat":36.0005,"lon":138.0016,"tags":{"shop":"convenience","brand":"ローソン"}}
          ]
        }
        """.trimIndent()
        val d = OverpassParser.parse(json)
        assertEquals(1, d.roads.size)
        assertEquals(1, d.junctions.size)
        assertEquals(1, d.tollBooths.size)
        assertEquals(1, d.restAreas.size)
        assertEquals(2, d.pois.size)
        assertTrue(d.restAreas[0].contains(36.0005, 138.0015))
        assertFalse(d.restAreas[0].contains(36.0005, 138.003))
    }

    @Test
    fun overpassRuntimeErrorIsDetected() {
        try {
            OverpassParser.parse("""{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 3 after 151 seconds."}""")
            fail("例外が出るべき")
        } catch (e: OverpassException) {
            assertTrue(e.message!!.contains("timed out"))
        }
    }

    @Test
    fun overpassQueryContainsBbox() {
        val q = OverpassQuery.forTile(TileId.of(36.07, 138.03))
        assertTrue(q.contains("36.00000,138.00000,36.20000,138.25000"))
        assertTrue(q.contains("motorway_junction"))
    }

    @Test
    fun facilityNamesAreParsed() {
        fun check(raw: String, base: String, type: FacilityType?, etc: Boolean = false, dir: String? = null) {
            val p = FacilityNameParser.parse(raw)
            assertEquals("base of $raw", base, p.base)
            assertEquals("type of $raw", type, p.type)
            assertEquals("etc of $raw", etc, p.etcOnly)
            if (dir != null) assertEquals("dir of $raw", dir, p.direction)
        }
        check("岡谷IC", "岡谷", FacilityType.IC)
        check("岡谷ＪＣＴ", "岡谷", FacilityType.JCT)
        check("辰野PA", "辰野", FacilityType.PA)
        check("談合坂サービスエリア（上り線）", "談合坂", FacilityType.SA, dir = "上り線")
        check("海老名SA 下り", "海老名", FacilityType.SA, dir = "下り")
        check("諏訪湖スマートIC", "諏訪湖", FacilityType.SIC, etc = true)
        check("駒ヶ岳SAスマートインターチェンジ", "駒ヶ岳", FacilityType.SIC, etc = true)
        check("八王子料金所", "八王子", FacilityType.TB)
        check("初台出入口", "初台", FacilityType.IC)
        check("東京インターチェンジ", "東京", FacilityType.IC)
        check("中央", "中央", null)
        check("新空港IC(ETC専用)", "新空港", FacilityType.IC, etc = true)
        // v2.1: 表記ゆれ・IC 名＋向き・道路名の出口
        check("新居弁天I.C", "新居弁天", FacilityType.IC)
        check("篠原Ｉ．Ｃ．", "篠原", FacilityType.IC)
        check("豊川IC入口", "豊川", FacilityType.IC)
        check("真岡I.C北ランプ", "真岡", FacilityType.IC, dir = "北")
        check("浜松西IC第二ランプ", "浜松西", FacilityType.IC, dir = "第二")
        check("大和スマートIC北", "大和", FacilityType.SIC, etc = true, dir = "北")
        check("幸田須美", "幸田須美", null)
        val road = FacilityNameParser.parse("国道2号出口")
        assertEquals("国道2号", road.base)
        assertTrue("道路名＋出口は行き先の名前", road.entryWordOnly)
        assertTrue(!FacilityNameParser.parse("豊川IC入口").entryWordOnly)
    }

    @Test
    fun maxspeedParsing() {
        assertEquals(100, RoadAttr.parseMaxspeed("100"))
        assertEquals(80, RoadAttr.parseMaxspeed("80 km/h"))
        assertEquals(97, RoadAttr.parseMaxspeed("60 mph"))
        assertNull(RoadAttr.parseMaxspeed("signals"))
        assertNull(RoadAttr.parseMaxspeed("none"))
    }
}
