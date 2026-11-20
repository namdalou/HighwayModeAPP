package com.example.highwaymode.core.traffic

import com.example.highwaymode.core.json.JsonReader
import com.example.highwaymode.core.net.CancelToken
import com.example.highwaymode.core.net.CancelledException
import com.example.highwaymode.core.net.Http
import com.example.highwaymode.core.net.HttpStatusException
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * TomTom の「地点ごとの流れ」（Traffic API の Flow Segment Data。v2.23）。
 * 流れのタイル（[TomTomFlowTiles]）が使えないとき（このキーでは使えない・タイルの無料枠を使い切った）の代わり。
 * 前方の経路の決まった地点（[POINTS_M]）で問い合わせ、その地点を含む道路の区間の「いまの速さ・自由に流れるときの速さ・
 * 区間の形」を得る。地点の間は分からない（順調とみなす）ので、短い渋滞は出ないことがある。
 */
class TomTomFlowSegments(private val apiKey: String) {

    /** 1 つの区間 */
    class Segment(
        val lat: DoubleArray,
        val lon: DoubleArray,
        val currentKmh: Double,
        val freeKmh: Double,
        /** 0〜1（0.6 より大きければ、その時点の実際の走行データによる） */
        val confidence: Double,
        val closed: Boolean
    )

    fun fetch(q: TrafficQuery, rangeM: Double, corridor: RouteCorridor, cancel: CancelToken?, why: String): TomTomFlowTiles.Result {
        val items = ArrayList<TrafficItem>()
        val covered = ArrayList<DoubleArray>()
        var asked = 0
        var got = 0
        var lowConf = 0
        var noRoad = 0
        var jam = 0
        var slow = 0
        var firstError: Exception? = null
        for (d in POINTS_M) {
            if (d > rangeM || d > q.lengthM) break
            if (covered.any { d >= it[0] && d <= it[1] }) continue
            cancel?.check()
            val p = pointAt(q, d) ?: break
            asked++
            val seg = try {
                parse(Http.getText(url(p[0], p[1]), cancel = cancel, timeoutMs = 15_000))
            } catch (e: Exception) {
                if (e is CancelledException) throw e
                // 近くに道路が無い地点（HTTP 400）は飛ばす。キーで使えない・上限はやめる
                if (e is HttpStatusException && e.code == 400) {
                    noRoad++
                    continue
                }
                if (firstError == null) firstError = e
                if (e is HttpStatusException && e.code in intArrayOf(401, 403, 429)) break
                continue
            } ?: continue
            got++
            // この区間が経路のどこからどこまでか（その範囲の地点は問い合わせない）
            val a = corridor.alongOf(seg.lat.first(), seg.lon.first(), TomTomFlowTiles.NEAR_M)
            val b = corridor.alongOf(seg.lat.last(), seg.lon.last(), TomTomFlowTiles.NEAR_M)
            if (a != null && b != null) covered.add(doubleArrayOf(min(a, b), max(a, b)))
            if (seg.confidence < MIN_CONFIDENCE) {
                // その時点の走行データによらない値（ふだんの速さ）は使わない
                lowConf++
                continue
            }
            if (seg.closed || seg.freeKmh <= 1.0) continue
            val ratio = (seg.currentKmh / seg.freeKmh).coerceIn(0.03, 1.0)
            if (ratio >= TomTomFlowTiles.KEEP_BELOW) continue
            val kind = TomTomFlowTiles.kindFor(ratio)
            if (kind == TrafficKind.JAM) jam++ else if (kind == TrafficKind.SLOW) slow++
            items.add(TrafficItem(seg.lat, seg.lon, kind, ratio, seg.currentKmh, null, null, TomTomFlowTiles.severityFor(kind), source = TrafficItem.SOURCE_FLOW))
        }
        val note = buildString {
            append("道路の流れ（地点ごと。").append(why).append("）: ")
            if (got == 0 && firstError != null) append("取得できません（").append(TomTomFlowTiles.short(firstError!!)).append("）")
            else {
                append("$asked 地点で問い合わせ・区間 $got・遅い区間 ${items.size}（渋滞 $jam・混雑 $slow）")
                if (lowConf > 0) append("・実際の走行データの無い区間 $lowConf")
                if (noRoad > 0) append("・道路が見つからない地点（HTTP 400）$noRoad")
                firstError?.let { append("・失敗 ").append(TomTomFlowTiles.short(it)) }
            }
        }
        return TomTomFlowTiles.Result(items, note, if (got == 0) (firstError ?: IllegalStateException("区間がありません")) else null, got)
    }

    private fun url(lat: Double, lon: Double): String =
        "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/22/json?key=" + URLEncoder.encode(apiKey, "UTF-8") +
            "&point=" + String.format(Locale.US, "%.6f,%.6f", lat, lon) + "&unit=KMPH"

    companion object {
        /** 問い合わせる地点（現在地からの道のり）[m]。1 回の取得で最大 9 回 */
        val POINTS_M = doubleArrayOf(500.0, 3_000.0, 6_000.0, 9_000.0, 12_000.0, 16_000.0, 20_000.0, 25_000.0, 30_000.0)
        const val RANGE_M = 30_000.0
        const val MIN_CONFIDENCE = 0.5

        private fun pointAt(q: TrafficQuery, d: Double): DoubleArray? {
            for (i in 0 until q.lat.size - 1) {
                val d0 = q.dist[i]
                val d1 = q.dist[i + 1]
                if (d in d0..d1) {
                    val f = if (d1 > d0) (d - d0) / (d1 - d0) else 0.0
                    return doubleArrayOf(q.lat[i] + (q.lat[i + 1] - q.lat[i]) * f, q.lon[i] + (q.lon[i + 1] - q.lon[i]) * f)
                }
            }
            return null
        }

        /** Flow Segment Data の応答（json）を読む。区間が無ければ null */
        fun parse(json: String): Segment? {
            var cur = Double.NaN
            var free = Double.NaN
            var conf = 1.0
            var closed = false
            val la = ArrayList<Double>()
            val lo = ArrayList<Double>()
            val r = JsonReader(json)
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "flowSegmentData") {
                    r.skipValue()
                    continue
                }
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "currentSpeed" -> cur = r.nextDouble()
                        "freeFlowSpeed" -> free = r.nextDouble()
                        "confidence" -> conf = r.nextDouble()
                        "roadClosure" -> closed = r.nextBoolean()
                        "coordinates" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                if (r.nextName() != "coordinate") {
                                    r.skipValue()
                                    continue
                                }
                                r.beginArray()
                                while (r.hasNext()) {
                                    var a = Double.NaN
                                    var b = Double.NaN
                                    r.beginObject()
                                    while (r.hasNext()) {
                                        when (r.nextName()) {
                                            "latitude" -> a = r.nextDouble()
                                            "longitude" -> b = r.nextDouble()
                                            else -> r.skipValue()
                                        }
                                    }
                                    r.endObject()
                                    if (!a.isNaN() && !b.isNaN()) {
                                        la.add(a)
                                        lo.add(b)
                                    }
                                }
                                r.endArray()
                            }
                            r.endObject()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            r.endObject()
            if (la.size < 2 || cur.isNaN() || free.isNaN()) return null
            return Segment(la.toDoubleArray(), lo.toDoubleArray(), cur, free, conf, closed)
        }
    }
}
