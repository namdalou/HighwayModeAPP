package com.example.highwaymode.core.hwdb

import com.example.highwaymode.core.osm.OsmArea
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmPoi
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OsmWay
import kotlin.math.roundToLong

/**
 * 1 タイル分の OSM データ（OsmTileData）のコンパクトなバイナリ表現。
 *
 *  - 座標は 1e-7 度（OSM の保存精度）の整数で、直前の点との差分を zigzag 可変長整数で保存
 *  - ノード ID・ウェイ ID も差分で保存（OSM の ID は連番に近いので 1〜3 バイトになる）
 *  - タグは文字列表の番号で保存し、アプリが使うキーだけを残す
 *
 * Overpass の JSON に比べて約 1/15〜1/25 の大きさになり、解析も数十倍速い。
 */
object TileCodec {

    const val VERSION = 1
    private const val SCALE = 1e7

    /** 道路ウェイで使うタグ */
    val ROAD_KEYS = setOf(
        "highway", "name", "name:ja", "ref", "oneway", "junction",
        "maxspeed", "maxspeed:forward", "maxspeed:backward",
        "lanes", "lanes:forward", "lanes:backward",
        "tunnel", "bridge", "toll", "motorroad", "operator",
        "destination", "destination:forward", "destination:backward", "destination:ja",
        "hwm:class"
    )

    /** 出口分岐・料金所・オービスで使うタグ */
    val NODE_KEYS = setOf(
        "highway", "barrier", "name", "name:ja", "official_name", "ref", "exit_to", "destination",
        "payment:cash", "payment:coins", "payment:notes", "payment:etc", "description", "note",
        "maxspeed", "camera:type", "speed_camera:type", "enforcement:type", "type:ja", "direction"
    )

    val AREA_KEYS = setOf("highway", "name", "name:ja", "official_name")

    val POI_KEYS = setOf("amenity", "shop", "tourism", "information", "leisure", "name", "name:ja", "brand", "brand:ja", "opening_hours")

    private fun q(v: Double): Long = (v * SCALE).roundToLong()

