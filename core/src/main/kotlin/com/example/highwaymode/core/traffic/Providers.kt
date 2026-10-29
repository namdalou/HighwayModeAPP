package com.example.highwaymode.core.traffic

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.json.JsonReader
import com.example.highwaymode.core.net.CancelToken
import com.example.highwaymode.core.net.Http
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** 経路の間引き（Douglas–Peucker）。maxPoints 以下になるまで許容誤差を広げる */
object RouteSimplify {
    fun indices(lat: DoubleArray, lon: DoubleArray, maxPoints: Int, startTol: Double = 15.0): IntArray {
        if (lat.size <= 2) return IntArray(lat.size) { it }
        var tol = startTol
        while (true) {
            val keep = BooleanArray(lat.size)
            keep[0] = true
            keep[lat.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.add(0 to lat.size - 1)
            val lat0 = lat[0]
            val mLat = Geo.metersPerDegLat(lat0)
            val mLon = Geo.metersPerDegLon(lat0)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b - a < 2) continue
                val ax = lon[a] * mLon
                val ay = lat[a] * mLat
                val bx = lon[b] * mLon
                val by = lat[b] * mLat
                val dx = bx - ax
                val dy = by - ay
                val l2 = dx * dx + dy * dy
                var worst = -1.0
                var wi = -1
                for (i in a + 1 until b) {
                    val px = lon[i] * mLon - ax
                    val py = lat[i] * mLat - ay
                    val d = if (l2 < 1e-6) Math.hypot(px, py) else abs(px * dy - py * dx) / Math.sqrt(l2)
                    if (d > worst) {
                        worst = d
                        wi = i
                    }
                }
                if (worst > tol && wi > 0) {
                    keep[wi] = true
                    stack.add(a to wi)
                    stack.add(wi to b)
                }
            }
            val n = keep.count { it }
            if (n <= maxPoints) return (0 until lat.size).filter { keep[it] }.toIntArray()
            tol *= 1.6
        }
    }
}

/**
 * HERE Traffic API v7（流れ: /v7/flow, 事故・規制: /v7/incidents）。
 * 経路に沿った回廊（corridor）で問い合わせるので、通る道路の情報だけが返る。
 */
class HereTrafficProvider(private val apiKey: String, private val includeIncidents: Boolean = true) : TrafficProvider {
    override val name = "HERE"

    override fun fetch(q: TrafficQuery, cancel: CancelToken?): TrafficData {
        val idx = RouteSimplify.indices(q.lat, q.lon, 280)
        val poly = FlexPolyline.encode(DoubleArray(idx.size) { q.lat[idx[it]] }, DoubleArray(idx.size) { q.lon[idx[it]] })
        // 長い回廊は URL 長の制限を超えるので POST（JSON）で送る
        val inObj = "{\"type\":\"corridor\",\"corridor\":\"$poly\",\"radius\":60}"
        val flowBody = "{\"in\":$inObj,\"locationReferencing\":[\"shape\"],\"functionalClasses\":[1,2,3]}"
        val incBody = "{\"in\":$inObj,\"locationReferencing\":[\"shape\"]}"
        val items = ArrayList<TrafficItem>()
        val flowJson = Http.postText(
            "https://data.traffic.hereapi.com/v7/flow?apiKey=" + URLEncoder.encode(apiKey, "UTF-8"),
            flowBody, "application/json", cancel = cancel, timeoutMs = 30_000
        )
        items.addAll(parseFlow(flowJson))
        if (includeIncidents) {
            try {
                val incJson = Http.postText(
                    "https://data.traffic.hereapi.com/v7/incidents?apiKey=" + URLEncoder.encode(apiKey, "UTF-8") + "&lang=ja-JP",
                    incBody, "application/json", cancel = cancel, timeoutMs = 30_000
                )
                items.addAll(parseIncidents(incJson))
            } catch (e: Exception) {
                if (e is com.example.highwaymode.core.net.CancelledException) throw e
                // 事故情報が取れなくても流れの情報は使う
            }
        }
        return TrafficData(name, System.currentTimeMillis(), items, false, q.lat, q.lon)
    }

