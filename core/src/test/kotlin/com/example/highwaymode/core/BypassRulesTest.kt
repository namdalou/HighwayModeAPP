package com.example.highwaymode.core

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.geo.LatLon
import com.example.highwaymode.core.graph.BypassClassifier
import com.example.highwaymode.core.hwdb.HwdbFile
import com.example.highwaymode.core.hwdb.TileCodec
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OsmWay
import com.example.highwaymode.core.osm.OverpassParser
import com.example.highwaymode.core.osm.TileId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主要バイパスの判定の決まり（v2.15）。
 *  - 信号・横断歩道・踏切・国道どうしの平面交差で区切る（反対車線にも区切りを写す）
 *  - ひと続きの区間に、出口の IC が 2 か所以上・長さ 3km 以上
 *  - 実データ（国道23号 名四国道・知立バイパス周辺）で、信号のある一般道が外れ、本物のバイパスが残る
 */
class BypassRulesTest {

    private var nextId = 1L
    private val pos = HashMap<Long, LatLon>()
    private val ways = ArrayList<OsmWay>()
    private val junctions = ArrayList<OsmNode>()

    /** 南へ s [m]、東へ x [m] の点 */
    private fun pt(s: Double, x: Double, lon0: Double = 137.4): LatLon =
        LatLon(34.9 - s / Geo.metersPerDegLat(34.9), lon0 + x / Geo.metersPerDegLon(34.9))

    private fun node(p: LatLon): Long {
        val id = nextId++
        pos[id] = p
        return id
    }

    private fun way(ids: List<Long>, tags: Map<String, String>): Long {
        val id = 500_000 + nextId++
        ways.add(OsmWay(id, ids.toLongArray(), DoubleArray(ids.size) { pos[ids[it]]!!.lat }, DoubleArray(ids.size) { pos[ids[it]]!!.lon }, tags))
        return id
    }

    /** 上下線が分かれた国道（50m ごとのノード、1km ごとのウェイ）。下り = 東側（南向き）、上り = 西側（北向き） */
    private inner class Dual(val km: Int, val ref: String, val name: String, lon0: Double = 137.4) {
        val sb = LongArray(km * 20 + 1) { node(pt(it * 50.0, 12.0, lon0)) }
        val nb = LongArray(km * 20 + 1) { node(pt(it * 50.0, -12.0, lon0)) }
        private val lon0 = lon0

        init {
            val tags = mapOf("highway" to "trunk", "ref" to ref, "name" to name, "oneway" to "yes", "lanes" to "2")
            for (k in 0 until km) {
                val a = k * 20
                val b = a + 20
                way((a..b).map { sb[it] }, tags)
                way((b downTo a).map { nb[it] }, tags)
            }
        }

        /** 下りの出口（分岐ノードつき）と、上りの出口（分岐ノードなし） */
        fun interchange(atKm: Double, label: String) {
            val i = (atKm * 20).toInt()
            way(
                listOf(sb[i]) + (1..8).map { j -> node(pt(atKm * 1000 + 50 * j, 12.0 + j * j * 1.5, lon0)) },
                mapOf("highway" to "trunk_link", "oneway" to "yes")
            )
            junctions.add(OsmNode(sb[i], pos[sb[i]]!!.lat, pos[sb[i]]!!.lon, mapOf("highway" to "motorway_junction", "name" to label)))
            way(
                listOf(nb[i + 4]) + (1..8).map { j -> node(pt(atKm * 1000 + 200 - 50 * j, -12.0 - j * j * 1.5, lon0)) },
                mapOf("highway" to "trunk_link", "oneway" to "yes")
            )
        }

        fun sbNodeAt(atKm: Double): Long = sb[(atKm * 20).toInt()]
        fun nbNodeAt(atKm: Double): Long = nb[(atKm * 20).toInt()]
    }