    fun encode(t: OsmTileData): ByteArray {
        val strings = LinkedHashMap<String, Int>()
        fun idx(s: String): Int = strings.getOrPut(s) { strings.size }
        // 先に文字列表を作る
        fun collect(tags: Map<String, String>, keys: Set<String>) {
            for ((k, v) in tags) if (k in keys) {
                idx(k)
                idx(v)
            }
        }
        for (w in t.roads) collect(w.tags, ROAD_KEYS)
        for (n in t.junctions) collect(n.tags, NODE_KEYS)
        for (n in t.tollBooths) collect(n.tags, NODE_KEYS)
        for (n in t.cameras) collect(n.tags, NODE_KEYS)
        for (a in t.restAreas) {
            idx(a.key)
            collect(a.tags, AREA_KEYS)
        }
        for (p in t.pois) {
            idx(p.key)
            collect(p.tags, POI_KEYS)
        }

        val out = ByteSink(64 * 1024)
        out.u8(VERSION)
        out.varInt(strings.size)
        for (s in strings.keys) out.string(s)

        fun tags(m: Map<String, String>, keys: Set<String>) {
            var n = 0
            for (k in m.keys) if (k in keys) n++
            out.varInt(n)
            for ((k, v) in m) if (k in keys) {
                out.varInt(strings[k]!!)
                out.varInt(strings[v]!!)
            }
        }

        // ---- ウェイ
        out.varInt(t.roads.size)
        var prevWay = 0L
        var prevNode = 0L
        var pLat = 0L
        var pLon = 0L
        for (w in t.roads) {
            out.zigzag(w.id - prevWay)
            prevWay = w.id
            out.varInt(w.nodeIds.size)
            for (i in w.nodeIds.indices) {
                out.zigzag(w.nodeIds[i] - prevNode)
                prevNode = w.nodeIds[i]
                val la = q(w.lat[i])
                val lo = q(w.lon[i])
                out.zigzag(la - pLat)
                out.zigzag(lo - pLon)
                pLat = la
                pLon = lo
            }
            tags(w.tags, ROAD_KEYS)
        }

        // ---- ノード（出口分岐・料金所・オービス）
        for (list in listOf(t.junctions, t.tollBooths, t.cameras)) {
            out.varInt(list.size)
            var pid = 0L
            for (n in list) {
                out.zigzag(n.id - pid)
                pid = n.id
                val la = q(n.lat)
                val lo = q(n.lon)
                out.zigzag(la - pLat)
                out.zigzag(lo - pLon)
                pLat = la
                pLon = lo
                tags(n.tags, NODE_KEYS)
            }
        }

        // ---- SA/PA 区域
        out.varInt(t.restAreas.size)
        for (a in t.restAreas) {
            out.varInt(strings[a.key]!!)
            out.zigzag(a.id)
            val cla = q(a.centerLat)
            val clo = q(a.centerLon)
            out.zigzag(cla - pLat)
            out.zigzag(clo - pLon)
            pLat = cla
            pLon = clo
            out.varInt(a.rings.size)
            for (r in a.rings) {
                out.varInt(r.lat.size)
                var rla = cla
                var rlo = clo
                for (i in r.lat.indices) {
                    val la = q(r.lat[i])
                    val lo = q(r.lon[i])
                    out.zigzag(la - rla)
                    out.zigzag(lo - rlo)
                    rla = la
                    rlo = lo
                }
            }
            tags(a.tags, AREA_KEYS)
        }

        // ---- 施設 POI
        out.varInt(t.pois.size)
        for (p in t.pois) {
            out.varInt(strings[p.key]!!)
            val la = q(p.lat)
            val lo = q(p.lon)
            out.zigzag(la - pLat)
            out.zigzag(lo - pLon)
            pLat = la
            pLon = lo
            tags(p.tags, POI_KEYS)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): OsmTileData {
        val src = ByteSource(bytes, off, off + len)
        val ver = src.u8()
        if (ver != VERSION) throw ByteSource.FormatException("unsupported tile codec version $ver")
        val ns = src.varInt()
        val strings = Array(ns) { src.string() }

        fun str(i: Int): String {
            if (i < 0 || i >= strings.size) throw ByteSource.FormatException("bad string index")
            return strings[i]
        }

        fun tags(): Map<String, String> {
            val n = src.varInt()
            if (n == 0) return emptyMap()
            val m = HashMap<String, String>(n * 2)
            repeat(n) {
                val k = str(src.varInt())
                m[k] = str(src.varInt())
            }
            return m
        }

        fun coord(prev: Long): Long = prev + src.zigzag()

        val nw = src.varInt()
        val roads = ArrayList<OsmWay>(nw)
        var prevWay = 0L
        var prevNode = 0L
        var pLat = 0L
        var pLon = 0L
        repeat(nw) {
            prevWay += src.zigzag()
            val np = src.varInt()
            if (np > src.remaining) throw ByteSource.FormatException("bad point count")
            val ids = LongArray(np)
            val la = DoubleArray(np)
            val lo = DoubleArray(np)
            for (i in 0 until np) {
                prevNode += src.zigzag()
                ids[i] = prevNode
                pLat = coord(pLat)
                pLon = coord(pLon)
                la[i] = pLat / SCALE
                lo[i] = pLon / SCALE
            }
            roads.add(OsmWay(prevWay, ids, la, lo, tags()))
        }

        val nodeLists = Array(3) { ArrayList<OsmNode>() }
        for (li in 0 until 3) {
            val n = src.varInt()
            var pid = 0L
            repeat(n) {
                pid += src.zigzag()
                pLat = coord(pLat)
                pLon = coord(pLon)
                nodeLists[li].add(OsmNode(pid, pLat / SCALE, pLon / SCALE, tags()))
            }
        }

        val na = src.varInt()
        val areas = ArrayList<OsmArea>(na)
        repeat(na) {
            val key = str(src.varInt())
            val id = src.zigzag()
            pLat = coord(pLat)
            pLon = coord(pLon)
            val cla = pLat
            val clo = pLon
            val nr = src.varInt()
            val rings = ArrayList<OsmArea.Ring>(nr)
            repeat(nr) {
                val np = src.varInt()
                if (np > src.remaining) throw ByteSource.FormatException("bad ring size")
                val la = DoubleArray(np)
                val lo = DoubleArray(np)
                var rla = cla
                var rlo = clo
                for (i in 0 until np) {
                    rla = coord(rla)
                    rlo = coord(rlo)
                    la[i] = rla / SCALE
                    lo[i] = rlo / SCALE
                }
                rings.add(OsmArea.Ring(la, lo))
            }
            areas.add(OsmArea(key, id, cla / SCALE, clo / SCALE, rings, tags()))
        }

        val np = src.varInt()
        val pois = ArrayList<OsmPoi>(np)
        repeat(np) {
            val key = str(src.varInt())
            pLat = coord(pLat)
            pLon = coord(pLon)
            pois.add(OsmPoi(key, pLat / SCALE, pLon / SCALE, tags()))
        }
        return OsmTileData(roads, nodeLists[0], nodeLists[1], areas, pois, nodeLists[2])
    }
}
