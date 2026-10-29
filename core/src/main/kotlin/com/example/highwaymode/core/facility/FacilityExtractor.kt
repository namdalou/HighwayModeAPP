package com.example.highwaymode.core.facility

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.Edge
import com.example.highwaymode.core.graph.EdgeProjection
import com.example.highwaymode.core.graph.FacilityRefName
import com.example.highwaymode.core.graph.RestAreaRef
import com.example.highwaymode.core.graph.RoadAttr
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.osm.OsmArea
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmNode
import kotlin.math.abs
import kotlin.math.min

/**
 * OSM データから IC / JCT / SA / PA / スマート IC / 本線料金所 / オービスを抽出し、
 * 道路グラフの有向エッジ上に位置づける（＝どの進行方向から利用できるかを確定する）。
 *
 * 主な判定:
 *  - motorway_junction（出口分岐点）はその車線（片方向エッジ）上にのみ存在するため、進行方向別に正しく付く。
 *  - 名称にサフィックスが無い場合は、出口ランプを辿って「別路線に合流→JCT」「SA/PA 区域に入る→SA/PA」
 *    「高速道路網の外へ出る→IC」と推定する。名称が無い分岐は、反対車線の同じ IC 名・案内方面・ランプ名で補う。
 *  - motorway_junction が登録されていない出口（片方向だけ登録されている IC 等）も、
 *    本線からランプが分かれる地点を「推定出口」として拾う。
 *  - SA/PA 区域は (1) 進行方向の左側にある (2) 区域が本線にかかっている (3) その車線から分かれるランプが区域に入る
 *    のいずれかで、その方向の施設とする（集約型 SA も両方向に出る）。
 *  - ETC 専用: スマート IC、名称の「ETC専用」、出口ランプ上の料金所の payment:cash=no 等。
 *  - オービス: 道路上のノードはその車線（direction=forward/backward も考慮）、道路外はその左側の車線に付ける。
 */
object FacilityExtractor {

    class Params(
        val areaSearchRadius: Double = 330.0,
        val linkSearchMaxDist: Double = 4500.0,
        val poiAreaRadius: Double = 260.0,
        val areaNearLinkDist: Double = 140.0,
        /** 既存の出口から この距離以内のランプ分岐は推定出口にしない */
        val inferredMergeDist: Double = 260.0,
        /** 道路外にあるオービスを道路に付ける最大距離 */
        val cameraRadius: Double = 40.0,
        val userCameraRadius: Double = 80.0
    )

    private class Occ(val edge: Int, val index: Int)

    private class ExitInfo(
        val type: FacilityType?,
        val area: OsmArea?,
        val etcOnly: Boolean,
        val etcReason: String?,
        val destination: String?,
        val otherRoadName: String?,
        val linkName: String?
    )

    private class NamedJunction(val lat: Double, val lon: Double, val base: String, val type: FacilityType)