    private fun dataSet(barriers: Set<Long> = emptySet()): OsmDataSet {
        val merged = OsmDataSet.merge(listOf(OsmTileData(ways, junctions, emptyList(), emptyList(), emptyList())))
        return OsmDataSet(merged.roads, merged.junctions, merged.tollBooths, merged.restAreas, merged.pois, merged.cameras, barriers)
    }

    /** 採用された本線（国道）の、起点からの距離 [km] の範囲（東側 = 下り、西側 = 上り）を 0.1km 単位で集める */
    private fun keptKm(ds: OsmDataSet, east: Boolean, lon0: Double = 137.4): Set<Int> {
        val out = HashSet<Int>()
        for (w in ds.roads.values) {
            if (w.tags["highway"] != "trunk") continue
            for (i in 0 until w.lat.size - 1) {
                val isEast = w.lon[i] > lon0
                if (isEast != east) continue
                val s0 = (34.9 - w.lat[i]) * Geo.metersPerDegLat(34.9)
                val s1 = (34.9 - w.lat[i + 1]) * Geo.metersPerDegLat(34.9)
                val mid = (s0 + s1) / 2
                out.add(Math.floor(mid / 100.0).toInt())
            }
        }
        return out
    }

    private fun Set<Int>.covers(fromKm: Double, toKm: Double): Boolean =
        ((fromKm * 10).toInt() until (toKm * 10).toInt()).all { it in this }

    private fun Set<Int>.touches(fromKm: Double, toKm: Double): Boolean =
        ((fromKm * 10).toInt() until (toKm * 10).toInt()).any { it in this }

    @Test
    fun signalsCutTheBypassAndShortPartsAreDropped() {
        // 0〜30km。IC は 5・8・11・14km。6.5km に信号（下りのウェイにだけ登録）
        val road = Dual(30, "23", "名豊道路")
        for (km in listOf(5, 8, 11, 14)) road.interchange(km.toDouble(), "IC$km")
        val signal = road.sbNodeAt(6.5)

        // 信号が無ければ、これまでどおり IC の前後・間が採用される
        val plain = BypassClassifier.filter(dataSet())
        assertTrue(keptKm(plain, east = true).covers(5.0, 14.0))

        val ds = BypassClassifier.filter(dataSet(setOf(signal)), markBypass = true)
        val down = keptKm(ds, east = true)
        val up = keptKm(ds, east = false)
        println("下り: ${down.minOrNull()?.div(10.0)}〜${down.maxOrNull()?.plus(1)?.div(10.0)} km, 上り: ${up.minOrNull()?.div(10.0)}〜${up.maxOrNull()?.plus(1)?.div(10.0)} km")
        // 信号から先（IC が 3 か所）は採用
        assertTrue("下り: 信号の先〜最後の IC", down.covers(6.5, 14.0))
        assertTrue("上り: 反対車線にも区切りが写り、信号の先は採用", up.covers(6.6, 14.0))
        // 信号の手前（IC が 1 か所だけ）は不採用
        assertFalse("下り: 信号の手前は対象外", down.touches(0.0, 6.5))
        assertFalse("上り: 信号の手前は対象外", up.touches(0.0, 6.4))
        // 最後の IC から 1.5km より先は対象外
        assertFalse(down.touches(17.0, 30.0))
        // 途中に信号のあるウェイは分けられ、分けたウェイにも判定済みの印が付く
        assertTrue(ds.roads.values.any { it.id != BypassClassifier.originalId(it.id) && it.tags[BypassClassifier.TAG] == BypassClassifier.TAG_BYPASS })
        // 判定済みのデータをもう一度判定しても変わらない（アプリが読み込むときの動き）
        val again = BypassClassifier.filter(ds)
        assertEquals(ds.roads.keys, again.roads.keys)
    }