    companion object {
        fun parseFlow(json: String): List<TrafficItem> {
            val out = ArrayList<TrafficItem>()
            val r = JsonReader(json)
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "results") {
                    r.skipValue()
                    continue
                }
                r.beginArray()
                while (r.hasNext()) {
                    var shapes: List<Pair<DoubleArray, DoubleArray>> = emptyList()
                    var speed = Double.NaN
                    var free = Double.NaN
                    var jam = Double.NaN
                    var trav: String? = null
                    var desc: String? = null
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "location" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    when (r.nextName()) {
                                        "description" -> desc = r.nextString()
                                        "shape" -> shapes = readHereShape(r)
                                        else -> r.skipValue()
                                    }
                                }
                                r.endObject()
                            }
                            "currentFlow" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    when (r.nextName()) {
                                        "speed" -> speed = r.nextDouble()
                                        "speedUncapped" -> if (speed.isNaN()) speed = r.nextDouble() else r.skipValue()
                                        "freeFlow" -> free = r.nextDouble()
                                        "jamFactor" -> jam = r.nextDouble()
                                        "traversability" -> trav = r.nextString()
                                        else -> r.skipValue()
                                    }
                                }
                                r.endObject()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    val closed = trav == "closed"
                    val ratio = when {
                        closed -> 0.0
                        !speed.isNaN() && !free.isNaN() && free > 0.5 -> (speed / free).coerceIn(0.02, 1.2)
                        !jam.isNaN() -> (1.0 - jam / 10.0 * 0.95).coerceIn(0.05, 1.0)
                        else -> null
                    }
                    val kind = when {
                        closed -> TrafficKind.CLOSURE
                        ratio != null && ratio < 0.4 -> TrafficKind.JAM
                        ratio != null && ratio < 0.7 -> TrafficKind.SLOW
                        else -> TrafficKind.FLOW
                    }
                    for ((la, lo) in shapes) {
                        if (la.size < 2) continue
                        out.add(TrafficItem(la, lo, kind, ratio, if (speed.isNaN()) null else speed * 3.6, null, desc,
                            if (jam.isNaN()) 0 else (jam / 2.5).toInt().coerceIn(0, 4)))
                    }
                }
                r.endArray()
            }
            r.endObject()
            return out
        }

        fun parseIncidents(json: String): List<TrafficItem> {
            val out = ArrayList<TrafficItem>()
            val r = JsonReader(json)
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "results") {
                    r.skipValue()
                    continue
                }
                r.beginArray()
                while (r.hasNext()) {
                    var shapes: List<Pair<DoubleArray, DoubleArray>> = emptyList()
                    var type: String? = null
                    var desc: String? = null
                    var closed = false
                    var crit: String? = null
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "location" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    if (r.nextName() == "shape") shapes = readHereShape(r) else r.skipValue()
                                }
                                r.endObject()
                            }
                            "incidentDetails" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    when (r.nextName()) {
                                        "type" -> type = r.nextString()
                                        "roadClosed" -> closed = r.nextBoolean()
                                        "criticality" -> crit = r.nextString()
                                        "description", "summary" -> {
                                            r.beginObject()
                                            while (r.hasNext()) {
                                                if (r.nextName() == "value" && desc == null) desc = r.nextString() else r.skipValue()
                                            }
                                            r.endObject()
                                        }
                                        else -> r.skipValue()
                                    }
                                }
                                r.endObject()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    val kind = when {
                        closed || type == "roadClosure" -> TrafficKind.CLOSURE
                        type == "accident" -> TrafficKind.ACCIDENT
                        type == "construction" -> TrafficKind.ROADWORK
                        type == "laneRestriction" -> TrafficKind.LANE_CLOSED
                        type == "disabledVehicle" || type == "roadHazard" -> TrafficKind.HAZARD
                        type == "weather" -> TrafficKind.WEATHER
                        type == "congestion" -> TrafficKind.JAM
                        else -> TrafficKind.OTHER
                    }
                    val sev = when (crit) {
                        "critical" -> 4
                        "major" -> 3
                        "minor" -> 1
                        else -> 2
                    }
                    for ((la, lo) in shapes) if (la.isNotEmpty()) {
                        out.add(TrafficItem(la, lo, kind, null, null, null, desc, sev))
                    }
                }
                r.endArray()
            }
            r.endObject()
            return out
        }

        private fun readHereShape(r: JsonReader): List<Pair<DoubleArray, DoubleArray>> {
            val out = ArrayList<Pair<DoubleArray, DoubleArray>>()
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "links") {
                    r.skipValue()
                    continue
                }
                r.beginArray()
                while (r.hasNext()) {
                    val la = ArrayList<Double>()
                    val lo = ArrayList<Double>()
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "points") {
                            r.beginArray()
                            while (r.hasNext()) {
                                var a = Double.NaN
                                var b = Double.NaN
                                r.beginObject()
                                while (r.hasNext()) {
                                    when (r.nextName()) {
                                        "lat" -> a = r.nextDouble()
                                        "lng" -> b = r.nextDouble()
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
                        } else r.skipValue()
                    }
                    r.endObject()
                    out.add(la.toDoubleArray() to lo.toDoubleArray())
                }
                r.endArray()
            }
            r.endObject()
            // 連続するリンクは 1 本につなぐ（向きの判定を安定させる）
            if (out.size <= 1) return out
            val la = ArrayList<Double>()
            val lo = ArrayList<Double>()
            var joinable = true
            for ((i, p) in out.withIndex()) {
                if (i > 0 && p.first.isNotEmpty() && la.isNotEmpty() &&
                    Geo.distance(la.last(), lo.last(), p.first[0], p.second[0]) > 30.0
                ) {
                    joinable = false
                    break
                }
                for (k in p.first.indices) {
                    if (k == 0 && la.isNotEmpty() && la.last() == p.first[0] && lo.last() == p.second[0]) continue
                    la.add(p.first[k])
                    lo.add(p.second[k])
                }
            }
            return if (joinable) listOf(la.toDoubleArray() to lo.toDoubleArray()) else out
        }
    }
}

