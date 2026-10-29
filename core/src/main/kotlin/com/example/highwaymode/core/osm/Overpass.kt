package com.example.highwaymode.core.osm

import com.example.highwaymode.core.json.JsonReader
import java.util.Locale

/**
 * Overpass API（OpenStreetMap）用のクエリ生成とレスポンス解析。
 *
 * 取得対象:
 *  - 高速道路・都市高速（highway=motorway / motorway_link）
 *  - 有料道路（toll=yes の trunk / primary / secondary とそのランプ）
 *  - 自動車専用道路（motorroad=yes の trunk / primary）
 *  - 主要バイパスの候補（分離された国道・ランプ・IC を含む国道。IC の無い一般区間は BypassClassifier で除く）
 *  - 出口分岐点 highway=motorway_junction（IC / JCT / SA・PA 出口）
 *  - 料金所 barrier=toll_booth（ETC 専用判定にも使用）
 *  - SA / PA（highway=services / rest_area）と、その周辺の施設 POI
 */
object OverpassQuery {

    fun forTile(tile: TileId): String = forBBox(tile.south, tile.west, tile.north, tile.east)

    fun forBBox(south: Double, west: Double, north: Double, east: Double, timeoutSec: Int = 90): String {
        val bb = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", south, west, north, east)
        return build("($bb)", "($bb)", timeoutSec)
    }

    /**
     * 都道府県単位（ISO3166-2 コード例: "JP-13"）の一括取得。全国データ作成用。
     */
    fun forPrefecture(iso: String, timeoutSec: Int = 600): String {
        val head = "area[\"ISO3166-2\"=\"$iso\"][\"admin_level\"=\"4\"]->.pref;\n"
        return build("(area.pref)", "(area.pref)", timeoutSec, head, maxsizeMb = 1024)
    }

    private fun build(roadFilter: String, areaFilter: String, timeoutSec: Int, head: String = "", maxsizeMb: Int = 512): String {
        val sb = StringBuilder()
        sb.append("[out:json][timeout:").append(timeoutSec).append("][maxsize:").append(maxsizeMb.toLong() * 1024 * 1024).append("];\n")
        sb.append(head)
        sb.append("node[\"highway\"=\"motorway_junction\"]").append(roadFilter).append("->.mj;\n")
        sb.append("(\n")
        sb.append("  way[\"highway\"~\"^(motorway|motorway_link)$\"]").append(roadFilter).append(";\n")
        sb.append("  way[\"highway\"~\"^(trunk|trunk_link|primary|primary_link|secondary|secondary_link)$\"][\"toll\"=\"yes\"]").append(roadFilter).append(";\n")
        sb.append("  way[\"highway\"~\"^(trunk|trunk_link|primary|primary_link)$\"][\"motorroad\"=\"yes\"]").append(roadFilter).append(";\n")
        // 主要バイパス（IC のある一般国道）の候補: 分離 4 車線の国道・ランプ・IC を含む国道・名称がバイパスの道路
        sb.append("  way[\"highway\"=\"trunk\"][\"oneway\"~\"^(yes|-1)$\"]").append(roadFilter).append(";\n")
        sb.append("  way[\"highway\"=\"trunk_link\"]").append(roadFilter).append(";\n")
        sb.append("  way(bn.mj)[\"highway\"~\"^(trunk|primary)$\"];\n")
        sb.append("  way[\"highway\"~\"^(trunk|primary)$\"][\"name\"~\"(バイパス|道路|名阪国道)$\"]").append(roadFilter).append(";\n")
        sb.append(")->.r;\n")
        sb.append(".r out body geom qt;\n")
        sb.append("node(w.r)[\"highway\"=\"motorway_junction\"];\nout body qt;\n")
        sb.append("node(w.r)[\"barrier\"=\"toll_booth\"];\nout body qt;\n")
        sb.append("node[\"highway\"=\"speed_camera\"]").append(areaFilter).append(";\nout body qt;\n")
        sb.append("nwr[\"highway\"~\"^(services|rest_area)$\"]").append(areaFilter).append("->.s;\n")
        sb.append(".s out body geom qt;\n")
        sb.append("(\n")
        sb.append("  nwr(around.s:150)[\"amenity\"~\"^(fuel|charging_station|restaurant|fast_food|food_court|cafe|toilets|shower|atm|information|bank)$\"];\n")
        sb.append("  nwr(around.s:150)[\"shop\"];\n")
        sb.append("  nwr(around.s:150)[\"tourism\"~\"^(information|hotel|motel|guest_house)$\"];\n")
        sb.append("  nwr(around.s:150)[\"leisure\"=\"dog_park\"];\n")
        sb.append(");\nout tags center qt;\n")
        return sb.toString()
    }
}