    @Test
    fun ordinaryRoadBetweenTwoInterchangesWithSignalsIsExcluded() {
        // 利用者の報告の形: 一般道（信号あり）の 6km 先にバイパスの IC がある。手前側にも立体交差（分岐ノード）が 1 つある
        //   0km: 立体交差（大高 JCT のようなもの）  1〜6km: 信号のある一般道  7km〜: バイパス（IC が 7・10・13km）
        val road = Dual(20, "23", "国道23号")
        road.interchange(0.5, "立体交差")
        for (km in listOf(7, 10, 13)) road.interchange(km.toDouble(), "IC$km")
        val signals = setOf(road.sbNodeAt(2.0), road.nbNodeAt(3.5), road.sbNodeAt(5.0))

        // 信号の情報が無いと、IC の間（6.5km）は全部採用されてしまう（v2.14 までの誤判定と同じ形）
        val without = keptKm(BypassClassifier.filter(dataSet()), east = true)
        assertTrue(without.covers(0.5, 13.0))

        val ds = BypassClassifier.filter(dataSet(signals))
        val down = keptKm(ds, east = true)
        val up = keptKm(ds, east = false)
        println("下り: ${down.minOrNull()?.div(10.0)}〜 km, 上り: ${up.minOrNull()?.div(10.0)}〜 km")
        assertFalse("一般道の区間（立体交差のまわりも含む）は対象外", down.touches(0.0, 5.0))
        assertFalse(up.touches(0.0, 5.0))
        assertTrue("バイパスの区間は採用", down.covers(7.0, 13.0))
        assertTrue(up.covers(7.0, 13.0))
    }

    @Test
    fun singleInterchangeOrExpresswayRampAloneIsNotABypass() {
        // IC が 1 つだけの国道、高速道路の IC へのランプがあるだけの国道 → 対象外
        val a = Dual(10, "1", "国道1号")
        a.interchange(5.0, "ひとつだけ")
        val b = Dual(10, "42", "国道42号", lon0 = 137.6)
        // 高速道路（本線とランプ）と、国道から高速道路へ上がるランプ
        val mw = (0..40).map { node(pt(4000.0 + it * 50.0, 400.0, 137.6)) }
        way(mw, mapOf("highway" to "motorway", "oneway" to "yes", "ref" to "E1"))
        val rampTop = mw[10]
        val mid = node(pt(4300.0, 200.0, 137.6))
        way(listOf(b.sbNodeAt(4.0), mid), mapOf("highway" to "trunk_link", "oneway" to "yes"))
        way(listOf(mid, rampTop), mapOf("highway" to "motorway_link", "oneway" to "yes"))

        val (ds, result) = BypassClassifier.filterWithResult(dataSet())
        assertTrue("国道は 1 本も採用しない", ds.roads.values.none { it.tags["highway"] == "trunk" })
        assertTrue("高速道路は採用", ds.roads.values.any { it.tags["highway"] == "motorway" })
        assertTrue(result.components.isNotEmpty() && result.components.none { it.kept })
        BypassClassifier.report(result).forEach { println(it) }
    }

    @Test
    fun crossingOfTwoNationalRoadsIsABarrier() {
        // バイパスの途中（6.5km）で、別の国道と平面で交わる（同じノードを共有）→ そこで区切られる
        val road = Dual(20, "23", "名豊道路")
        for (km in listOf(5, 8, 11, 14)) road.interchange(km.toDouble(), "IC$km")
        val crossS = road.sbNodeAt(6.5)
        val crossN = road.nbNodeAt(6.5)
        val west = (1..10).map { node(pt(6500.0, -12.0 - it * 50.0)) }.reversed()
        val east = (1..10).map { node(pt(6500.0, 12.0 + it * 50.0)) }
        way(west + listOf(crossN, crossS) + east, mapOf("highway" to "trunk", "ref" to "155"))

        val ds = BypassClassifier.filter(dataSet())
        val down = keptKm(ds, east = true)
        assertFalse("平面交差の手前（IC が 1 か所）は対象外", down.touches(0.0, 6.5))
        assertTrue(down.covers(6.5, 14.0))
        assertTrue("交わる国道は対象外", ds.roads.values.none { it.tags["ref"] == "155" })
    }

