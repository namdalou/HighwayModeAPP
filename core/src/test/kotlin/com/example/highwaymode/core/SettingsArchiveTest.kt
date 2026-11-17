package com.example.highwaymode.core

import com.example.highwaymode.core.backup.SettingsArchive
import com.example.highwaymode.core.backup.SettingsArchive.PrefValue
import com.example.highwaymode.core.camera.UserCameras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 設定の書き出し・読み込みのファイル（v2.19） */
class SettingsArchiveTest {

    private val lineSep = Char(0x2028).toString()
    private val ctrl = Char(0x01).toString()

    private fun sample(): SettingsArchive {
        val hw = linkedMapOf<String, PrefValue>(
            "display_mode" to PrefValue.Int32(2),
            "eta_style" to PrefValue.Int32(-1),
            "traffic_provider" to PrefValue.Str("tomtom"),
            "traffic_key_tomtom" to PrefValue.Str("AbC123xyz"),
            "traffic_key_here" to PrefValue.Str(""),
            "hwdb_last_check" to PrefValue.Int64(1_763_380_800_123L),
            "camera_voice" to PrefValue.Bool(false),
            "driver_profile" to PrefValue.Str("{\"ratio\":1.04,\n\"n\":12}\\end\t\"" + lineSep + ctrl),
            "overpass_endpoints" to PrefValue.Str("https://a.example/api\nhttps://b.example/api"),
            "dual_highway_layout" to PrefValue.Str("SPLIT"),
            "tags" to PrefValue.StrSet(setOf("一般道", "a,b", "")),
            "empty_set" to PrefValue.StrSet(emptySet())
        )
        val sp = linkedMapOf<String, PrefValue>(
            "bar_visual" to PrefValue.Int32(5),
            "bar_custom_color" to PrefValue.Int32(0xFF00A0FF.toInt()),
            "meter_peak" to PrefValue.Bool(true),
            "gain" to PrefValue.Real(0.7f),
            "tiny" to PrefValue.Real(1.0e-8f),
            "neg" to PrefValue.Real(-123.456f),
            "fallback_pkg" to PrefValue.Str("com.amazon.mp3"),
            "dump_wanted" to PrefValue.Bool(true)
        )
        val cams = UserCameras.toCsv(
            listOf(
                UserCameras.Entry(35.123456, 137.654321, 182.5, 100, "登録地点"),
                UserCameras.Entry(34.9, 136.8, null, null, "名前, カンマ入り")
            )
        )
        return SettingsArchive("2.19.0", "2026-11-17T12:00:00Z", true, mapOf("highway_mode" to hw, "spectrum" to sp), cams)
    }

    @Test
    fun roundTripKeepsEveryValue() {
        val a = sample()
        val json = a.toJson()
        println(json.take(600))
        val b = SettingsArchive.parse(json)
        assertEquals("2.19.0", b.appVersion)
        assertEquals("2026-11-17T12:00:00Z", b.exportedAt)
        assertTrue(b.secrets)
        assertEquals(a.prefs, b.prefs)
        assertEquals(a.camerasCsv, b.camerasCsv)
        // 登録したオービスも、CSV として読み戻せる
        val cams = UserCameras.parse(b.camerasCsv!!).entries
        assertEquals(2, cams.size)
        assertEquals(35.123456, cams[0].lat, 1e-9)
        assertEquals(100, cams[0].maxspeed)
        assertNull(cams[1].bearing)
        // 小数は同じ値に戻る
        assertEquals(0.7f, (b.prefs["spectrum"]!!["gain"] as PrefValue.Real).v)
        // 制御文字・行区切りはファイルの中では \u で表す（そのまま書くと、読めないエディタがある）
        assertFalse(json.contains(lineSep))
        assertFalse(json.contains(ctrl))
    }

    @Test
    fun secretsCanBeLeftOut() {
        val a = sample()
        assertEquals(1, a.secretCount())
        val b = SettingsArchive.parse(a.withoutSecrets().toJson())
        assertFalse(b.secrets)
        assertTrue(b.prefs["highway_mode"]!!.keys.none { it.startsWith("traffic_key_") })
        assertEquals(0, b.secretCount())
        // API キー以外はそのまま
        assertEquals(PrefValue.Str("tomtom"), b.prefs["highway_mode"]!!["traffic_provider"])
        assertEquals(a.settingCount(), b.settingCount())
        assertFalse(b.toJson().contains("AbC123xyz"))
    }

    private fun assertRejected(json: String, expect: String) {
        try {
            SettingsArchive.parse(json)
            fail("読み込めてしまった: $json")
        } catch (e: SettingsArchive.ArchiveException) {
            assertTrue("「$expect」を含むはず: ${e.message}", e.message!!.contains(expect))
        }
    }

    @Test
    fun rejectsOtherFiles() {
        assertRejected("{\"type\":\"FeatureCollection\",\"features\":[]}", "このアプリで書き出した")
        assertRejected("緯度,経度\n35.1,137.2", "このアプリで書き出した")
        assertRejected("", "このアプリで書き出した")
        assertRejected("[1,2,3]", "このアプリで書き出した")
        // 新しい版のアプリの形
        assertRejected("{\"app\":\"spectrum-highway\",\"format\":99,\"prefs\":{}}", "アプリを更新")
        // 途中で切れている
        val full = sample().toJson()
        assertRejected(full.substring(0, full.length / 2), "壊れて")
    }

    @Test
    fun skipsUnknownItems() {
        val json = """
            ${Char(0xFEFF)}{
              "future": {"a": [1, 2, {"b": null}]},
              "app": "spectrum-highway",
              "format": 1,
              "prefs": {
                "other_app_prefs": { "x": { "type": "int", "value": 1 } },
                "spectrum": {
                  "bar_visual": { "value": 3, "type": "int", "note": "順番が逆でも読む" },
                  "new_kind": { "type": "color", "value": "#ff0000" },
                  "too_big": { "type": "int", "value": 9999999999 }
                }
              },
              "cameras": null
            }
        """.trimIndent()
        val a = SettingsArchive.parse(json)
        assertEquals(setOf("spectrum"), a.prefs.keys)
        assertEquals(mapOf("bar_visual" to PrefValue.Int32(3)), a.prefs["spectrum"])
        assertNull(a.camerasCsv)
        assertFalse(a.secrets)
    }

    @Test
    fun valuesFromSharedPreferences() {
        assertEquals(PrefValue.Int32(3), PrefValue.of(3))
        assertEquals(PrefValue.Int64(3L), PrefValue.of(3L))
        assertEquals(PrefValue.Real(1.5f), PrefValue.of(1.5f))
        assertEquals(PrefValue.Bool(true), PrefValue.of(true))
        assertEquals(PrefValue.Str("x"), PrefValue.of("x"))
        assertEquals(PrefValue.StrSet(setOf("a")), PrefValue.of(setOf("a")))
        assertNull(PrefValue.of(null))
        assertNull(PrefValue.of(1.5))
    }
}