class OverpassException(message: String) : Exception(message)

object OverpassParser {

    val ROAD_HIGHWAYS = setOf(
        "motorway", "motorway_link", "trunk", "trunk_link",
        "primary", "primary_link", "secondary", "secondary_link"
    )

    private val POI_AMENITIES = setOf(
        "fuel", "charging_station", "restaurant", "fast_food", "food_court", "cafe",
        "toilets", "shower", "atm", "information", "bank"
    )

    /** @throws OverpassException サーバー側エラー（タイムアウト等）の場合 */
    fun parse(json: String): OsmTileData = parse(JsonReader(json))

    /** HTTP 応答などを文字列にせず逐次解析する（都道府県単位の大きな応答向け） */
    fun parse(reader: java.io.Reader): OsmTileData = parse(JsonReader(reader))

    private fun parse(r: JsonReader): OsmTileData {
        val roads = ArrayList<OsmWay>()
        val junctions = ArrayList<OsmNode>()
        val tolls = ArrayList<OsmNode>()
        val areas = ArrayList<OsmArea>()
        val pois = ArrayList<OsmPoi>()
        val cameras = ArrayList<OsmNode>()
        var remark: String? = null
        var sawElements = false

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "elements" -> {
                    sawElements = true
                    r.beginArray()
                    while (r.hasNext()) {
                        readElement(r, roads, junctions, tolls, areas, pois, cameras)
                    }
                    r.endArray()
                }
                "remark" -> remark = if (r.peek() == JsonReader.Token.NULL) {
                    r.nextNull(); null
                } else r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()

        val rm = remark
        if (rm != null) {
            val low = rm.lowercase(Locale.ROOT)
            if ("error" in low || "timed out" in low || "out of memory" in low) {
                throw OverpassException(rm)
            }
        }
        if (!sawElements) throw OverpassException("elements がありません")
        return OsmTileData(roads, junctions, tolls, areas, pois, cameras)
    }

    private class Member(val type: String, val role: String, val lat: DoubleArray, val lon: DoubleArray)

