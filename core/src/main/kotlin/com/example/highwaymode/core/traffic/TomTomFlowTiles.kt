package com.example.highwaymode.core.traffic

import com.example.highwaymode.core.net.CancelToken
import com.example.highwaymode.core.net.CancelledException
import com.example.highwaymode.core.net.Http
import com.example.highwaymode.core.net.HttpStatusException
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * TomTom の「道路の流れ」（Traffic API の Vector Flow Tiles。v2.23）。
 *
 * 事故・渋滞の一覧（Incident Details）には、TomTom が「渋滞」として登録したものしか載らず、日本の高速道路では
 * 案内板に出ている渋滞が載っていないことがあった（利用者の報告: 何 km も手前から案内板に出ている渋滞が、
 * 自分が渋滞にはまって減速するまで表示されなかった）。流れのタイルには、道路の区間ごとの
 * 「いまの速さ ÷ 自由に流れるときの速さ」（style = relative の traffic_level）が載るので、前方の経路にかかる
 * タイルを読み、遅い区間を渋滞・混雑として使う（無料枠もタイルの方が大きい）。
 *
 * タイルの読み方の前提（TomTom の資料による。実際の値は開発者向け設定の「渋滞情報（確認用）」で確かめる）:
 *  - 線の地物ごとに traffic_level（0〜1。1 = 自由に流れている）、road_closure（通行止め）などの属性がある
 *  - 中央分離帯のある道路は上下線が別の線。線の向き・位置で車線を決める（照合は [TrafficMapper]）
 */
class TomTomFlowTiles(private val apiKey: String) {

    /** 取得の結果 */
    class Result(val items: List<TrafficItem>, val note: String, val error: Exception?, val tilesOk: Int)

    /** 読んだタイルの集計（確認用） */
    class Stats {
        var lines = 0
        var withLevel = 0
        var near = 0
        var jam = 0
        var slow = 0
        var closures = 0
        var minLevel = Double.NaN
        var maxLevel = Double.NaN
        val layers = LinkedHashSet<String>()
        val keys = LinkedHashSet<String>()

        fun level(v: Double) {
            if (minLevel.isNaN() || v < minLevel) minLevel = v
            if (maxLevel.isNaN() || v > maxLevel) maxLevel = v
        }
    }

    /** 属性の指定（roadTypes・tags）が受け付けられなかったら、次からは付けない */
    @Volatile
    private var withParams = true
    private val cache = LinkedHashMap<Long, Pair<Long, ByteArray>>()
    /** タイルが使えなかった（キーで使えない・上限）ときは、しばらくタイルを読まず、地点ごとの流れ（[TomTomFlowSegments]）を使う */
    @Volatile
    private var tilesBlockedUntilMs = 0L
    private val segments = TomTomFlowSegments(apiKey)

    /**
     * 前方経路（現在地から rangeM まで）にかかるタイルを読み、経路の近くの流れの悪い区間を返す。
     * 1 枚でも読めれば成功（読めなかった枚数は note に書く）。1 枚も読めなければ error。
     */
    fun fetch(q: TrafficQuery, rangeM: Double, corridor: RouteCorridor, cancel: CancelToken?): Result {
        val now0 = System.currentTimeMillis()
        if (now0 < tilesBlockedUntilMs) return segments.fetch(q, min(rangeM, TomTomFlowSegments.RANGE_M), corridor, cancel, "タイルが使えないため")
        val r = fetchTiles(q, rangeM, corridor, cancel)
        val e = r.error
        if (r.tilesOk == 0 && e is HttpStatusException) {
            // このキーではタイルが使えない・タイルの無料枠を使い切った・タイルの問い合わせが受け付けられない:
            // 30 分は地点ごとの流れに切り替える（通信できないとき（HTTP の応答が無い）は、どちらも取れないので切り替えない）
            tilesBlockedUntilMs = now0 + TILE_BLOCK_MS
            return segments.fetch(q, min(rangeM, TomTomFlowSegments.RANGE_M), corridor, cancel, "タイルが使えないため（${short(e)}）")
        }
        return r
    }

