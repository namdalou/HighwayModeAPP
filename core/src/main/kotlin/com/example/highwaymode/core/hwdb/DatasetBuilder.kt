package com.example.highwaymode.core.hwdb

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.osm.OsmArea
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmPoi
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OsmWay
import com.example.highwaymode.core.osm.TileId
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 都道府県単位などの大きな取得結果を集め、タイルに分割して HWDB に書き出す。
 *
 * タイルの中身は Overpass でそのタイルの範囲を取得した場合と同じになるようにする:
 *  - ウェイは通過するすべてのタイルに入れる（全形状つき）
 *  - そのウェイ上の出口分岐・料金所はタイル外でも同じタイルに入れる
 *  - SA/PA 区域は中心と外周が入るタイルに、周辺 POI はその区域と同じタイルに入れる
 */
class DatasetBuilder {
    private val roads = HashMap<Long, OsmWay>()
    private val junctions = HashMap<Long, OsmNode>()
    private val tolls = HashMap<Long, OsmNode>()
    private val cameras = HashMap<Long, OsmNode>()
    private val areas = HashMap<String, OsmArea>()
    private val pois = HashMap<String, OsmPoi>()

    val roadCount: Int get() = roads.size
    val nodeCount: Int get() = roads.values.sumOf { it.nodeIds.size }
    val restAreaCount: Int get() = areas.size
    val cameraCount: Int get() = cameras.size
    val junctionCount: Int get() = junctions.size

    @Synchronized
    fun add(d: OsmTileData) {
        for (w in d.roads) roads.putIfAbsent(w.id, w)
        for (n in d.junctions) junctions.putIfAbsent(n.id, n)
        for (n in d.tollBooths) tolls.putIfAbsent(n.id, n)
        for (n in d.cameras) cameras.putIfAbsent(n.id, n)
        for (a in d.restAreas) areas.putIfAbsent(a.key, a)
        for (p in d.pois) pois.putIfAbsent(p.key, p)
    }

    /**
     * タイルに分割する。complete に含まれるタイルは、データが無くても空タイルとして返す。
     * complete に含まれないがデータのあるタイルも返す（呼び出し側で「一部のみ」として記録する）。
     */
    @Synchronized
    fun splitTiles(complete: Set<TileId>?): Map<TileId, OsmTileData> {
        class Acc {
            val roads = LinkedHashMap<Long, OsmWay>()
            val junctions = LinkedHashMap<Long, OsmNode>()
            val tolls = LinkedHashMap<Long, OsmNode>()
            val cameras = LinkedHashMap<Long, OsmNode>()
            val areas = LinkedHashMap<String, OsmArea>()
            val pois = LinkedHashMap<String, OsmPoi>()
        }
        val acc = HashMap<TileId, Acc>()
        fun at(t: TileId): Acc? {
            if (!t.inJapan) return null
            return acc.getOrPut(t) { Acc() }
        }

        for (w in roads.values.sortedBy { it.id }) {
            val ts = tilesOfLine(w.lat, w.lon)
            for (t in ts) {
                val a = at(t) ?: continue
                a.roads[w.id] = w
                for (nid in w.nodeIds) {
                    junctions[nid]?.let { a.junctions[nid] = it }
                    tolls[nid]?.let { a.tolls[nid] = it }
                    cameras[nid]?.let { a.cameras[nid] = it }
                }
            }
        }
        for (c in cameras.values) at(TileId.of(c.lat, c.lon))?.cameras?.put(c.id, c)

        // POI を区域ごとに割り当てる（格子で近傍検索）
        val poiGrid = HashMap<Long, MutableList<OsmPoi>>()
        fun key(y: Int, x: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)
        fun cy(lat: Double): Int = floor(lat * 200).toInt()
        fun cx(lon: Double): Int = floor(lon * 200).toInt()
        for (p in pois.values) poiGrid.getOrPut(key(cy(p.lat), cx(p.lon))) { ArrayList() }.add(p)

        for (a in areas.values.sortedBy { it.key }) {
            val ts = LinkedHashSet<TileId>()
            ts.add(TileId.of(a.centerLat, a.centerLon))
            for (r in a.rings) ts.addAll(tilesOfLine(r.lat, r.lon))
            var radius = 150.0
            for (r in a.rings) for (i in r.lat.indices) radius = max(radius, Geo.distance(a.centerLat, a.centerLon, r.lat[i], r.lon[i]) + 150.0)
            radius = radius.coerceAtMost(800.0)
            val near = ArrayList<OsmPoi>()
            val y0 = cy(a.centerLat)
            val x0 = cx(a.centerLon)
            for (dy in -2..2) for (dx in -2..2) {
                val list = poiGrid[key(y0 + dy, x0 + dx)] ?: continue
                for (p in list) if (Geo.distance(p.lat, p.lon, a.centerLat, a.centerLon) <= radius) near.add(p)
            }
            for (t in ts) {
                val x = at(t) ?: continue
                x.areas[a.key] = a
                for (p in near) x.pois[p.key] = p
            }
        }

        val out = LinkedHashMap<TileId, OsmTileData>()
        if (complete != null) for (t in complete) out[t] = HwdbFile.EMPTY
        for ((t, a) in acc) {
            out[t] = OsmTileData(
                a.roads.values.toList(), a.junctions.values.toList(), a.tolls.values.toList(),
                a.areas.values.toList(), a.pois.values.toList(), a.cameras.values.toList()
            )
        }
        return out
    }