    fun attach(graph: RoadGraph, data: OsmDataSet, params: Params = Params()) {
        val perEdge = Array<MutableList<FacilityAnchor>>(graph.edges.size) { ArrayList(0) }
        if (graph.isEmpty) {
            graph.anchors = Array(0) { emptyList() }
            return
        }

        // ---- ノード出現位置（出口分岐・料金所・オービス）
        val interesting = HashSet<Long>(data.junctions.size + data.tollBooths.size + data.cameras.size)
        interesting.addAll(data.junctions.keys)
        interesting.addAll(data.tollBooths.keys)
        interesting.addAll(data.cameras.keys)
        val occ = HashMap<Long, MutableList<Occ>>()
        for (e in graph.edges) {
            val ids = e.nodeIds
            for (i in ids.indices) {
                if (ids[i] in interesting) occ.getOrPut(ids[i]) { ArrayList(2) }.add(Occ(e.id, i))
            }
        }

        // ---- SA/PA 施設オブジェクトと設備（POI）
        val areas = data.restAreas.values.toList()
        val areaFacility = HashMap<String, Facility>()
        for (a in areas) {
            val raw = nameOf(a.tags)
            val p = FacilityNameParser.parse(raw)
            val type = when (p.type) {
                FacilityType.SA, FacilityType.PA -> p.type
                else -> if (a.tags["highway"] == "services") FacilityType.SA else FacilityType.PA
            }
            val f = Facility(
                id = "a" + a.key, type = type,
                baseName = p.base.ifEmpty { "名称不明" }, rawName = raw ?: "",
                lat = a.centerLat, lon = a.centerLon,
                etcOnly = false, etcReason = null, exitRef = null, destination = null
            )
            f.directionNote = p.direction
            f.amenities.add(Amenity.TOILET) // 日本の SA/PA は全てトイレあり
            areaFacility[a.key] = f
        }
        assignPois(data, areas, areaFacility, params)

        // 名称つき分岐の一覧（名称の無い分岐・推定出口の名前探しに使う）
        val named = ArrayList<NamedJunction>()
        for (n in data.junctions.values) {
            val p = FacilityNameParser.parse(nameOf(n.tags))
            if (p.base.isNotEmpty() && p.type != null && p.type != FacilityType.TB) named.add(NamedJunction(n.lat, n.lon, p.base, p.type))
        }

        // ---- 出口分岐（IC/JCT/SA・PA出口/SIC）
        val mergedArea = HashMap<String, MutableList<Facility>>()
        for ((nodeId, node) in data.junctions) {
            val occs = occ[nodeId] ?: continue
            val spots = occs.filter { isAnchorSpot(graph, it) }
            if (spots.isEmpty()) continue
            val raw = nameOf(node.tags)
            val parsed = FacilityNameParser.parse(raw)
            // 本線側のエッジを基準にする（ランプ側を基準にすると別路線判定が狂う）
            val mainSpot = spots.firstOrNull { !graph.edges[it.edge].attr.isLink } ?: spots.first()
            val mainEdge = graph.edges[mainSpot.edge]
            val vIdx = graph.vertexByNode[nodeId]
            val exit = if (vIdx != null) analyzeExit(graph, vIdx, mainEdge.attr, data, areas, params) else null

            var type = parsed.type ?: exit?.type ?: FacilityType.IC
            if (type == FacilityType.TB) type = FacilityType.IC
            val linkedArea = if (type.isRestArea || parsed.type == null) exit?.area else null
            if (parsed.type == null && linkedArea != null) {
                type = areaFacility[linkedArea.key]?.type ?: type
            }
            var base = parsed.base
            var inferredByName = false
            if (base.isEmpty()) {
                val fb = fallbackName(node.tags, node.lat, node.lon, exit, linkedArea, areaFacility, named, type)
                if (fb == null) continue
                base = fb.first
                if (parsed.type == null && fb.second != null) type = fb.second!!
                inferredByName = true
            }

            val etcByTag = node.tags["payment:cash"] == "no"
            val etcOnly = parsed.etcOnly || type == FacilityType.SIC || etcByTag || (exit?.etcOnly == true)
            val reason = when {
                type == FacilityType.SIC -> "スマートIC（ETC専用）"
                parsed.etcOnly -> "名称に「ETC専用」"
                etcByTag -> "現金払い不可（payment:cash=no）"
                exit != null && exit.etcOnly -> exit.etcReason
                else -> null
            }
            val f = Facility(
                id = "j$nodeId", type = type, baseName = base, rawName = raw ?: "",
                lat = node.lat, lon = node.lon, etcOnly = etcOnly, etcReason = reason,
                exitRef = node.tags["ref"]?.takeIf { it.isNotBlank() },
                destination = exit?.destination ?: (node.tags["exit_to"] ?: node.tags["destination"])?.replace(';', '・')
            )
            f.directionNote = parsed.direction
            f.typeInferred = parsed.type == null || inferredByName
            if (type == FacilityType.JCT && f.destination == null) f.destination = exit?.otherRoadName

            if (type.isRestArea) {
                val area = linkedArea ?: findAreaByName(areas, areaFacility, base, node.lat, node.lon)
                if (area != null) {
                    areaFacility[area.key]?.let { f.mergeFrom(it) }
                    mergedArea.getOrPut(area.key) { ArrayList() }.add(f)
                } else {
                    f.amenities.add(Amenity.TOILET)
                }
            }
            for (s in spots) perEdge[s.edge].add(FacilityAnchor(f, s.edge, graph.edges[s.edge].cum[s.index]))
        }

        // ---- 出口分岐ノードが登録されていないランプ分岐を「推定出口」として追加
        addInferredExits(graph, data, areas, areaFacility, named, perEdge, params)

        // ---- SA/PA 区域
        for (a in areas) {
            val f = areaFacility[a.key] ?: continue
            anchorRestArea(graph, a, f, perEdge, params)
        }
        // 出口分岐で SA/PA と結び付いた場合、区域側にも設備情報を反映
        for ((key, list) in mergedArea) {
            val af = areaFacility[key] ?: continue
            for (jf in list) af.mergeFrom(jf)
        }

        // ---- 本線料金所
        for ((nodeId, node) in data.tollBooths) {
            val occs = occ[nodeId] ?: continue
            val spots = occs.filter { !graph.edges[it.edge].attr.isLink && isAnchorSpot(graph, it) }
            if (spots.isEmpty()) continue
            val raw = nameOf(node.tags)
            val p = FacilityNameParser.parse(raw)
            val etc = isEtcOnlyToll(node.tags) || p.etcOnly
            val f = Facility(
                id = "t$nodeId", type = FacilityType.TB, baseName = p.base, rawName = raw ?: "",
                lat = node.lat, lon = node.lon, etcOnly = etc,
                etcReason = if (etc) "ETC専用料金所" else null, exitRef = null, destination = null
            )
            for (s in spots) perEdge[s.edge].add(FacilityAnchor(f, s.edge, graph.edges[s.edge].cum[s.index]))
        }

        // ---- オービス
        for ((nodeId, node) in data.cameras) {
            anchorCamera(graph, nodeId, node, occ[nodeId], perEdge, params)
        }

        graph.anchors = Array(graph.edges.size) { i -> perEdge[i].sortedBy { it.offset } }
        graph.restAreas = areas.map { a ->
            RestAreaRef(
                a.centerLat, a.centerLon, maxOf(areaExtent(a), 150.0) + 80.0,
                FacilityRefName(areaFacility[a.key]?.displayName ?: "SA/PA")
            )
        }
    }