/**
 * TomTom Traffic API（Incident Details v5）。渋滞（遅れ時間つき）・事故・通行止め・工事など。
 * 渋滞情報が無い区間は順調とみなせる（coverageAll）。
 * bbox は 1 回 10,000km² までなので、経路を区切って複数回に分けて問い合わせる。
 */
class TomTomTrafficProvider(private val apiKey: String) : TrafficProvider {
    override val name = "TomTom"

    override fun fetch(q: TrafficQuery, cancel: CancelToken?): TrafficData {
        val items = ArrayList<TrafficItem>()
        val seen = HashSet<String>()
        for (box in boxes(q)) {
            val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=" + URLEncoder.encode(apiKey, "UTF-8") +
                "&bbox=" + String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", box[1], box[0], box[3], box[2]) +
                "&fields=" + URLEncoder.encode(FIELDS, "UTF-8") +
                "&language=ja-JP&timeValidityFilter=present"
            val json = Http.getText(url, cancel = cancel, timeoutMs = 30_000)
            for ((id, item) in parse(json)) if (id == null || seen.add(id)) items.add(item)
        }
        return TrafficData(name, System.currentTimeMillis(), items, true, q.lat, q.lon)
    }

    /** 経路を 1 辺 90km 以内の範囲に分ける。[south, west, north, east] */
    private fun boxes(q: TrafficQuery): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var s = q.lat[0]
        var n = q.lat[0]
        var w = q.lon[0]
        var e = q.lon[0]
        fun areaKm2(s: Double, w: Double, n: Double, e: Double): Double {
            val h = (n - s) * 111.0
            val wd = (e - w) * 111.0 * cos(Math.toRadians((n + s) / 2))
            return (h + 2.0) * (wd + 2.0)
        }
        for (i in 1 until q.lat.size) {
            val ns = min(s, q.lat[i])
            val nn = max(n, q.lat[i])
            val nw = min(w, q.lon[i])
            val ne = max(e, q.lon[i])
            if (areaKm2(ns, nw, nn, ne) > 9000.0) {
                out.add(doubleArrayOf(s - 0.005, w - 0.006, n + 0.005, e + 0.006))
                s = q.lat[i - 1].coerceAtMost(q.lat[i]); n = q.lat[i - 1].coerceAtLeast(q.lat[i])
                w = q.lon[i - 1].coerceAtMost(q.lon[i]); e = q.lon[i - 1].coerceAtLeast(q.lon[i])
            } else {
                s = ns; n = nn; w = nw; e = ne
            }
        }
        out.add(doubleArrayOf(s - 0.005, w - 0.006, n + 0.005, e + 0.006))
        return out.take(4)
    }

    companion object {
        private const val FIELDS = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay," +
            "events{description,code,iconCategory},startTime,endTime,from,to,length,delay,roadNumbers}}}"

        /** @return (id, 情報) の一覧 */
        fun parse(json: String): List<Pair<String?, TrafficItem>> {
            val out = ArrayList<Pair<String?, TrafficItem>>()
            val r = JsonReader(json)
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "incidents") {
                    r.skipValue()
                    continue
                }
                r.beginArray()
                while (r.hasNext()) {
                    var la = DoubleArray(0)
                    var lo = DoubleArray(0)
                    var id: String? = null
                    var icon = 0
                    var mag = 0
                    var delay = Double.NaN
                    var length = Double.NaN
                    var desc: String? = null
                    var from: String? = null
                    var to: String? = null
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "geometry" -> {
                                r.beginObject()
                                var type = ""
                                while (r.hasNext()) {
                                    when (r.nextName()) {
                                        "type" -> type = r.nextString()
                                        "coordinates" -> {
                                            val pts = ArrayList<DoubleArray>()
                                            r.beginArray()
                                            if (r.peek() == JsonReader.Token.NUMBER) {
                                                // Point: [lon, lat]
                                                val x = r.nextDouble()
                                                val y = r.nextDouble()
                                                while (r.hasNext()) r.skipValue()
                                                pts.add(doubleArrayOf(x, y))
                                            } else {
                                                while (r.hasNext()) {
                                                    r.beginArray()
                                                    val x = r.nextDouble()
                                                    val y = r.nextDouble()
                                                    while (r.hasNext()) r.skipValue()
                                                    r.endArray()
                                                    pts.add(doubleArrayOf(x, y))
                                                }
                                            }
                                            r.endArray()
                                            la = DoubleArray(pts.size) { pts[it][1] }
                                            lo = DoubleArray(pts.size) { pts[it][0] }
                                        }
                                        else -> r.skipValue()
                                    }
                                }
                                r.endObject()
                                if (type.isEmpty()) Unit
                            }
                            "properties" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    when (r.nextName()) {
                                        "id" -> id = r.nextString()
                                        "iconCategory" -> icon = r.nextLong().toInt()
                                        "magnitudeOfDelay" -> mag = r.nextLong().toInt()
                                        "delay" -> delay = if (r.peek() == JsonReader.Token.NULL) {
                                            r.nextNull(); Double.NaN
                                        } else r.nextDouble()
                                        "length" -> length = if (r.peek() == JsonReader.Token.NULL) {
                                            r.nextNull(); Double.NaN
                                        } else r.nextDouble()
                                        "from" -> from = if (r.peek() == JsonReader.Token.NULL) { r.nextNull(); null } else r.nextString()
                                        "to" -> to = if (r.peek() == JsonReader.Token.NULL) { r.nextNull(); null } else r.nextString()
                                        "events" -> {
                                            r.beginArray()
                                            while (r.hasNext()) {
                                                r.beginObject()
                                                while (r.hasNext()) {
                                                    if (r.nextName() == "description" && desc == null && r.peek() == JsonReader.Token.STRING) desc = r.nextString()
                                                    else r.skipValue()
                                                }
                                                r.endObject()
                                            }
                                            r.endArray()
                                        }
                                        else -> r.skipValue()
                                    }
                                }
                                r.endObject()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (la.isEmpty()) continue
                    val kind = when (icon) {
                        1 -> TrafficKind.ACCIDENT
                        6 -> TrafficKind.JAM
                        7 -> TrafficKind.LANE_CLOSED
                        8 -> TrafficKind.CLOSURE
                        9 -> TrafficKind.ROADWORK
                        14, 3 -> TrafficKind.HAZARD
                        2, 4, 5, 10, 11 -> TrafficKind.WEATHER
                        else -> TrafficKind.OTHER
                    }
                    // 遅れ時間から速度比を推定（自由流 80km/h として）。遅れが無ければ規模から
                    val ratio: Double? = when {
                        kind == TrafficKind.CLOSURE -> 0.0
                        !delay.isNaN() && delay > 0 && !length.isNaN() && length > 50 -> {
                            val tFree = length / 22.2
                            (tFree / (tFree + delay)).coerceIn(0.03, 1.0)
                        }
                        kind == TrafficKind.JAM -> when (mag) {
                            1 -> 0.6
                            2 -> 0.35
                            3 -> 0.15
                            else -> 0.4
                        }
                        else -> null
                    }
                    val text = listOfNotNull(desc, if (from != null && to != null) "$from→$to" else null).joinToString(" ")
                    out.add(id to TrafficItem(la, lo, if (kind == TrafficKind.JAM && ratio != null && ratio > 0.55) TrafficKind.SLOW else kind,
                        ratio, null, if (delay.isNaN()) null else delay, text.ifEmpty { null }, mag.coerceIn(0, 4)))
                }
                r.endArray()
            }
            r.endObject()
            return out
        }
    }
}