    @Test
    fun untaggedGapBetweenMotorroadSectionsIsKept() {
        // 自動車専用の印が一部のウェイにしか付いていない道路: 0〜4km と 6〜10km が自動車専用、4〜6km は印なし（IC も無い）
        val ids = (0..200).map { node(pt(it * 50.0, 0.0)) }
        val base = mapOf("highway" to "trunk", "ref" to "9", "name" to "山陰道")
        way(ids.subList(0, 81), base + ("motorroad" to "yes"))
        val gap = way(ids.subList(80, 121), base)
        way(ids.subList(120, 201), base + ("motorroad" to "yes"))
        // 自動車専用の区間の先に続く、IC の無い一般道（別のウェイ）は対象外
        val tail = (1..60).map { node(pt(10_000.0 + it * 50.0, 0.0)) }
        val after = way(listOf(ids[200]) + tail, base)

        val ds = BypassClassifier.filter(dataSet())
        assertTrue("自動車専用の区間の間は採用", gap in ds.roads.keys)
        assertFalse("自動車専用の区間の先の一般道は対象外", after in ds.roads.keys)
    }

    @Test
    fun tileCodecKeepsBarrierNodesAndStaysReadable() {
        val road = Dual(2, "23", "名豊道路")
        val barriers = longArrayOf(road.sbNodeAt(1.0), road.nbNodeAt(0.5))
        val tile = OsmTileData(ways, junctions, emptyList(), emptyList(), emptyList(), emptyList(), barriers)
        val back = TileCodec.decode(TileCodec.encode(tile))
        assertArrayEquals(barriers.sortedArray(), back.barrierNodes)
        assertEquals(ways.size, back.roads.size)
        // 信号などの無いタイル（判定済みのデータ）は、これまでと同じ並び（末尾に何も足さない）
        val plain = OsmTileData(ways, junctions, emptyList(), emptyList(), emptyList())
        assertEquals(0, TileCodec.decode(TileCodec.encode(plain)).barrierNodes.size)
        assertTrue(TileCodec.encode(tile).size > TileCodec.encode(plain).size)
    }

    @Test
    fun overpassParserReadsSignalsAndCrossings() {
        val json = """
            {"elements":[
              {"type":"way","id":10,"nodes":[1,2,3],"geometry":[{"lat":35.0,"lon":137.0},{"lat":35.001,"lon":137.0},{"lat":35.002,"lon":137.0}],
               "tags":{"highway":"trunk","ref":"23","oneway":"yes"}},
              {"type":"node","id":2,"lat":35.001,"lon":137.0,"tags":{"highway":"traffic_signals"}},
              {"type":"node","id":3,"lat":35.002,"lon":137.0,"tags":{"highway":"crossing","crossing":"uncontrolled"}},
              {"type":"node","id":4,"lat":35.003,"lon":137.0,"tags":{"highway":"traffic_signals","traffic_signals":"emergency"}},
              {"type":"node","id":5,"lat":35.004,"lon":137.0,"tags":{"railway":"level_crossing"}},
              {"type":"node","id":6,"lat":35.005,"lon":137.0,"tags":{"highway":"crossing","crossing":"no"}}
            ]}
        """.trimIndent()
        val t = OverpassParser.parse(json)
        assertEquals(1, t.roads.size)
        // 交差点の信号・横断歩道・踏切だけ（消防署などの信号・「横断できない」印は数えない）
        assertArrayEquals(longArrayOf(2, 3, 5), t.barrierNodes.sortedArray())
    }