    private fun fetchTiles(q: TrafficQuery, rangeM: Double, corridor: RouteCorridor, cancel: CancelToken?): Result {
        val tiles = tilesFor(q, rangeM)
        val stats = Stats()
        val items = ArrayList<TrafficItem>()
        var ok = 0
        var failed = 0
        var firstError: Exception? = null
        var consecutive = 0
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val it = cache.entries.iterator()
            while (it.hasNext()) if (now - it.next().value.first > CACHE_MS) it.remove()
        }
        for (t in tiles) {
            cancel?.check()
            val (x, y) = t[0] to t[1]
            val key = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
            val bytes = try {
                synchronized(cache) { cache[key]?.second } ?: download(x, y, cancel).also { b ->
                    synchronized(cache) { cache[key] = System.currentTimeMillis() to b }
                }
            } catch (e: Exception) {
                if (e is CancelledException) throw e
                failed++
                consecutive++
                if (firstError == null) firstError = e
                // キーがタイルに使えない・上限・通信できない: 残りも同じなのでやめる
                if (e is HttpStatusException && e.code in intArrayOf(401, 403, 429) || consecutive >= 3) break
                continue
            }
            consecutive = 0
            try {
                items.addAll(parse(bytes, ZOOM, x, y, corridor, stats))
                ok++
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = e
            }
        }
        val note = buildString {
            append("道路の流れ（タイル）: ")
            if (ok == 0) {
                append("取得できません")
                firstError?.let { append("（").append(short(it)).append("）") }
            } else {
                append("${ok} 枚")
                if (failed > 0) append("（読めなかった ${failed} 枚: ").append(firstError?.let { short(it) } ?: "").append("）")
                append("・線 ${stats.lines}（速さの値つき ${stats.withLevel}）・経路の近くの遅い区間 ${stats.near}（渋滞 ${stats.jam}・混雑 ${stats.slow}）")
                if (!stats.minLevel.isNaN()) append(String.format(java.util.Locale.US, "・値の範囲 %.2f〜%.2f", stats.minLevel, stats.maxLevel))
                if (stats.closures > 0) append("・通行止めの印 ${stats.closures}")
                if (stats.withLevel == 0) append("・層 ${stats.layers.joinToString(",")}・属性 ${stats.keys.take(12).joinToString(",")}")
            }
        }
        return Result(items, note, if (ok == 0) (firstError ?: IllegalStateException("タイルがありません")) else null, ok)
    }

    private fun download(x: Int, y: Int, cancel: CancelToken?): ByteArray {
        try {
            return Http.getBytes(url(x, y, withParams), cancel = cancel, timeoutMs = 20_000)
        } catch (e: HttpStatusException) {
            if (e.code == 400 && withParams) {
                // 属性の指定の書き方が受け付けられない: 指定なし（既定の属性・すべての道路）で読み直す
                withParams = false
                return Http.getBytes(url(x, y, false), cancel = cancel, timeoutMs = 20_000)
            }
            throw e
        }
    }

    private fun url(x: Int, y: Int, params: Boolean): String {
        val sb = StringBuilder("https://api.tomtom.com/traffic/map/4/tile/flow/relative/")
            .append(ZOOM).append('/').append(x).append('/').append(y).append(".pbf?key=").append(URLEncoder.encode(apiKey, "UTF-8"))
        if (params) {
            // 高速道路〜主要な一般道（TomTom の道路の種類 0〜3）だけ。属性は使うものだけ
            sb.append("&roadTypes=").append(URLEncoder.encode("[0,1,2,3]", "UTF-8"))
            sb.append("&tags=").append(URLEncoder.encode("[traffic_level,traffic_road_coverage,road_closure,road_type,road_category]", "UTF-8"))
        }
        return sb.toString()
    }

    companion object {
        /** タイルの細かさ（12 = 1 枚 約 8km 四方。座標の細かさ 約 2m） */
        const val ZOOM = 12
        const val MAX_TILES = 20
        /** これより遅い（速さの比）区間を「渋滞」（NEXCO の渋滞の目安 40km/h 以下 ≒ 自由流 90km/h の 0.45） */
        const val JAM_BELOW = 0.45
        /** これより遅い区間を「混雑」 */
        const val SLOW_BELOW = 0.7
        /** これより速い区間は使わない（順調とみなす） */
        const val KEEP_BELOW = 0.9
        /** 経路からこの距離の中にある線だけを残す [m] */
        const val NEAR_M = 60.0
        /** 同じタイルを読み直さない時間（続けて取り直したとき） */
        const val CACHE_MS = 100_000L
        /** タイルが使えなかったとき、地点ごとの流れに切り替えておく時間 */
        const val TILE_BLOCK_MS = 30 * 60_000L

        /** 速さの比から種類（渋滞・混雑・やや遅い）。タイル・地点ごとの流れで共通 */
        fun kindFor(ratio: Double): TrafficKind = when {
            ratio < JAM_BELOW -> TrafficKind.JAM
            ratio < SLOW_BELOW -> TrafficKind.SLOW
            else -> TrafficKind.FLOW
        }

        fun severityFor(kind: TrafficKind): Int = when (kind) {
            TrafficKind.JAM -> 3
            TrafficKind.SLOW -> 1
            else -> 0
        }

        /** 前方経路（rangeM まで）にかかるタイル（x, y）。近い順 */
        fun tilesFor(q: TrafficQuery, rangeM: Double, z: Int = ZOOM): List<IntArray> {
            val seen = LinkedHashSet<Long>()
            val out = ArrayList<IntArray>()
            fun add(la: Double, lo: Double) {
                val x = VectorTile.tileX(z, lo)
                val y = VectorTile.tileY(z, la)
                if (seen.add((x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL))) out.add(intArrayOf(x, y))
            }
            if (q.lat.isEmpty()) return out
            add(q.lat[0], q.lon[0])
            for (i in 0 until q.lat.size - 1) {
                val d0 = q.dist.getOrElse(i) { 0.0 }
                if (d0 >= rangeM || out.size >= MAX_TILES) break
                val d1 = q.dist.getOrElse(i + 1) { d0 }
                val steps = max(1, ceil((d1 - d0) / 50.0).toInt())
                for (j in 1..steps) {
                    val f = j.toDouble() / steps
                    if (d0 + (d1 - d0) * f > rangeM) break
                    add(q.lat[i] + (q.lat[i + 1] - q.lat[i]) * f, q.lon[i] + (q.lon[i + 1] - q.lon[i]) * f)
                }
            }
            return out.take(MAX_TILES)
        }

        /** タイルを読み、経路の近くの流れの悪い区間（線）を交通情報にする。corridor が null なら全部 */
        fun parse(bytes: ByteArray, z: Int, x: Int, y: Int, corridor: RouteCorridor?, stats: Stats? = null): List<TrafficItem> {
            val out = ArrayList<TrafficItem>()
            for (layer in VectorTile.decode(bytes)) {
                stats?.layers?.add(layer.name)
                for (f in layer.features) {
                    if (f.type != VectorTile.TYPE_LINE) continue
                    stats?.let { s -> if (s.keys.size < 40) s.keys.addAll(f.tags.keys) }
                    val closed = f.bool("road_closure") == true
                    val raw = f.number("traffic_level")
                    for (line in f.lines) {
                        stats?.let { it.lines++ }
                        if (raw == null || raw.isNaN()) continue
                        stats?.let { it.withLevel++; it.level(raw) }
                        // relative なら 0〜1。万一 km/h（absolute）で届いたら 80km/h を自由流とみなす
                        val ratio = (if (raw > 1.5) raw / 80.0 else raw).coerceIn(0.0, 1.0)
                        if (closed) stats?.let { it.closures++ }
                        // 通行止めは事故・渋滞の一覧の方（理由・区間の説明つき）を使う。流れは遅い区間だけ
                        if (closed || ratio >= KEEP_BELOW) continue
                        val n = line.size / 2
                        if (n < 2) continue
                        val la = DoubleArray(n) { VectorTile.lat(z, y, line[2 * it + 1].toDouble(), f.extent) }
                        val lo = DoubleArray(n) { VectorTile.lon(z, x, line[2 * it].toDouble(), f.extent) }
                        if (corridor != null && !corridor.lineNear(la, lo, NEAR_M)) continue
                        val kind = kindFor(ratio)
                        stats?.let {
                            it.near++
                            if (kind == TrafficKind.JAM) it.jam++ else if (kind == TrafficKind.SLOW) it.slow++
                        }
                        out.add(TrafficItem(la, lo, kind, ratio.coerceAtLeast(0.03), null, null, null, severityFor(kind), source = TrafficItem.SOURCE_FLOW))
                    }
                }
            }
            return out
        }

        fun short(e: Exception): String = when (e) {
            is HttpStatusException -> "HTTP ${e.code}"
            else -> (e.message ?: e.javaClass.simpleName).take(60)
        }

    }
}