    // ------------------------------------------------------------------ 推定出口

    private fun addInferredExits(
        graph: RoadGraph,
        data: OsmDataSet,
        areas: List<OsmArea>,
        areaFacility: Map<String, Facility>,
        named: List<NamedJunction>,
        perEdge: Array<MutableList<FacilityAnchor>>,
        params: Params
    ) {
        for (v in graph.vertices.indices) {
            val outs = graph.outEdges(v)
            if (outs.size < 2) continue
            val ins = graph.inEdges(v).map { graph.edges[it] }.filter { !it.attr.isLink }
            if (ins.isEmpty()) continue
            val mainIn = ins.first()
            val outEdges = outs.map { graph.edges[it] }.filter { it.twin != mainIn.id && !(it.wayId == mainIn.wayId && it.forwardOnWay != mainIn.forwardOnWay) }
            val links = outEdges.filter { it.attr.isLink }
            val mains = outEdges.filter { !it.attr.isLink }
            if (mains.isEmpty()) continue
            // 本線同士の分岐（別路線へ）もランプ扱いで判定する
            val branchMains = mains.filter { FacilityExtractor.differentRoad(it.attr, mainIn.attr) }
            if (links.isEmpty() && (branchMains.isEmpty() || branchMains.size == mains.size)) continue
            val continuation = mains.filter { it !in branchMains }.ifEmpty { mains }
            // 近くに既に出口がある（分岐ノードが少しずれて登録されている等）なら作らない
            if (hasExitNear(graph, perEdge, mainIn, continuation, params.inferredMergeDist)) continue

            val vert = graph.vertices[v]
            val exit = analyzeExit(graph, v, mainIn.attr, data, areas, params)
            val area = exit.area
            val f: Facility = if (area != null) {
                areaFacility[area.key] ?: continue
            } else {
                val type = exit.type ?: if (branchMains.isNotEmpty()) FacilityType.JCT else continue
                val fb = fallbackName(emptyMap(), vert.lat, vert.lon, exit, null, areaFacility, named, type) ?: continue
                Facility(
                    id = "x${vert.nodeId}", type = fb.second ?: type, baseName = fb.first, rawName = "",
                    lat = vert.lat, lon = vert.lon, etcOnly = exit.etcOnly || fb.second == FacilityType.SIC,
                    etcReason = if (fb.second == FacilityType.SIC) "スマートIC（ETC専用）" else exit.etcReason,
                    exitRef = null, destination = exit.destination ?: exit.otherRoadName
                ).also { it.typeInferred = true }
            }
            for (m in continuation) perEdge[m.id].add(FacilityAnchor(f, m.id, 0.0))
        }
    }