    /**
     * 実データ（国道23号 名四国道・知立バイパス周辺。© OpenStreetMap contributors, ODbL）。
     * テスト用データは v2.14 までの判定で作ったものなので、判定済みの印を外して判定し直す。
     * 信号の情報は入っていないので、名四国道（大高〜豊明）の途中のノード 1 つを信号として渡す（片側の車線だけ）。
     */
    @Test
    fun realDataMeishiRoadIsExcludedButChiryuBypassIsKept() {
        val url = BypassRulesTest::class.java.getResource("/hwdb/aichi-route23.hwdb")
        assertNotNull("テスト用データ hwdb/aichi-route23.hwdb", url)
        val db = HwdbFile.open(File(url!!.toURI()))
        val tiles = ArrayList<OsmTileData>()
        for (iy in 73..75) for (ix in 67..69) db.read(TileId(iy, ix))?.let { tiles.add(it) }
        val merged = OsmDataSet.merge(tiles)
        val roads = LinkedHashMap<Long, OsmWay>()
        for ((id, w) in merged.roads) {
            roads[id] = if (w.tags.containsKey(BypassClassifier.TAG)) OsmWay(w.id, w.nodeIds, w.lat, w.lon, w.tags - BypassClassifier.TAG) else w
        }
        // 名四国道（東行き）の途中のノード（北緯 35.0437・東経 136.9715 付近）
        val signal = 739254809L
        assertTrue("テスト用データに、信号として使うノードがある", roads.values.any { signal in it.nodeIds })
        val raw = OsmDataSet(roads, merged.junctions, merged.tollBooths, merged.restAreas, merged.pois, merged.cameras, setOf(signal))
        val (ds, result) = BypassClassifier.filterWithResult(raw, markBypass = true)
        BypassClassifier.report(result).forEach { println(it) }

        val bypass = ds.roads.values.filter { it.tags[BypassClassifier.TAG] == BypassClassifier.TAG_BYPASS && it.tags["highway"] == "trunk" }
        fun km(list: List<OsmWay>): Double = list.sumOf { w -> (0 until w.lat.size - 1).sumOf { Geo.distance(w.lat[it], w.lon[it], w.lat[it + 1], w.lon[it + 1]) } } / 1000
        val byName = bypass.groupBy { it.tags["name"] ?: "" }
        // 本物のバイパス（名豊道路の各区間・衣浦豊田道路）は、ほぼ全部が残る
        for ((name, min) in listOf("知立バイパス" to 30.0, "岡崎バイパス" to 27.0, "蒲郡バイパス" to 28.0, "豊橋バイパス" to 28.0, "豊橋東バイパス" to 11.0, "衣浦豊田道路" to 12.0)) {
            val got = km(byName[name] ?: emptyList())
            assertTrue("$name は採用（%.1f km）".format(got), got >= min)
        }
        // 名四国道（国道23号の一般道の区間）: 信号より西（大高 JCT のまわりを含む）は 1 本も残らない
        val meishi = bypass.filter { it.tags["name"] == "国道23号" }
        assertTrue("名四国道の信号より西は対象外", meishi.none { w -> w.lon.any { it < 136.9710 } })
        // 残るのは、豊明 IC（知立バイパスの起点）の手前の短い区間だけ（上下合わせて 6km 未満。v2.14 までは 19.6km）
        assertTrue("名四国道で残るのは豊明 IC の手前だけ（%.1f km）".format(km(meishi)), km(meishi) < 6.0)
        // IC の無い一般の国道は対象外（v2.14 までは、高速道路の IC へのランプの前後が入っていた）
        for (name in listOf("国道155号", "国道302号", "国道1号", "国道42号", "国道153号", "飯田街道", "豊田南バイパス")) {
            assertTrue("$name は対象外", (byName[name] ?: emptyList()).isEmpty())
        }
        // 国道419号（衣浦豊田道路の前後の一般道）: 残るのは衣浦豊田道路の端の IC の先 1.5km まで
        //（テスト用データには信号が入っていないため。実際のデータでは最初の信号まで。v2.14 までは 7.2km）
        assertTrue("国道419号で残るのは端の IC の先だけ（%.1f km）".format(km(byName["国道419号"] ?: emptyList())), km(byName["国道419号"] ?: emptyList()) < 5.0)
    }
}
