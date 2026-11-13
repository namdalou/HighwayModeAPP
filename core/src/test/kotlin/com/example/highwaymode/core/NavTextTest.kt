package com.example.highwaymode.core

import com.example.highwaymode.core.route.NavText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** カーナビのアプリの通知の文字から、案内（距離・案内の文）を取り出す。案内をしていないときの通知は案内にしない */
class NavTextTest {

    private fun parse(vararg pieces: Pair<String, String>) = NavText.parse(pieces.toList())

    @Test
    fun drivingModeNotificationIsNotGuidance() {
        // Android Auto につなぐと出る通知（ナビは始めていない）
        val a = parse("title" to "Google マップは運転モードです", "text" to "タップしてナビを開始")
        assertNotNull(a)
        assertFalse(a!!.guidance)
        assertFalse(parse("title" to "Googleマップは運転モード")!!.guidance)
        assertFalse(parse("title" to "Google Maps is in driving mode")!!.guidance)
        // 行き先の候補（文の途中に距離が入っていても、案内ではない）
        assertFalse(parse("title" to "運転モード", "text" to "自宅まで 25 分（12 km）")!!.guidance)
        assertFalse(parse("title" to "Google マップは運転モードです", "text" to "目的地を設定してください")!!.guidance)
    }

    @Test
    fun guidanceInsideTheDrivingModeNotificationIsKept() {
        // 運転モードの通知の本文に次の案内が出る場合（距離が読めれば案内）
        val a = parse("title" to "Google マップは運転モードです", "text" to "500 m 先 国道1号 を右折")!!
        assertTrue(a.guidance)
        assertEquals(500.0, a.distanceM!!, 0.01)
    }

    @Test
    fun otherOngoingNotificationsAreNotGuidance() {
        assertFalse(parse("title" to "Yahoo!カーナビ", "text" to "起動中")!!.guidance)
        assertFalse(parse("title" to "オフライン マップをダウンロードしています", "text" to "45%")!!.guidance)
        assertFalse(parse("title" to "現在地を共有しています")!!.guidance)
        assertNull(parse())
        assertNull(parse("title" to " "))
    }

    @Test
    fun guidanceWithDistanceInTheTitle() {
        val a = parse("title" to "500 m", "text" to "国道1号 を右折")!!
        assertTrue(a.guidance)
        assertEquals(500.0, a.distanceM!!, 0.01)
        assertEquals("国道1号 を右折", a.instruction)

        // 独自の表示の部品。到着時刻の部品は案内の文に入れない
        val b = parse("nav_title" to "1.2 km", "nav_description" to "豊田JCT 東名高速道路 東京 方面", "nav_time" to "13:05 到着")!!
        assertTrue(b.guidance)
        assertEquals(1200.0, b.distanceM!!, 0.01)
        assertEquals("豊田JCT 東名高速道路 東京 方面", b.instruction)
    }

    @Test
    fun guidanceWithoutDistanceNeedsManeuverWords() {
        val a = parse("title" to "目的地に到着しました")!!
        assertTrue(a.guidance)
        assertNull(a.distanceM)
        assertTrue(parse("title" to "Yahoo!カーナビ", "text" to "この先 岡崎IC 出口")!!.guidance)
        assertTrue(parse("title" to "Turn right onto Route 1")!!.guidance)
        // 英字の単語の一部（MUSIC の IC など）は、IC・JCT とみなさない
        assertFalse(parse("title" to "MUSIC PARTY", "text" to "再生中")!!.guidance)
    }

    @Test
    fun distanceAtTheStartOfALine() {
        // Google マップ以外のアプリの書き方（題名はアプリの名前、本文が「距離 ＋ 案内」）
        val a = parse("title" to "Yahoo!カーナビ", "text" to "300m先 左方向 国道23号")!!
        assertTrue(a.guidance)
        assertEquals(300.0, a.distanceM!!, 0.01)
        val b = parse("title" to "moviLink", "text" to "約 2.5km 先、名古屋南JCT を 伊勢湾岸道 四日市 方面")!!
        assertTrue(b.guidance)
        assertEquals(2500.0, b.distanceM!!, 0.01)
    }

    @Test
    fun wordChecks() {
        assertTrue(NavText.isNotGuidance("Google マップは 運転 モード です"))
        assertTrue(NavText.isNotGuidance("Ｄｒｉｖｉｎｇ Ｍｏｄｅ"))
        assertFalse(NavText.isNotGuidance("国道1号 を右折"))
        assertTrue(NavText.hasManeuver("斜め 右 方向"))
        assertTrue(NavText.hasManeuver("豊田ＪＣＴ"))
        assertFalse(NavText.hasManeuver("起動中"))
        assertEquals(52_000.0, NavText.distanceOf("52 km")!!, 0.01)
        assertEquals(800.0, NavText.distanceOf("800m 先")!!, 0.01)
    }
}