    /**
     * HWDB に書き出す。
     * @param prefectures 取得した都道府県（完全なタイルの判定に使う）。null なら取得結果のあるタイルをすべて書く
     */
    fun write(file: File, prefectures: List<Prefectures.Pref>?, extraMeta: Map<String, String> = emptyMap()): Map<String, String> {
        val complete = prefectures?.let { Prefectures.completeTiles(it) }
        val tiles = splitTiles(complete)
        val all = prefectures != null && prefectures.size == Prefectures.ALL.size
        // 全国分なら離島などの範囲外タイルも完全とみなす
        val partial = if (complete == null || all) emptySet() else tiles.keys.filter { it !in complete }.toSet()
        val now = Date()
        val meta = LinkedHashMap<String, String>()
        meta[HwdbFile.META_DATA_VERSION] = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Tokyo") }.format(now)
        meta[HwdbFile.META_CREATED_AT] = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        meta[HwdbFile.META_COVERAGE] = if (all) "all" else "partial"
        if (prefectures != null) meta[HwdbFile.META_PREFECTURES] = prefectures.joinToString(",") { it.code }
        meta[HwdbFile.META_SOURCE] = "OpenStreetMap"
        meta[HwdbFile.META_LICENSE] = "© OpenStreetMap contributors, ODbL 1.0"
        meta["roads"] = roadCount.toString()
        meta["restAreas"] = restAreaCount.toString()
        meta["cameras"] = cameraCount.toString()
        meta.putAll(extraMeta)
        meta["partialTiles"] = partial.size.toString()
        HwdbFile.write(file, meta, tiles, partial)
        return meta
    }

    companion object {
        /** 折れ線が通るタイル（長い線分は途中も調べる） */
        fun tilesOfLine(lat: DoubleArray, lon: DoubleArray): Set<TileId> {
            val out = LinkedHashSet<TileId>()
            for (i in lat.indices) {
                out.add(TileId.of(lat[i], lon[i]))
                if (i > 0) {
                    val dLat = abs(lat[i] - lat[i - 1])
                    val dLon = abs(lon[i] - lon[i - 1])
                    val steps = ceil(max(dLat / (TileId.LAT_STEP / 8), dLon / (TileId.LON_STEP / 8))).toInt()
                    for (k in 1 until steps) {
                        val f = k.toDouble() / steps
                        out.add(TileId.of(lat[i - 1] + (lat[i] - lat[i - 1]) * f, lon[i - 1] + (lon[i] - lon[i - 1]) * f))
                    }
                }
            }
            return out
        }
    }
}