/**
 * Google Routes API（computeRoutes, TRAFFIC_ON_POLYLINE）。
 * 前方経路を経由地で固定してルートを引き、区間ごとの混雑度（NORMAL / SLOW / TRAFFIC_JAM）と
 * 渋滞を考慮した所要時間を得る。
 */
class GoogleRoutesTrafficProvider(private val apiKey: String) : TrafficProvider {
    override val name = "Google"

    override fun fetch(q: TrafficQuery, cancel: CancelToken?): TrafficData {
        // 経由地: 約 10km ごと（最大 8 点）。各点に進行方位を付けて反対車線にスナップしないようにする
        val n = q.lat.size
        val total = q.lengthM
        val vias = ArrayList<Int>()
        val step = max(8000.0, total / 9)
        var next = step
        for (i in 1 until n - 1) {
            if (q.dist[i] >= next && total - q.dist[i] > 3000.0) {
                vias.add(i)
                next += step
                if (vias.size >= 8) break
            }
        }
        fun loc(i: Int): String = String.format(
            Locale.US, "{\"location\":{\"latLng\":{\"latitude\":%.6f,\"longitude\":%.6f},\"heading\":%d}}",
            q.lat[i], q.lon[i], Math.round(Geo.normalizeDeg(q.bearing[i])).toInt() % 360
        )
        val body = StringBuilder()
        body.append("{\"origin\":").append(loc(0))
        body.append(",\"destination\":").append(loc(n - 1))
        if (vias.isNotEmpty()) {
            body.append(",\"intermediates\":[")
            body.append(vias.joinToString(",") { loc(it).replaceFirst("{", "{\"via\":true,") })
            body.append("]")
        }
        body.append(",\"travelMode\":\"DRIVE\",\"routingPreference\":\"TRAFFIC_AWARE\"")
        body.append(",\"extraComputations\":[\"TRAFFIC_ON_POLYLINE\"],\"polylineQuality\":\"HIGH_QUALITY\"")
        body.append(",\"languageCode\":\"ja-JP\",\"units\":\"METRIC\"}")
        val json = Http.postText(
            "https://routes.googleapis.com/directions/v2:computeRoutes", body.toString(), "application/json",
            headers = mapOf(
                "X-Goog-Api-Key" to apiKey,
                "X-Goog-FieldMask" to "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals"
            ),
            cancel = cancel, timeoutMs = 30_000
        )
        val (items, delay) = parse(json)
        return TrafficData(name, System.currentTimeMillis(), items, true, q.lat, q.lon, delay)
    }