    private fun hasExitNear(
        graph: RoadGraph,
        perEdge: Array<MutableList<FacilityAnchor>>,
        mainIn: Edge,
        continuation: List<Edge>,
        dist: Double
    ): Boolean {
        for (a in perEdge[mainIn.id]) {
            if (a.facility.type != FacilityType.TB && a.facility.type != FacilityType.CAMERA && a.offset > mainIn.length - dist) return true
        }
        for (m in continuation) for (a in perEdge[m.id]) {
            if (a.facility.type != FacilityType.TB && a.facility.type != FacilityType.CAMERA && a.offset < dist) return true
        }
        // 1 つ手前のエッジの末尾も確認（短いエッジで分割されている場合）
        if (mainIn.length < dist) {
            for (pid in graph.inEdges(mainIn.from)) {
                val pe = graph.edges[pid]
                if (pe.attr.isLink) continue
                for (a in perEdge[pid]) if (a.facility.type != FacilityType.TB && a.offset > pe.length - (dist - mainIn.length)) return true
            }
        }
        return false
    }

    /** 名称の無い出口の名前を補う。(基本名, 推定種別) を返す。見つからなければ null */
    private fun fallbackName(
        tags: Map<String, String>,
        lat: Double,
        lon: Double,
        exit: ExitInfo?,
        linkedArea: OsmArea?,
        areaFacility: Map<String, Facility>,
        named: List<NamedJunction>,
        type: FacilityType
    ): Pair<String, FacilityType?>? {
        if (linkedArea != null) {
            val af = areaFacility[linkedArea.key]
            if (af != null) return af.baseName to af.type
        }
        // 反対車線などにある同じ IC/JCT の名前（2.5km 以内で最も近いもの）
        var best: NamedJunction? = null
        var bestD = 2500.0
        for (n in named) {
            if (abs(n.lat - lat) > 0.03 || abs(n.lon - lon) > 0.04) continue
            if (n.type.isRestArea && !type.isRestArea) continue
            val d = Geo.distance(lat, lon, n.lat, n.lon)
            if (d < bestD) {
                bestD = d
                best = n
            }
        }
        if (best != null && (best.type == type || type == FacilityType.IC || best.type == FacilityType.SIC)) return best.base to best.type
        val exitTo = tags["exit_to"]
        if (!exitTo.isNullOrBlank()) return exitTo.split(';').first().trim() to null
        val ln = exit?.linkName
        if (!ln.isNullOrBlank()) {
            val p = FacilityNameParser.parse(ln)
            if (p.base.isNotEmpty()) return p.base to p.type
        }
        val dest = exit?.destination
        if (!dest.isNullOrBlank()) return dest.split('・').first() to null
        if (type == FacilityType.JCT && !exit?.otherRoadName.isNullOrBlank()) return exit!!.otherRoadName!! to FacilityType.JCT
        if (best != null) return best.base to best.type
        val ref = tags["ref"]
        if (!ref.isNullOrBlank()) return "出口$ref" to FacilityType.IC
        // 一般道へ出る出口は名称不明でも表示する
        if (type == FacilityType.IC && exit?.type == FacilityType.IC) return "出口" to FacilityType.IC
        return null
    }

