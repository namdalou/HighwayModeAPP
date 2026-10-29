package com.example.highwaymode.core.osm

import kotlin.math.floor

/** 道路ウェイ（ノード ID 列と座標列は同じ長さ） */
class OsmWay(
    val id: Long,
    val nodeIds: LongArray,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val tags: Map<String, String>
)

class OsmNode(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>
)

/**
 * SA/PA（highway=services / rest_area）。
 * ノードの場合は rings が空、ウェイ・リレーションの場合は外周リング（閉じていない場合もある）。
 */
class OsmArea(
    val key: String,
    val id: Long,
    val centerLat: Double,
    val centerLon: Double,
    val rings: List<Ring>,
    val tags: Map<String, String>
) {
    class Ring(val lat: DoubleArray, val lon: DoubleArray) {
        val closed: Boolean
            get() = lat.size >= 4 && lat[0] == lat[lat.size - 1] && lon[0] == lon[lon.size - 1]
    }

    /** 点がポリゴン内か（閉じたリングのみ判定、奇偶規則） */
    fun contains(lat: Double, lon: Double): Boolean {
        var inside = false
        for (r in rings) {
            if (!r.closed) continue
            var j = r.lat.size - 1
            for (i in r.lat.indices) {
                val yi = r.lat[i]
                val yj = r.lat[j]
                if ((yi > lat) != (yj > lat)) {
                    val xi = r.lon[i]
                    val xj = r.lon[j]
                    val xCross = xi + (lat - yi) * (xj - xi) / (yj - yi)
                    if (lon < xCross) inside = !inside
                }
                j = i
            }
        }
        return inside
    }
}

/** 施設内の POI（給油所、レストラン、トイレ等） */
class OsmPoi(
    val key: String,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>
)

/** 1 タイル分の取得結果 */
class OsmTileData(
    val roads: List<OsmWay>,
    val junctions: List<OsmNode>,
    val tollBooths: List<OsmNode>,
    val restAreas: List<OsmArea>,
    val pois: List<OsmPoi>,
    /** 速度取締機（オービス）highway=speed_camera */
    val cameras: List<OsmNode> = emptyList()
) {
    val isEmpty: Boolean get() = roads.isEmpty()
}

/** 複数タイルを重複排除して統合したデータ */
class OsmDataSet(
    val roads: Map<Long, OsmWay>,
    val junctions: Map<Long, OsmNode>,
    val tollBooths: Map<Long, OsmNode>,
    val restAreas: Map<String, OsmArea>,
    val pois: Map<String, OsmPoi>,
    val cameras: Map<Long, OsmNode> = emptyMap()
) {
    companion object {
        /**
         * @param extraCameras 利用者が登録したオービスなど、タイルとは別に加える取締機
         */
        fun merge(tiles: Collection<OsmTileData>, extraCameras: Collection<OsmNode> = emptyList()): OsmDataSet {
            val roads = LinkedHashMap<Long, OsmWay>()
            val junctions = HashMap<Long, OsmNode>()
            val tolls = HashMap<Long, OsmNode>()
            val areas = LinkedHashMap<String, OsmArea>()
            val pois = LinkedHashMap<String, OsmPoi>()
            val cams = LinkedHashMap<Long, OsmNode>()
            for (t in tiles) {
                for (w in t.roads) roads.putIfAbsent(w.id, w)
                for (n in t.junctions) junctions.putIfAbsent(n.id, n)
                for (n in t.tollBooths) tolls.putIfAbsent(n.id, n)
                for (a in t.restAreas) areas.putIfAbsent(a.key, a)
                for (p in t.pois) pois.putIfAbsent(p.key, p)
                for (c in t.cameras) cams.putIfAbsent(c.id, c)
            }
            for (c in extraCameras) cams.putIfAbsent(c.id, c)
            return OsmDataSet(roads, junctions, tolls, areas, pois, cams)
        }
    }
}

/**
 * データ取得単位のタイル（緯度 0.2° × 経度 0.25° ≒ 22km × 23km）。
 */
data class TileId(val iy: Int, val ix: Int) {
    val south: Double get() = LAT0 + iy * LAT_STEP
    val north: Double get() = south + LAT_STEP
    val west: Double get() = LON0 + ix * LON_STEP
    val east: Double get() = west + LON_STEP
    val centerLat: Double get() = south + LAT_STEP / 2
    val centerLon: Double get() = west + LON_STEP / 2

    fun key(): String = "${iy}_$ix"

    fun contains(lat: Double, lon: Double): Boolean =
        lat >= south && lat < north && lon >= west && lon < east

    /** 日本の範囲内か（範囲外のタイルは取得しない） */
    val inJapan: Boolean
        get() = north > 20.0 && south < 46.0 && east > 122.5 && west < 154.5

    companion object {
        const val LAT0 = 20.0
        const val LON0 = 120.0
        const val LAT_STEP = 0.2
        const val LON_STEP = 0.25

        fun of(lat: Double, lon: Double): TileId =
            TileId(floor((lat - LAT0) / LAT_STEP).toInt(), floor((lon - LON0) / LON_STEP).toInt())

        fun parse(key: String): TileId? {
            val p = key.split('_')
            if (p.size != 2) return null
            val a = p[0].toIntOrNull() ?: return null
            val b = p[1].toIntOrNull() ?: return null
            return TileId(a, b)
        }
    }
}
