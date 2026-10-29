package com.example.highwaymode.datatool

import com.example.highwaymode.core.hwdb.TileCodec
import com.example.highwaymode.core.osm.OsmArea
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmPoi
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OsmWay
import com.example.highwaymode.core.osm.OverpassParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * osmium で絞り込んだ OSM XML（ウェイのノードに座標つき: locations_on_ways=true）を読み込む。
 *
 *   osmium tags-filter japan-latest.osm.pbf w/highway=motorway,motorway_link,trunk,trunk_link ... -o filtered.osm.pbf
 *   osmium add-locations-to-ways filtered.osm.pbf -o located.osm.gz -f osm,locations_on_ways=true
 *
 * 1 回目の走査で SA/PA のマルチポリゴン（リレーション）の外周ウェイを調べ、2 回目で全体を読む。
 */
class OsmXmlLoader(private val file: File, private val log: (String) -> Unit = { println(it) }) {

    private val poiAmenities = setOf("fuel", "charging_station", "restaurant", "fast_food", "food_court", "cafe", "toilets", "shower", "atm", "information", "bank")

    private fun open(): InputStream {
        val raw = BufferedInputStream(FileInputStream(file), 1 shl 20)
        // 拡張子ではなく先頭のマジックナンバーで gzip を判定
        raw.mark(2)
        val b0 = raw.read()
        val b1 = raw.read()
        raw.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 1 shl 20) else raw
    }

    private fun reader(input: InputStream): XMLStreamReader {
        val f = XMLInputFactory.newInstance()
        f.setProperty(XMLInputFactory.IS_COALESCING, false)
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        f.setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        return f.createXMLStreamReader(input, "UTF-8")
    }

    fun load(): OsmTileData {
        // ---- 1 回目: SA/PA リレーションの外周ウェイ
        val memberOf = HashMap<Long, Long>()
        val relTags = HashMap<Long, Map<String, String>>()
        open().use { input ->
            val r = reader(input)
            var relId = 0L
            var inRel = false
            val members = ArrayList<Pair<Long, String>>()
            var tags = HashMap<String, String>()
            while (r.hasNext()) {
                val ev = r.next()
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    when (r.localName) {
                        "relation" -> {
                            inRel = true
                            relId = r.getAttributeValue(null, "id").toLong()
                            members.clear()
                            tags = HashMap()
                        }
                        "member" -> if (inRel && r.getAttributeValue(null, "type") == "way") {
                            members.add(r.getAttributeValue(null, "ref").toLong() to (r.getAttributeValue(null, "role") ?: ""))
                        }
                        "tag" -> if (inRel) tags[r.getAttributeValue(null, "k")] = r.getAttributeValue(null, "v")
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && r.localName == "relation") {
                    inRel = false
                    val hw = tags["highway"]
                    if (hw == "services" || hw == "rest_area") {
                        relTags[relId] = tags
                        for ((wid, role) in members) if (role == "outer" || role.isEmpty()) memberOf[wid] = relId
                    }
                }
            }
            r.close()
        }
        log("SA/PA リレーション: ${relTags.size}")

        // ---- 2 回目: 全体
        val roads = ArrayList<OsmWay>()
        val junctions = ArrayList<OsmNode>()
        val tolls = ArrayList<OsmNode>()
        val cameras = ArrayList<OsmNode>()
        val areas = ArrayList<OsmArea>()
        val pois = ArrayList<OsmPoi>()
        val memberGeom = HashMap<Long, Pair<DoubleArray, DoubleArray>>()
        var nodes = 0L
        var ways = 0L
        open().use { input ->
            val r = reader(input)
            var kind = ""
            var id = 0L
            var lat = Double.NaN
            var lon = Double.NaN
            var tags = HashMap<String, String>()
            var ids = LongArrayList()
            var la = DoubleArrayList()
            var lo = DoubleArrayList()
            while (r.hasNext()) {
                val ev = r.next()
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    when (r.localName) {
                        "node" -> {
                            kind = "node"
                            id = r.getAttributeValue(null, "id").toLong()
                            lat = r.getAttributeValue(null, "lat")?.toDouble() ?: Double.NaN
                            lon = r.getAttributeValue(null, "lon")?.toDouble() ?: Double.NaN
                            tags = HashMap()
                        }
                        "way" -> {
                            kind = "way"
                            id = r.getAttributeValue(null, "id").toLong()
                            tags = HashMap()
                            ids = LongArrayList()
                            la = DoubleArrayList()
                            lo = DoubleArrayList()
                        }
                        "relation" -> kind = "relation"
                        "nd" -> if (kind == "way") {
                            ids.add(r.getAttributeValue(null, "ref").toLong())
                            la.add(r.getAttributeValue(null, "lat")?.toDouble() ?: Double.NaN)
                            lo.add(r.getAttributeValue(null, "lon")?.toDouble() ?: Double.NaN)
                        }
                        "tag" -> if (kind == "node" || kind == "way") tags[r.getAttributeValue(null, "k")] = r.getAttributeValue(null, "v")
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    when (r.localName) {
                        "node" -> {
                            nodes++
                            if (tags.isNotEmpty() && !lat.isNaN()) handleNode(id, lat, lon, tags, junctions, tolls, cameras, areas, pois)
                            kind = ""
                        }
                        "way" -> {
                            ways++
                            val laA = la.toArray()
                            val loA = lo.toArray()
                            val complete = laA.none { it.isNaN() } && loA.none { it.isNaN() }
                            if (complete) {
                                if (memberOf.containsKey(id)) memberGeom[id] = laA to loA
                                handleWay(id, ids.toArray(), laA, loA, tags, roads, areas, pois)
                            }
                            kind = ""
                        }
                        "relation" -> kind = ""
                    }
                    if (nodes > 0 && nodes % 5_000_000 == 0L && r.localName == "node") log("  ノード ${nodes / 1_000_000}M")
                }
            }
            r.close()
        }
        log("読み込み: ノード $nodes, ウェイ $ways → 道路 ${roads.size}, 出口分岐 ${junctions.size}, 料金所 ${tolls.size}, オービス ${cameras.size}, SA/PA ${areas.size}, POI 候補 ${pois.size}")

        // ---- SA/PA リレーションを組み立てる
        val relMembers = HashMap<Long, MutableList<Pair<DoubleArray, DoubleArray>>>()
        for ((wid, rid) in memberOf) memberGeom[wid]?.let { relMembers.getOrPut(rid) { ArrayList() }.add(it) }
        for ((rid, parts) in relMembers) {
            val rings = joinRings(parts)
            if (rings.isEmpty()) continue
            val allLa = rings.flatMap { it.lat.asList() }
            val allLo = rings.flatMap { it.lon.asList() }
            areas.add(OsmArea("r$rid", rid, allLa.average(), allLo.average(), rings, relTags[rid] ?: emptyMap()))
        }
        return OsmTileData(roads, junctions, tolls, areas, pois, cameras)
    }

    private fun handleNode(
        id: Long, lat: Double, lon: Double, tags: Map<String, String>,
        junctions: MutableList<OsmNode>, tolls: MutableList<OsmNode>, cameras: MutableList<OsmNode>,
        areas: MutableList<OsmArea>, pois: MutableList<OsmPoi>
    ) {
        val hw = tags["highway"]
        when {
            hw == "motorway_junction" -> junctions.add(OsmNode(id, lat, lon, slim(tags, TileCodec.NODE_KEYS)))
            hw == "speed_camera" -> cameras.add(OsmNode(id, lat, lon, slim(tags, TileCodec.NODE_KEYS)))
            tags["barrier"] == "toll_booth" -> tolls.add(OsmNode(id, lat, lon, slim(tags, TileCodec.NODE_KEYS)))
            hw == "services" || hw == "rest_area" -> areas.add(OsmArea("n$id", id, lat, lon, emptyList(), slim(tags, TileCodec.AREA_KEYS)))
            isPoi(tags) -> pois.add(OsmPoi("n$id", lat, lon, slim(tags, TileCodec.POI_KEYS)))
        }
    }

    private fun handleWay(
        id: Long, ids: LongArray, la: DoubleArray, lo: DoubleArray, tags: Map<String, String>,
        roads: MutableList<OsmWay>, areas: MutableList<OsmArea>, pois: MutableList<OsmPoi>
    ) {
        val hw = tags["highway"]
        when {
            hw != null && hw in OverpassParser.ROAD_HIGHWAYS && ids.size >= 2 -> {
                // 一般の主要地方道・県道は有料・自動車専用のものだけ（国道は BypassClassifier で判定）
                val minor = hw.startsWith("primary") || hw.startsWith("secondary")
                if (minor && tags["toll"] != "yes" && tags["motorroad"] != "yes") return
                roads.add(OsmWay(id, ids, la, lo, slim(tags, TileCodec.ROAD_KEYS)))
            }
            (hw == "services" || hw == "rest_area") && la.isNotEmpty() ->
                areas.add(OsmArea("w$id", id, la.average(), lo.average(), listOf(OsmArea.Ring(la, lo)), slim(tags, TileCodec.AREA_KEYS)))
            isPoi(tags) && la.isNotEmpty() -> pois.add(OsmPoi("w$id", la.average(), lo.average(), slim(tags, TileCodec.POI_KEYS)))
        }
    }

    private fun isPoi(tags: Map<String, String>): Boolean {
        val a = tags["amenity"]
        if (a != null && a in poiAmenities) return true
        val shop = tags["shop"]
        if (shop != null && shop != "vacant" && shop != "no") return true
        val t = tags["tourism"]
        if (t == "information" || t == "hotel" || t == "motel" || t == "guest_house") return true
        return tags["leisure"] == "dog_park"
    }

    private fun slim(tags: Map<String, String>, keys: Set<String>): Map<String, String> {
        val m = HashMap<String, String>(8)
        for ((k, v) in tags) if (k in keys) m[k.intern()] = v.intern()
        return m
    }

    /** 外周ウェイの断片を端点でつないで閉じたリングにする */
    private fun joinRings(parts: List<Pair<DoubleArray, DoubleArray>>): List<OsmArea.Ring> {
        val pending = parts.map { it.first.toMutableList() to it.second.toMutableList() }.toMutableList()
        val rings = ArrayList<OsmArea.Ring>()
        fun same(a1: Double, o1: Double, a2: Double, o2: Double) = a1 == a2 && o1 == o2
        while (pending.isNotEmpty()) {
            val (la, lo) = pending.removeAt(0)
            var progress = true
            while (!same(la.first(), lo.first(), la.last(), lo.last()) && progress) {
                progress = false
                val it = pending.iterator()
                while (it.hasNext()) {
                    val (pa, po) = it.next()
                    when {
                        same(la.last(), lo.last(), pa.first(), po.first()) -> {
                            la.addAll(pa.drop(1)); lo.addAll(po.drop(1))
                        }
                        same(la.last(), lo.last(), pa.last(), po.last()) -> {
                            la.addAll(pa.reversed().drop(1)); lo.addAll(po.reversed().drop(1))
                        }
                        else -> continue
                    }
                    it.remove()
                    progress = true
                    break
                }
            }
            rings.add(OsmArea.Ring(la.toDoubleArray(), lo.toDoubleArray()))
        }
        return rings
    }

    private class LongArrayList {
        private var a = LongArray(16)
        private var n = 0
        fun add(v: Long) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray(): LongArray = a.copyOf(n)
    }

    private class DoubleArrayList {
        private var a = DoubleArray(16)
        private var n = 0
        fun add(v: Double) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray(): DoubleArray = a.copyOf(n)
    }
}