    private fun readElement(
        r: JsonReader,
        roads: MutableList<OsmWay>,
        junctions: MutableList<OsmNode>,
        tolls: MutableList<OsmNode>,
        areas: MutableList<OsmArea>,
        pois: MutableList<OsmPoi>,
        cameras: MutableList<OsmNode>
    ) {
        var type = ""
        var id = 0L
        var lat = Double.NaN
        var lon = Double.NaN
        var cLat = Double.NaN
        var cLon = Double.NaN
        var nodeIds: LongArray? = null
        var gLat: DoubleArray? = null
        var gLon: DoubleArray? = null
        var geomHasNull = false
        var tags: Map<String, String> = emptyMap()
        var members: List<Member> = emptyList()

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "id" -> id = r.nextLong()
                "lat" -> lat = r.nextDouble()
                "lon" -> lon = r.nextDouble()
                "center" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "lat" -> cLat = r.nextDouble()
                            "lon" -> cLon = r.nextDouble()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                "nodes" -> nodeIds = readLongArray(r)
                "geometry" -> {
                    val g = readGeometry(r)
                    gLat = g.first
                    gLon = g.second
                    geomHasNull = g.third
                }
                "tags" -> tags = readTags(r)
                "members" -> members = readMembers(r)
                else -> r.skipValue()
            }
        }
        r.endObject()

        val highway = tags["highway"]
        when {
            type == "way" && highway != null && highway in ROAD_HIGHWAYS -> {
                val ids = nodeIds
                val la = gLat
                val lo = gLon
                if (ids != null && la != null && lo != null && !geomHasNull &&
                    ids.size == la.size && ids.size >= 2
                ) {
                    roads.add(OsmWay(id, ids, la, lo, tags))
                }
            }
            highway == "services" || highway == "rest_area" -> {
                val key = type.take(1) + id
                when (type) {
                    "node" -> if (!lat.isNaN()) areas.add(OsmArea(key, id, lat, lon, emptyList(), tags))
                    "way" -> {
                        val la = gLat
                        val lo = gLon
                        if (la != null && lo != null && la.isNotEmpty() && !geomHasNull) {
                            val ring = OsmArea.Ring(la, lo)
                            val ctrLat = if (!cLat.isNaN()) cLat else la.average()
                            val ctrLon = if (!cLon.isNaN()) cLon else lo.average()
                            areas.add(OsmArea(key, id, ctrLat, ctrLon, listOf(ring), tags))
                        }
                    }
                    "relation" -> {
                        val rings = members.filter { it.type == "way" && (it.role == "outer" || it.role.isEmpty()) && it.lat.isNotEmpty() }
                            .map { OsmArea.Ring(it.lat, it.lon) }
                        if (rings.isNotEmpty() || !cLat.isNaN()) {
                            val ctrLat = if (!cLat.isNaN()) cLat else rings.flatMap { it.lat.asList() }.average()
                            val ctrLon = if (!cLon.isNaN()) cLon else rings.flatMap { it.lon.asList() }.average()
                            areas.add(OsmArea(key, id, ctrLat, ctrLon, rings, tags))
                        }
                    }
                }
            }
            type == "node" && highway == "motorway_junction" ->
                if (!lat.isNaN()) junctions.add(OsmNode(id, lat, lon, tags))
            type == "node" && highway == "speed_camera" ->
                if (!lat.isNaN()) cameras.add(OsmNode(id, lat, lon, tags))
            type == "node" && tags["barrier"] == "toll_booth" ->
                if (!lat.isNaN()) tolls.add(OsmNode(id, lat, lon, tags))
            isPoi(tags) -> {
                val pLat = if (!lat.isNaN()) lat else cLat
                val pLon = if (!lon.isNaN()) lon else cLon
                if (!pLat.isNaN() && !pLon.isNaN()) pois.add(OsmPoi(type.take(1) + id, pLat, pLon, tags))
            }
        }
    }

    private fun isPoi(tags: Map<String, String>): Boolean {
        val a = tags["amenity"]
        if (a != null && a in POI_AMENITIES) return true
        if (tags.containsKey("shop")) return true
        val t = tags["tourism"]
        if (t == "information" || t == "hotel" || t == "motel" || t == "guest_house") return true
        return tags["leisure"] == "dog_park"
    }

    private fun readLongArray(r: JsonReader): LongArray {
        var arr = LongArray(64)
        var n = 0
        r.beginArray()
        while (r.hasNext()) {
            if (n == arr.size) arr = arr.copyOf(n * 2)
            arr[n++] = r.nextLong()
        }
        r.endArray()
        return arr.copyOf(n)
    }

    private fun readGeometry(r: JsonReader): Triple<DoubleArray, DoubleArray, Boolean> {
        var la = DoubleArray(64)
        var lo = DoubleArray(64)
        var n = 0
        var hasNull = false
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() == JsonReader.Token.NULL) {
                r.nextNull()
                hasNull = true
                continue
            }
            var pLat = Double.NaN
            var pLon = Double.NaN
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "lat" -> pLat = r.nextDouble()
                    "lon" -> pLon = r.nextDouble()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (n == la.size) {
                la = la.copyOf(n * 2)
                lo = lo.copyOf(n * 2)
            }
            la[n] = pLat
            lo[n] = pLon
            n++
        }
        r.endArray()
        return Triple(la.copyOf(n), lo.copyOf(n), hasNull)
    }

    private fun readTags(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>(16)
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            if (r.peek() == JsonReader.Token.NULL) {
                r.nextNull()
            } else {
                m[k] = r.nextString()
            }
        }
        r.endObject()
        return m
    }

    private fun readMembers(r: JsonReader): List<Member> {
        val out = ArrayList<Member>()
        r.beginArray()
        while (r.hasNext()) {
            var t = ""
            var role = ""
            var la = DoubleArray(0)
            var lo = DoubleArray(0)
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "type" -> t = r.nextString()
                    "role" -> role = r.nextString()
                    "geometry" -> {
                        val g = readGeometry(r)
                        if (!g.third) {
                            la = g.first
                            lo = g.second
                        }
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            out.add(Member(t, role, la, lo))
        }
        r.endArray()
        return out
    }
}
