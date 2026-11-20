package com.example.highwaymode.core

import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.traffic.RouteCorridor
import com.example.highwaymode.core.traffic.TomTomFlowTiles
import com.example.highwaymode.core.traffic.TrafficData
import com.example.highwaymode.core.traffic.TrafficItem
import com.example.highwaymode.core.traffic.TrafficKind
import com.example.highwaymode.core.traffic.TrafficMapper
import com.example.highwaymode.core.traffic.TrafficQuery
import com.example.highwaymode.core.traffic.VectorTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * v2.23: TomTom の「道路の流れ」のベクトルタイルの読み取りと、前方経路への対応づけ。
 * タイルは仕様（vector-tile-spec 2.1）どおりにこのテストの中で作る（実物の応答はこの作業環境では取れないため）。
 */
class TomTomFlowTest {

    companion object {
        lateinit var net: TestNetwork
        lateinit var graph: RoadGraph

        @BeforeClass
        @JvmStatic
        fun setup() {
            net = TestNetwork().build()
            graph = net.graph()
        }
    }

    // ---------------------------------------------------------------- protobuf を書く（テスト用）

    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7FL.inv() == 0L) {
                    out.write(x.toInt())
                    return
                }
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
        }

        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytes(field: Int, b: ByteArray) {
            tag(field, 2); varint(b.size.toLong()); out.write(b)
        }

        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun uint(field: Int, v: Long) {
            tag(field, 0); varint(v)
        }

        fun double(field: Int, v: Double) {
            tag(field, 1)
            val bits = java.lang.Double.doubleToLongBits(v)
            for (k in 0 until 8) out.write(((bits ushr (8 * k)) and 0xFF).toInt())
        }

        fun float(field: Int, v: Float) {
            tag(field, 5)
            val bits = java.lang.Float.floatToIntBits(v)
            for (k in 0 until 4) out.write((bits ushr (8 * k)) and 0xFF)
        }

        fun packed(field: Int, vals: List<Long>) {
            val p = Pb()
            vals.forEach { p.varint(it) }
            bytes(field, p.out.toByteArray())
        }

        fun toBytes(): ByteArray = out.toByteArray()
    }

    private fun zz(n: Long): Long = (n shl 1) xor (n shr 63)

    private class Line(val la: DoubleArray, val lo: DoubleArray, val level: Double, val closed: Boolean = false)

    /** z/x/y のタイルに線を書く（属性は traffic_level と road_closure）。値は double・float・bool を混ぜて書く */
    private fun tile(z: Int, x: Int, y: Int, lines: List<Line>, extent: Int = 4096): ByteArray {
        val keys = listOf("traffic_level", "road_closure", "traffic_road_coverage")
        val values = ArrayList<Any>()
        fun valueIndex(v: Any): Int {
            val i = values.indexOf(v)
            if (i >= 0) return i
            values.add(v)
            return values.size - 1
        }
        val features = ArrayList<ByteArray>()
        for ((n, ln0) in lines.withIndex()) {
            val f = Pb()
            f.uint(1, n.toLong() + 1)
            // 値の種類を変えて、どれでも読めることを確かめる（偶数番目は double、奇数番目は float）
            val lv: Any = if (n % 2 == 0) ln0.level else ln0.level.toFloat()
            f.packed(2, listOf(0L, valueIndex(lv).toLong(), 1L, valueIndex(ln0.closed).toLong(), 2L, valueIndex("full").toLong()))
            f.uint(3, VectorTile.TYPE_LINE.toLong())
            val g = ArrayList<Long>()
            var cx = 0L
            var cy = 0L
            for (k in ln0.la.indices) {
                val px = Math.round(((ln0.lo[k] + 180.0) / 360.0 * (1 shl z) - x) * extent)
                val r = Math.toRadians(ln0.la[k])
                val py = Math.round(((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z) - y) * extent)
                if (k == 0) g.add(9L) else if (k == 1) g.add(2L or ((ln0.la.size - 1).toLong() shl 3))
                g.add(zz(px - cx)); g.add(zz(py - cy))
                cx = px; cy = py
            }
            f.packed(4, g)
            features.add(f.toBytes())
        }
        val layer = Pb()
        layer.uint(15, 2)
        layer.string(1, "Traffic flow")
        for (f in features) layer.bytes(2, f)
        for (k in keys) layer.string(3, k)
        for (v in values) {
            val vp = Pb()
            when (v) {
                is Double -> vp.double(3, v)
                is Float -> vp.float(2, v)
                is Boolean -> vp.uint(7, if (v) 1 else 0)
                is String -> vp.string(1, v)
            }
            layer.bytes(4, vp.toBytes())
        }
        layer.uint(5, extent.toLong())
        val t = Pb()
        t.bytes(3, layer.toBytes())
        return t.toBytes()
    }

    private fun line(fromS: Double, toS: Double, nb: Boolean = false, lateral: Double = 0.0): Pair<DoubleArray, DoubleArray> {
        val n = 40
        val pts = (0..n).map { k ->
            val s = fromS + (toS - fromS) * k / n
            if (nb) net.nbPoint(s, lateral) else net.sbPoint(s, lateral)
        }
        return pts.map { it.lat }.toDoubleArray() to pts.map { it.lon }.toDoubleArray()
    }

    /** 下り線の s0 からの前方経路と、その問い合わせ（200m ごと） */
    private fun routeAndQuery(s0: Double): Pair<com.example.highwaymode.core.route.RouteAhead, TrafficQuery> {
        val p0 = net.sbPoint(s0)
        val p = graph.nearby(p0.lat, p0.lon, 20.0) { it.wayId in net.sbWayIds }.first()
        val route = RouteAheadBuilder().build(graph, p.edge, p.offset)
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        val ds = ArrayList<Double>()
        var s = s0
        while (s <= 59_000.0) {
            val q = net.sbPoint(s)
            la.add(q.lat); lo.add(q.lon); ds.add(s - s0)
            s += 200.0
        }
        val q = TrafficQuery(la.toDoubleArray(), lo.toDoubleArray(), DoubleArray(la.size), ds.toDoubleArray(), ds.last())
        return route to q
    }

    @Test
    fun vectorTileDecodesLinesAndTags() {
        val (la, lo) = line(20_000.0, 20_400.0)
        val z = 12
        val x = VectorTile.tileX(z, lo[0])
        val y = VectorTile.tileY(z, la[0])
        val bytes = tile(z, x, y, listOf(Line(la, lo, 0.3), Line(la, lo, 0.62, closed = true)))
        val layers = VectorTile.decode(bytes)
        assertEquals(1, layers.size)
        assertEquals("Traffic flow", layers[0].name)
        assertEquals(2, layers[0].features.size)
        val f0 = layers[0].features[0]
        assertEquals(VectorTile.TYPE_LINE, f0.type)
        assertEquals(0.3, f0.number("traffic_level")!!, 1e-9)
        assertEquals(false, f0.bool("road_closure"))
        assertEquals("full", f0.text("traffic_road_coverage"))
        assertEquals(0.62, layers[0].features[1].number("traffic_level")!!, 1e-6)
        assertEquals(true, layers[0].features[1].bool("road_closure"))
        // 座標が元の経度・緯度に戻る（タイル z12 の 1 目盛り ≈ 2m）
        val pts = f0.lines[0]
        assertEquals(la.size * 2, pts.size)
        for (k in la.indices) {
            val back = com.example.highwaymode.core.geo.Geo.distance(
                la[k], lo[k],
                VectorTile.lat(z, y, pts[2 * k + 1].toDouble(), 4096), VectorTile.lon(z, x, pts[2 * k].toDouble(), 4096)
            )
            assertTrue("点 $k のずれ ${back}m", back < 3.0)
        }
        // 壊れたデータは例外（途中で切れた）
        var failed = false
        try {
            VectorTile.decode(bytes.copyOf(bytes.size - 3))
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun tilesCoverTheRouteAhead() {
        val (_, q) = routeAndQuery(1000.0)
        val tiles = TomTomFlowTiles.tilesFor(q, 50_000.0)
        assertTrue("タイルがある", tiles.isNotEmpty())
        assertTrue("上限以内", tiles.size <= TomTomFlowTiles.MAX_TILES)
        // 前方 50km までの経路の点は、どれかのタイルに入る
        val set = tiles.map { it[0] to it[1] }.toSet()
        for (i in q.lat.indices) {
            if (q.dist[i] > 50_000.0) break
            val t = VectorTile.tileX(12, q.lon[i]) to VectorTile.tileY(12, q.lat[i])
            assertTrue("経路の点 ${q.dist[i]} のタイル", t in set)
        }
        // 近い順（最初のタイルは現在地）
        assertEquals(VectorTile.tileX(12, q.lon[0]), tiles[0][0])
        assertEquals(VectorTile.tileY(12, q.lat[0]), tiles[0][1])
        println("前方 50km のタイル: ${tiles.size} 枚")
    }

    @Test
    fun flowTileJamsAreMappedToTheRouteCarriageway() {
        val (route, q) = routeAndQuery(1000.0)
        val corridor = RouteCorridor(q)
        // 下り（進む車線）の 20〜23km が渋滞（0.3）、30〜31km が混雑（0.6）、40〜41km はやや遅い（0.8）、
        // 上り（反対車線）の同じ 20〜23km も渋滞、下りの 10〜12km は順調（0.95 → 使わない）、
        // 経路から 400m 離れた線（別の道路）も渋滞
        val (a1, o1) = line(20_000.0, 23_000.0)
        val (a2, o2) = line(30_000.0, 31_000.0)
        val (a3, o3) = line(40_000.0, 41_000.0)
        val (a4, o4) = line(23_000.0, 20_000.0, nb = true)
        val (a5, o5) = line(10_000.0, 12_000.0)
        val (a6, o6) = line(15_000.0, 16_000.0, lateral = 400.0)
        // 座標列が逆向き（上り向き）でも下り線の上に重なっている線（渋滞）は下りに載る
        val (a7, o7) = line(50_500.0, 50_000.0)
        val all = listOf(
            Line(a1, o1, 0.3), Line(a2, o2, 0.6), Line(a3, o3, 0.8), Line(a4, o4, 0.2),
            Line(a5, o5, 0.95), Line(a6, o6, 0.1), Line(a7, o7, 0.25)
        )
        // 1 枚のタイル（座標はタイルの外にはみ出してもよい）に全部書く
        val z = 12
        val x = VectorTile.tileX(z, o1[0])
        val y = VectorTile.tileY(z, a1[0])
        val bytes = tile(z, x, y, all)
        val stats = TomTomFlowTiles.Stats()
        val items = TomTomFlowTiles.parse(bytes, z, x, y, corridor, stats)
        println(String.format(Locale.US, "流れのタイル: 線 %d・近く %d（渋滞 %d・混雑 %d）", stats.lines, stats.near, stats.jam, stats.slow))
        assertEquals(7, stats.lines)
        // 順調（0.95）と、経路から離れた線は残らない
        assertEquals(5, items.size)
        assertTrue(items.all { it.source == TrafficItem.SOURCE_FLOW })
        val data = TrafficData("TomTom", 0L, items, true, q.lat, q.lon)
        val et = TrafficMapper.annotate(graph, data)
        val rt = TrafficMapper.forRoute(route, graph, et, 50.0)
        val jams = rt.events.filter { it.kind == TrafficKind.JAM }
        val slows = rt.events.filter { it.kind == TrafficKind.SLOW }
        println("経路の出来事: " + rt.events.joinToString { String.format(Locale.US, "%s %.0f〜%.0f", it.kind.label, it.start, it.end) })
        // ルート始点は道路上 1km
        val j1 = jams.firstOrNull { it.start in 18_500.0..19_500.0 }
        assertNotNull("下りの 20〜23km の渋滞が載る", j1)
        assertEquals(22_000.0, j1!!.end, 150.0)
        assertTrue("下りの 30〜31km の混雑が載る", slows.any { it.start in 28_800.0..29_200.0 && it.end in 29_800.0..30_200.0 })
        assertTrue("逆向きの座標列でも下り線に重なる渋滞は載る", jams.any { it.start in 48_800.0..49_200.0 })
        // 反対車線・離れた道路の渋滞は載らない（20〜23km は下りの渋滞だけで、0.2 にならない）
        assertEquals(0.3, rt.ratio[(20_000.0 / 50).toInt()], 1e-9)
        assertTrue("やや遅い区間（0.8）は出来事にしない", rt.events.none { it.start in 38_800.0..40_200.0 })
        assertEquals(0.8, rt.ratio[(39_500.0 / 50).toInt()], 1e-9)
        // 照合の集計: 4 本が進む車線に、上りの 1 本はほかの車線に載る
        val onRoute = items.count { item -> route.segments.any { seg -> et.spans[seg.edge]?.any { it.item === item } == true } }
        assertEquals(4, onRoute)
        assertEquals(5, et.matched.size)
    }

    @Test
    fun flowSegmentDataParses() {
        val json = """{"flowSegmentData":{"frc":"FRC0","currentSpeed":23,"freeFlowSpeed":88,"currentTravelTime":313,
            "freeFlowTravelTime":81,"confidence":0.93,"roadClosure":false,"coordinates":{"coordinate":[
            {"latitude":35.0,"longitude":138.0},{"latitude":35.005,"longitude":138.001},{"latitude":35.01,"longitude":138.002}]},
            "@version":"traffic-service-flow 1.0.120"}}"""
        val seg = com.example.highwaymode.core.traffic.TomTomFlowSegments.parse(json)
        assertNotNull(seg)
        assertEquals(23.0, seg!!.currentKmh, 1e-9)
        assertEquals(88.0, seg.freeKmh, 1e-9)
        assertEquals(0.93, seg.confidence, 1e-9)
        assertEquals(3, seg.lat.size)
        assertEquals(138.002, seg.lon[2], 1e-9)
        assertEquals(TrafficKind.JAM, TomTomFlowTiles.kindFor(23.0 / 88.0))
        assertEquals(TrafficKind.SLOW, TomTomFlowTiles.kindFor(55.0 / 88.0))
        assertEquals(TrafficKind.FLOW, TomTomFlowTiles.kindFor(70.0 / 88.0))
        // 区間の無い応答
        assertNull(com.example.highwaymode.core.traffic.TomTomFlowSegments.parse("""{"flowSegmentData":{"currentSpeed":10}}"""))
    }

    @Test
    fun corridorFindsNearLinesOnly() {
        val (_, q) = routeAndQuery(1000.0)
        val c = RouteCorridor(q)
        val p = net.sbPoint(20_000.0)
        assertNotNull(c.distanceTo(p.lat, p.lon, 60.0))
        assertEquals(19_000.0, c.alongOf(p.lat, p.lon, 60.0)!!, 60.0)
        val far = net.sbPoint(20_000.0, lateral = 400.0)
        assertNull(c.distanceTo(far.lat, far.lon, 60.0))
        // 頂点が経路から離れていても、線の途中が経路に近ければ近い（経路を横切る線）
        val a = net.sbPoint(25_000.0, lateral = -500.0)
        val b = net.sbPoint(25_000.0, lateral = 500.0)
        assertTrue(c.lineNear(doubleArrayOf(a.lat, b.lat), doubleArrayOf(a.lon, b.lon), 60.0))
    }
}