    // ------------------------------------------------------------------ SA/PA

    private fun anchorRestArea(
        graph: RoadGraph,
        a: OsmArea,
        f: Facility,
        perEdge: Array<MutableList<FacilityAnchor>>,
        params: Params
    ) {
        val extent = areaExtent(a)
        val near = graph.nearby(a.centerLat, a.centerLon, params.areaSearchRadius + extent) { !it.attr.isLink }
        val accepted = ArrayList<EdgeProjection>()
        for (p in near) {
            val e = graph.edges[p.edge]
            val ok = when {
                e.attr.bidirectional -> true
                p.lateral > 0 -> true
                // 区域が本線にかかっている（中央分離帯側・上下集約）→ 両方向
                a.contains(p.lat, p.lon) -> true
                else -> {
                    // 右側にある場合: より近くに「区域を左側に見る対向車線」があれば対向車線用
                    val b = e.bearingAt(p.offset)
                    near.none { q ->
                        q.edge != p.edge && q.dist < p.dist && q.lateral > 0 &&
                            abs(Geo.diffDeg(graph.edges[q.edge].bearingAt(q.offset), b)) > 120.0
                    }
                }
            }
            if (ok) accepted.add(p)
        }
        for (p in accepted) {
            val e = graph.edges[p.edge]
            val clamped = p.offset <= 0.5 || p.offset >= e.length - 0.5
            if (clamped) {
                val b = e.bearingAt(p.offset)
                val better = accepted.any { q ->
                    q !== p && q.dist <= p.dist + 1.0 &&
                        abs(Geo.diffDeg(graph.edges[q.edge].bearingAt(q.offset), b)) < 60.0 &&
                        q.offset > 0.5 && q.offset < graph.edges[q.edge].length - 0.5
                }
                if (better) continue
            }
            perEdge[p.edge].add(FacilityAnchor(f, p.edge, p.offset))
        }

        // その区域へ入るランプが分かれる本線上の地点（右側出口・集約型 SA でも正しい方向に付く）
        val linkNear = graph.nearby(a.centerLat, a.centerLon, extent + 220.0) { it.attr.isLink }
        val touching = linkNear.map { graph.edges[it.edge] }.filter { touches(it, a, params) }
        if (touching.isEmpty()) return
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Pair<Int, Double>>()
        for (e in touching) queue.add(e.id to 0.0)
        while (queue.isNotEmpty()) {
            val (eid, d) = queue.removeFirst()
            if (!visited.add(eid)) continue
            val e = graph.edges[eid]
            val start = e.from
            val mainIns = graph.inEdges(start).map { graph.edges[it] }.filter { !it.attr.isLink }
            if (mainIns.isNotEmpty()) {
                // ここで本線から分かれている → 本線の続き（同じ路線）に出口として付ける
                for (mi in mainIns) {
                    val conts = graph.outEdges(start).map { graph.edges[it] }
                        .filter { !it.attr.isLink && it.twin != mi.id && !(it.wayId == mi.wayId && it.forwardOnWay != mi.forwardOnWay) }
                    for (c in conts) {
                        if (perEdge[c.id].none { it.facility === f && it.offset < 50.0 }) perEdge[c.id].add(FacilityAnchor(f, c.id, 0.0))
                    }
                }
                continue
            }
            for (pid in graph.inEdges(start)) {
                val pe = graph.edges[pid]
                if (!pe.attr.isLink) continue
                val nd = d + pe.length
                if (nd < 3000.0) queue.add(pid to nd)
            }
        }
    }

