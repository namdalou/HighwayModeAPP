package com.example.highwaymode.core.camera

import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.core.osm.OsmNode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 利用者が登録・取り込みしたオービスの一覧（CSV）。
 *
 *  形式（1 行 1 件, 見出し行は任意, # 以降はコメント）:
 *    緯度,経度[,進行方位(度, 北=0 東=90)][,制限速度][,名称]
 *  見出し行がある場合は列名で判定する（lat/latitude/緯度, lon/lng/longitude/経度,
 *  bearing/heading/direction/方位/方向, speed/maxspeed/制限速度, name/名称/種類/メモ）。
 *  緯度と経度が逆でも日本の範囲から判定する。
 */
object UserCameras {

    class Entry(val lat: Double, val lon: Double, val bearing: Double?, val maxspeed: Int?, val name: String?) {
        /** 位置から決まる安定した負の ID（OSM の ID と衝突しない） */
        val id: Long
            get() {
                val a = (lat * 1e5).roundToLong()
                val b = (lon * 1e5).roundToLong()
                val h = (a * 1_000_003L) xor (b * 9_999_991L) xor ((bearing ?: -1.0).roundToLong() * 131L)
                return -(abs(h) % 4_000_000_000_000L) - 1
            }

        fun toNode(): OsmNode {
            val tags = HashMap<String, String>()
            tags["highway"] = "speed_camera"
            tags["source"] = "user"
            if (bearing != null) tags["user:bearing"] = String.format(Locale.US, "%.0f", bearing)
            if (maxspeed != null) tags["maxspeed"] = maxspeed.toString()
            if (!name.isNullOrBlank()) tags["name"] = name
            return OsmNode(id, lat, lon, tags)
        }

        fun toCsv(): String = listOf(
            String.format(Locale.US, "%.6f", lat), String.format(Locale.US, "%.6f", lon),
            bearing?.let { String.format(Locale.US, "%.0f", it) } ?: "", maxspeed?.toString() ?: "", (name ?: "").replace(",", " ")
        ).joinToString(",")
    }

    class ParseResult(val entries: List<Entry>, val skipped: Int)

    private fun inJapanLat(v: Double) = v in 20.0..46.5
    private fun inJapanLon(v: Double) = v in 122.0..154.5

    fun parse(text: String): ParseResult {
        val out = ArrayList<Entry>()
        var skipped = 0
        var cols: Map<String, Int>? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            val f = split(line)
            if (cols == null && f.none { it.trim().toDoubleOrNull() != null && (inJapanLat(it.trim().toDouble()) || inJapanLon(it.trim().toDouble())) }) {
                cols = header(f)
                continue
            }
            val e = parseRow(f, cols)
            if (e == null) skipped++ else out.add(e)
        }
        // 同じ地点・同じ向きの重複を除く
        val uniq = LinkedHashMap<Long, Entry>()
        for (e in out) uniq.putIfAbsent(e.id, e)
        return ParseResult(uniq.values.toList(), skipped)
    }

    fun toCsv(entries: List<Entry>): String =
        "# HighwayMode オービス一覧: 緯度,経度,進行方位(度),制限速度,名称\nlat,lon,bearing,maxspeed,name\n" +
            entries.joinToString("\n") { it.toCsv() } + "\n"

    private fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (c in line) {
            when {
                c == '"' -> q = !q
                (c == ',' || c == '\t' || c == ';') && !q -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out.map { it.trim() }
    }

    private fun header(f: List<String>): Map<String, Int> {
        val m = HashMap<String, Int>()
        f.forEachIndexed { i, h0 ->
            val h = h0.lowercase(Locale.ROOT)
            when {
                h in setOf("lat", "latitude", "緯度", "y") -> m["lat"] = i
                h in setOf("lon", "lng", "long", "longitude", "経度", "x") -> m["lon"] = i
                h.contains("bearing") || h.contains("heading") || h.contains("direction") || h.contains("方位") || h.contains("方向") -> m["brg"] = i
                h.contains("speed") || h.contains("制限") || h.contains("速度") -> m["spd"] = i
                h.contains("name") || h.contains("名称") || h.contains("種類") || h.contains("メモ") || h.contains("type") -> m["name"] = i
            }
        }
        return m
    }

    private fun parseRow(f: List<String>, cols: Map<String, Int>?): Entry? {
        fun num(i: Int?): Double? = i?.let { f.getOrNull(it)?.replace("°", "")?.trim()?.toDoubleOrNull() }
        var lat: Double?
        var lon: Double?
        var brg: Double?
        var spd: Double?
        var name: String?
        if (cols != null && cols.containsKey("lat") && cols.containsKey("lon")) {
            lat = num(cols["lat"])
            lon = num(cols["lon"])
            brg = num(cols["brg"])
            spd = num(cols["spd"])
            name = cols["name"]?.let { f.getOrNull(it) }
        } else {
            lat = num(0)
            lon = num(1)
            brg = num(2)
            spd = num(3)
            name = f.getOrNull(4) ?: f.getOrNull(2)?.takeIf { it.toDoubleOrNull() == null }
        }
        if (lat == null || lon == null) return null
        if (!inJapanLat(lat) && inJapanLat(lon) && inJapanLon(lat)) {
            val t = lat
            lat = lon
            lon = t
        }
        if (!inJapanLat(lat) || !inJapanLon(lon)) return null
        val b = brg?.takeIf { it in 0.0..360.0 }
        val s = spd?.takeIf { it in 20.0..140.0 }?.toInt()
        return Entry(lat, lon, b, s, name?.takeIf { it.isNotBlank() && it.toDoubleOrNull() == null })
    }
}

/** オービス接近の音声案内のタイミングを決める */
class CameraAlertPlanner(
    private val stages: DoubleArray = doubleArrayOf(1000.0, 500.0)
) {
    class Alert(val cameraId: String, val stage: Int, val distanceM: Double, val text: String)

    private val announced = HashMap<String, Int>()

    /**
     * @param cameras 前方のオービス（近い順）
     * @param speedKmh 現在の車速
     * @return 今回読み上げるもの（最大 1 件）
     */
    fun update(cameras: List<FacilityView>, speedKmh: Double?): Alert? {
        val ids = cameras.map { it.id }.toHashSet()
        announced.keys.retainAll(ids)
        val v = speedKmh ?: 0.0
        if (v < 20.0) return null
        val c = cameras.firstOrNull() ?: return null
        val done = announced[c.id] ?: -1
        // 近い段階から調べる（いきなり近くで見つかった場合は遠い段階を読まない）。速いほど早めに
        for (s in stages.indices.reversed()) {
            val th = stages[s] * if (v > 90) 1.2 else 1.0
            if (c.distanceM <= th) {
                if (done >= s) return null
                announced[c.id] = s
                return Alert(c.id, s, c.distanceM, textFor(c))
            }
        }
        return null
    }

    private fun textFor(c: FacilityView): String {
        val d = c.distanceM
        val dist = when {
            d >= 950 -> "1キロ先"
            d >= 650 -> "700メートル先"
            d >= 350 -> "500メートル先"
            else -> "まもなく"
        }
        val lim = c.maxspeedKmh?.let { "、制限速度${it}キロ" } ?: ""
        val who = if (c.userDefined) "登録地点の速度取締機" else "速度取締機"
        return "${dist}、${who}です$lim"
    }

    fun reset() = announced.clear()
}