    companion object {
        /** @return (区間ごとの情報, 経路全体の遅れ秒) */
        fun parse(json: String): Pair<List<TrafficItem>, Double?> {
            var poly: String? = null
            var duration = Double.NaN
            var static = Double.NaN
            val intervals = ArrayList<Triple<Int, Int, String>>()
            val r = JsonReader(json)
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "routes") {
                    r.skipValue()
                    continue
                }
                r.beginArray()
                var first = true
                while (r.hasNext()) {
                    if (!first) {
                        r.skipValue()
                        continue
                    }
                    first = false
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "duration" -> duration = parseDuration(r.nextString())
                            "staticDuration" -> static = parseDuration(r.nextString())
                            "polyline" -> {
                                r.beginObject()
                                while (r.hasNext()) if (r.nextName() == "encodedPolyline") poly = r.nextString() else r.skipValue()
                                r.endObject()
                            }
                            "travelAdvisory" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    if (r.nextName() != "speedReadingIntervals") {
                                        r.skipValue()
                                        continue
                                    }
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        var a = 0
                                        var b = 0
                                        var sp = "NORMAL"
                                        r.beginObject()
                                        while (r.hasNext()) {
                                            when (r.nextName()) {
                                                "startPolylinePointIndex" -> a = r.nextLong().toInt()
                                                "endPolylinePointIndex" -> b = r.nextLong().toInt()
                                                "speed" -> sp = r.nextString()
                                                else -> r.skipValue()
                                            }
                                        }
                                        r.endObject()
                                        intervals.add(Triple(a, b, sp))
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
                r.endArray()
            }
            r.endObject()
            val p = poly ?: return emptyList<TrafficItem>() to null
            val (la, lo) = GooglePolyline.decode(p)
            val delay = if (!duration.isNaN() && !static.isNaN()) max(0.0, duration - static) else null
            // 混雑区間の速度比: 既定値（混雑 0.5, 渋滞 0.2）を経路全体の遅れに合うよう調整
            val base = mapOf("SLOW" to 0.5, "TRAFFIC_JAM" to 0.2)
            var est = 0.0
            for ((a, b, sp) in intervals) {
                val rr = base[sp] ?: continue
                est += segLen(la, lo, a, b) / 22.2 * (1 / rr - 1)
            }
            val k = if (delay != null && delay > 30 && est > 30) (delay / est).coerceIn(0.3, 3.0) else 1.0
            val items = ArrayList<TrafficItem>()
            for ((a, b, sp) in intervals) {
                if (b <= a || b >= la.size) continue
                val rr = base[sp]
                val ratio = if (rr == null) 1.0 else 1.0 / (1.0 + k * (1 / rr - 1))
                val kind = when (sp) {
                    "TRAFFIC_JAM" -> TrafficKind.JAM
                    "SLOW" -> TrafficKind.SLOW
                    else -> TrafficKind.FLOW
                }
                items.add(TrafficItem(la.copyOfRange(a, b + 1), lo.copyOfRange(a, b + 1), kind, ratio, null, null, null,
                    if (kind == TrafficKind.JAM) 3 else if (kind == TrafficKind.SLOW) 1 else 0))
            }
            return items to delay
        }

        private fun segLen(la: DoubleArray, lo: DoubleArray, a: Int, b: Int): Double {
            var s = 0.0
            for (i in a until min(b, la.size - 1)) s += Geo.distance(la[i], lo[i], la[i + 1], lo[i + 1])
            return s
        }

        private fun parseDuration(s: String): Double = s.trim().removeSuffix("s").toDoubleOrNull() ?: Double.NaN
    }
}