    private fun touches(e: Edge, a: OsmArea, params: Params): Boolean {
        for (i in 0 until e.pointCount) {
            if (a.contains(e.lat[i], e.lon[i])) return true
            if (a.rings.isEmpty() && Geo.distance(e.lat[i], e.lon[i], a.centerLat, a.centerLon) < params.areaNearLinkDist) return true
        }
        return false
    }

    // ------------------------------------------------------------------ オービス

    private fun anchorCamera(
        graph: RoadGraph,
        nodeId: Long,
        node: OsmNode,
        occs: List<Occ>?,
        perEdge: Array<MutableList<FacilityAnchor>>,
        params: Params
    ) {
        val tags = node.tags
        val user = nodeId < 0 || tags["source"] == "user"
        val f = Facility(
            id = "c$nodeId", type = FacilityType.CAMERA,
            baseName = (nameOf(tags)?.takeIf { it != "オービス" } ?: "速度取締機"),
            rawName = nameOf(tags) ?: "", lat = node.lat, lon = node.lon,
            etcOnly = false, etcReason = null, exitRef = null, destination = null
        )
        f.maxspeedKmh = RoadAttr.parseMaxspeed(tags["maxspeed"])
        f.cameraType = tags["camera:type"] ?: tags["speed_camera:type"] ?: tags["enforcement:type"] ?: tags["type:ja"]
        f.userDefined = user
        val dirTag = tags["direction"]?.trim()?.lowercase()
        val userBearing = tags["user:bearing"]?.toDoubleOrNull()
        var anchored = false
        if (occs != null) {
            for (o in occs) {
                if (!isAnchorSpot(graph, o)) continue
                val e = graph.edges[o.edge]
                val ok = when (dirTag) {
                    "forward" -> e.forwardOnWay
                    "backward" -> !e.forwardOnWay
                    else -> true
                }
                if (!ok) continue
                perEdge[o.edge].add(FacilityAnchor(f, o.edge, e.cum[o.index]))
                anchored = true
            }
        }
        if (anchored) return
        // 道路外（路側・門型）に登録されたもの → 近くの車線へ
        val radius = if (user) params.userCameraRadius else params.cameraRadius
        val near = graph.nearby(node.lat, node.lon, radius)
        val best = HashMap<Long, EdgeProjection>() // (wayId, 向き) ごとに最近傍
        for (p in near) {
            val e = graph.edges[p.edge]
            val ok = if (userBearing != null) {
                abs(Geo.diffDeg(e.bearingAt(p.offset), userBearing)) < 45.0
            } else {
                // 日本の取締機は進行方向の左側か車線の上 → 右側に離れているものは対向車線用
                p.lateral >= -8.0
            }
            if (!ok) continue
            val key = (e.wayId shl 1) or (if (e.forwardOnWay) 1L else 0L)
            val prev = best[key]
            if (prev == null || p.dist < prev.dist) best[key] = p
        }
        if (best.isEmpty()) return
        // 上下線とも候補になった場合（中央分離帯付近）は、方位指定が無ければ最も近い車線だけ
        val list = if (userBearing == null && best.size > 1 && best.values.map { graph.edges[it.edge].attr.isLink }.all { !it }) {
            listOf(best.values.minByOrNull { it.dist }!!)
        } else best.values.toList()
        for (p in list) perEdge[p.edge].add(FacilityAnchor(f, p.edge, p.offset))
    }

    // ------------------------------------------------------------------ helpers

    fun nameOf(tags: Map<String, String>): String? =
        (tags["name:ja"] ?: tags["name"] ?: tags["official_name"])?.trim()?.takeIf { it.isNotEmpty() }

    /** エッジ終端のノードは後続エッジの始点として現れるので重複させない */
    private fun isAnchorSpot(graph: RoadGraph, o: Occ): Boolean {
        val e = graph.edges[o.edge]
        return o.index < e.pointCount - 1 || graph.outEdges(e.to).isEmpty()
    }

    fun isEtcOnlyToll(tags: Map<String, String>): Boolean {
        if (tags["payment:cash"] == "no") return true
        if (tags["payment:coins"] == "no" && tags["payment:notes"] == "no") return true
        val etcTag = tags["payment:etc"]
        if (etcTag == "only" || etcTag == "exclusive") return true
        val text = listOfNotNull(tags["name"], tags["name:ja"], tags["description"], tags["note"]).joinToString(" ")
        return text.contains("ETC専用") || text.contains("ＥＴＣ専用")
    }

    /** 2 つの道路属性が別路線か（名称・路線番号の両方で判定） */
    fun differentRoad(a: RoadAttr, b: RoadAttr): Boolean {
        val na = a.normName
        val nb = b.normName
        if (na != null && nb != null && na != nb) return true
        val ra = a.normRefs
        val rb = b.normRefs
        if (ra.isNotEmpty() && rb.isNotEmpty() && ra.none { it in rb }) return true
        return false
    }

    private fun analyzeExit(
        graph: RoadGraph,
        vertex: Int,
        mainAttr: RoadAttr,
        data: OsmDataSet,
        areas: List<OsmArea>,
        params: Params
    ): ExitInfo {
        val v = graph.vertices[vertex]
        val outs = graph.outEdges(vertex)
        val exitEdges = outs.filter { graph.edges[it].attr.isLink }
        if (exitEdges.isEmpty()) {
            // 本線同士の分岐（JCT で本線が 2 方向に分かれる形）
            val other = outs.map { graph.edges[it] }.firstOrNull { !it.attr.isLink && differentRoad(it.attr, mainAttr) }
            return ExitInfo(
                if (other != null) FacilityType.JCT else null, null, false, null,
                other?.attr?.destination, other?.attr?.displayName, null
            )
        }
        val nearAreas = areas.filter { Geo.distance(it.centerLat, it.centerLon, v.lat, v.lon) < 3000.0 }
        val destination = exitEdges.firstNotNullOfOrNull { graph.edges[it].attr.destination }
        val linkName = exitEdges.firstNotNullOfOrNull { graph.edges[it].attr.name }

        var reachedArea: OsmArea? = null
        var otherRoad: RoadAttr? = null
        var deadEnd = false
        var etcOnly = false
        var etcReason: String? = null
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Pair<Int, Double>>()
        for (e in exitEdges) queue.add(e to 0.0)
        while (queue.isNotEmpty()) {
            val (eid, d) = queue.removeFirst()
            if (!visited.add(eid)) continue
            val e = graph.edges[eid]
            if (reachedArea == null && nearAreas.isNotEmpty()) reachedArea = areaTouchedBy(e, nearAreas, params)
            for (nid in e.nodeIds) {
                val tb = data.tollBooths[nid] ?: continue
                if (isEtcOnlyToll(tb.tags)) {
                    etcOnly = true
                    etcReason = "出口料金所がETC専用（" + (nameOf(tb.tags) ?: "料金所") + "）"
                }
            }
            val nexts = graph.outEdges(e.to)
            if (nexts.isEmpty()) {
                deadEnd = true
                continue
            }
            for (n in nexts) {
                if (n == e.twin) continue
                val ne = graph.edges[n]
                if (ne.attr.isLink) {
                    val nd = d + e.length
                    if (nd < params.linkSearchMaxDist) queue.add(n to nd)
                } else if (differentRoad(ne.attr, mainAttr)) {
                    if (otherRoad == null) otherRoad = ne.attr
                }
            }
        }
        val type = when {
            reachedArea != null -> if (reachedArea.tags["highway"] == "services") FacilityType.SA else FacilityType.PA
            otherRoad != null && !deadEnd -> FacilityType.JCT
            otherRoad != null && deadEnd -> FacilityType.IC // 一般道へも出られるランプ（IC と JCT の複合）
            deadEnd -> FacilityType.IC
            else -> null
        }
        return ExitInfo(type, reachedArea, etcOnly, etcReason, destination, otherRoad?.displayName, linkName)
    }

    private fun areaTouchedBy(e: Edge, nearAreas: List<OsmArea>, params: Params): OsmArea? {
        val step = maxOf(1, e.pointCount / 12)
        var i = 0
        while (i < e.pointCount) {
            val la = e.lat[i]
            val lo = e.lon[i]
            for (a in nearAreas) {
                if (a.contains(la, lo)) return a
                if (a.rings.isEmpty() && Geo.distance(la, lo, a.centerLat, a.centerLon) < params.areaNearLinkDist) return a
            }
            i += step
        }
        return null
    }

    private fun areaExtent(a: OsmArea): Double {
        var m = 0.0
        for (r in a.rings) for (i in r.lat.indices) {
            m = maxOf(m, Geo.distance(a.centerLat, a.centerLon, r.lat[i], r.lon[i]))
        }
        return min(m, 450.0)
    }

    private fun findAreaByName(
        areas: List<OsmArea>,
        areaFacility: Map<String, Facility>,
        base: String,
        lat: Double,
        lon: Double
    ): OsmArea? {
        var best: OsmArea? = null
        var bestD = 2500.0
        for (a in areas) {
            val f = areaFacility[a.key] ?: continue
            if (f.baseName != base) continue
            val d = Geo.distance(lat, lon, a.centerLat, a.centerLon)
            if (d < bestD) {
                bestD = d
                best = a
            }
        }
        return best
    }

    private fun assignPois(
        data: OsmDataSet,
        areas: List<OsmArea>,
        areaFacility: Map<String, Facility>,
        params: Params
    ) {
        if (areas.isEmpty()) return
        for (p in data.pois.values) {
            val amenity = amenityOf(p.tags) ?: continue
            var target: OsmArea? = null
            var bestD = params.poiAreaRadius
            for (a in areas) {
                if (abs(a.centerLat - p.lat) > 0.006 || abs(a.centerLon - p.lon) > 0.008) continue
                if (a.contains(p.lat, p.lon)) {
                    target = a
                    break
                }
                val d = Geo.distance(p.lat, p.lon, a.centerLat, a.centerLon)
                if (d < bestD) {
                    bestD = d
                    target = a
                }
            }
            val f = target?.let { areaFacility[it.key] } ?: continue
            f.amenities.add(amenity)
            val name = nameOf(p.tags)
            val brand = p.tags["brand:ja"] ?: p.tags["brand"]
            val info = PoiInfo(amenity, name, brand?.takeIf { it != name }, p.tags["opening_hours"])
            if (f.pois.none { it.amenity == info.amenity && it.name == info.name && it.brand == info.brand }) f.pois.add(info)
        }
    }

    fun amenityOf(tags: Map<String, String>): Amenity? {
        when (tags["amenity"]) {
            "fuel" -> return Amenity.GAS
            "charging_station" -> return Amenity.EV
            "restaurant" -> return Amenity.RESTAURANT
            "fast_food", "food_court", "cafe" -> return Amenity.SNACK
            "toilets" -> return Amenity.TOILET
            "shower" -> return Amenity.SHOWER
            "atm", "bank" -> return Amenity.ATM
            "information" -> return Amenity.INFO
        }
        val shop = tags["shop"]
        if (shop != null && shop != "vacant" && shop != "no") {
            return if (shop == "convenience") Amenity.CONVENIENCE else Amenity.SHOP
        }
        when (tags["tourism"]) {
            "information" -> {
                val kind = tags["information"]
                return if (kind == null || kind == "office" || kind == "visitor_centre" || kind == "terminal") Amenity.INFO else null
            }
            "hotel", "motel", "guest_house" -> return Amenity.HOTEL
        }
        if (tags["leisure"] == "dog_park") return Amenity.DOG_RUN
        return null
    }
}
