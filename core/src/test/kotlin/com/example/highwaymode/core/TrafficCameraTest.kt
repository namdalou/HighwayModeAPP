package com.example.highwaymode.core

import com.example.highwaymode.core.camera.CameraAlertPlanner
import com.example.highwaymode.core.camera.UserCameras
import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.traffic.FlexPolyline
import com.example.highwaymode.core.traffic.GooglePolyline
import com.example.highwaymode.core.traffic.GoogleRoutesTrafficProvider
import com.example.highwaymode.core.traffic.HereTrafficProvider
import com.example.highwaymode.core.traffic.TomTomTrafficProvider
import com.example.highwaymode.core.traffic.TrafficData
import com.example.highwaymode.core.traffic.TrafficItem
import com.example.highwaymode.core.traffic.TrafficKind
import com.example.highwaymode.core.traffic.TrafficMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Locale

class TrafficCameraTest {

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

    @Test
    fun polylineEncodingsMatchReferenceVectors() {
        // HERE flexible-polyline README の例
        val la = doubleArrayOf(50.1022829, 50.1020076, 50.1006313, 50.0987800)
        val lo = doubleArrayOf(8.6982122, 8.6956695, 8.6914960, 8.6875156)
        assertEquals("BFoz5xJ67i1B1B7PzIhaxL7Y", FlexPolyline.encode(la, lo))
        val (dla, dlo) = FlexPolyline.decode("BFoz5xJ67i1B1B7PzIhaxL7Y")
        for (i in la.indices) {
            assertEquals(la[i], dla[i], 1e-5)
            assertEquals(lo[i], dlo[i], 1e-5)
        }
        // Google Encoded Polyline の公式例
        val (gla, glo) = GooglePolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(38.5, 40.7, 43.252), gla.toList())
        assertEquals(listOf(-120.2, -120.95, -126.453), glo.toList())
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", GooglePolyline.encode(gla, glo))
    }

    private fun line(fromS: Double, toS: Double, nb: Boolean = false): Pair<DoubleArray, DoubleArray> {
        val n = 40
        val pts = (0..n).map { k ->
            val s = fromS + (toS - fromS) * k / n
            if (nb) net.nbPoint(s) else net.sbPoint(s)
        }
        return pts.map { it.lat }.toDoubleArray() to pts.map { it.lon }.toDoubleArray()
    }

    @Test
    fun trafficIsMappedOnlyToMatchingCarriageway() {
        val (jla, jlo) = line(20_000.0, 23_000.0)
        // 対向車線（上り）の同じ区間の渋滞（北行きなので s が減る向き）
        val (ola, olo) = line(23_000.0, 20_000.0, nb = true)
        // TomTom で座標列が逆向きに届いた下りの事故
        val (ala, alo) = line(36_500.0, 36_000.0)
        val items = listOf(
            TrafficItem(jla, jlo, TrafficKind.JAM, 0.2, delaySec = 400.0, description = "渋滞"),
            TrafficItem(ola, olo, TrafficKind.JAM, 0.1, description = "上り渋滞"),
            TrafficItem(ala, alo, TrafficKind.ACCIDENT, null, description = "事故")
        )
        val p0 = net.sbPoint(1000.0)
        val p = graph.nearby(p0.lat, p0.lon, 20.0) { it.wayId in net.sbWayIds }.first()
        val route = RouteAheadBuilder().build(graph, p.edge, p.offset)
        val (cla, clo) = line(1000.0, 59_000.0)
        val data = TrafficData("TomTom", 0L, items, true, cla, clo)
        val et = TrafficMapper.annotate(graph, data)
        val rt = TrafficMapper.forRoute(route, graph, et, 50.0)
        fun ratioAt(d: Double) = rt.ratio[(d / 50).toInt()]
        assertEquals(0.2, ratioAt(20_500.0), 1e-9) // ルート始点 1km → 道路上 21.5km
        assertEquals(1.0, ratioAt(10_000.0), 1e-9) // 情報なし＝順調（TomTom は全範囲が対象）
        val jam = rt.events.first { it.kind == TrafficKind.JAM }
        println(String.format(Locale.US, "渋滞の対応づけ: %.0f〜%.0f m（期待 19000〜22000）", jam.start, jam.end))
        assertEquals(19_000.0, jam.start, 120.0)
        assertEquals(22_000.0, jam.end, 120.0)
        assertTrue("上りの渋滞は載らない", rt.events.none { it.description == "上り渋滞" })
        assertTrue("逆向きの座標列でも下りの事故として載る", rt.events.any { it.kind == TrafficKind.ACCIDENT && it.start in 34_800.0..35_700.0 })
    }

    @Test
    fun providerResponsesParse() {
        val here = """{"sourceUpdated":"2026-10-29T10:00:00Z","results":[{"location":{"description":"中央道","length":1200.0,
            "shape":{"links":[{"points":[{"lat":35.0,"lng":138.0},{"lat":35.005,"lng":138.0}],"length":555.0},{"points":[{"lat":35.005,"lng":138.0},{"lat":35.01,"lng":138.0}],"length":555.0}]}},
            "currentFlow":{"speed":5.5,"speedUncapped":5.5,"freeFlow":27.0,"jamFactor":8.2,"confidence":0.9,"traversability":"open"}}]}"""
        val hi = HereTrafficProvider.parseFlow(here)
        assertEquals(1, hi.size)
        assertEquals(3, hi[0].lat.size)
        assertEquals(TrafficKind.JAM, hi[0].kind)
        assertEquals(5.5 / 27.0, hi[0].ratio!!, 1e-9)

        val tomtom = """{"incidents":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[138.0,35.0],[138.0,35.01]]},
            "properties":{"id":"a1","iconCategory":6,"magnitudeOfDelay":3,"events":[{"description":"渋滞","code":101,"iconCategory":6}],
            "from":"甲府昭和IC","to":"双葉JCT","length":1100.0,"delay":300,"roadNumbers":["E20"]}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[138.1,35.1]},"properties":{"id":"a2","iconCategory":8,"magnitudeOfDelay":4,"events":[{"description":"通行止め"}],"delay":null,"length":null}}]}"""
        val ti = TomTomTrafficProvider.parse(tomtom)
        assertEquals(2, ti.size)
        val jam = ti[0].second
        assertEquals(TrafficKind.JAM, jam.kind)
        assertEquals(35.0, jam.lat[0], 1e-9)
        assertEquals(138.0, jam.lon[0], 1e-9)
        val tFree = 1100.0 / 22.2
        assertEquals(tFree / (tFree + 300), jam.ratio!!, 1e-9)
        assertTrue(jam.description!!.contains("甲府昭和IC→双葉JCT"))
        assertEquals(TrafficKind.CLOSURE, ti[1].second.kind)
        assertEquals(1, ti[1].second.lat.size)

        val poly = GooglePolyline.encode(doubleArrayOf(35.0, 35.01, 35.02, 35.03), doubleArrayOf(138.0, 138.0, 138.0, 138.0))
        val google = """{"routes":[{"distanceMeters":3300,"duration":"600s","staticDuration":"150s","polyline":{"encodedPolyline":"${poly.replace("\\", "\\\\")}"},
            "travelAdvisory":{"speedReadingIntervals":[{"endPolylinePointIndex":1,"speed":"NORMAL"},{"startPolylinePointIndex":1,"endPolylinePointIndex":3,"speed":"TRAFFIC_JAM"}]}}]}"""
        val (gi, delay) = GoogleRoutesTrafficProvider.parse(google)
        assertEquals(450.0, delay!!, 1e-9)
        assertEquals(2, gi.size)
        assertEquals(TrafficKind.FLOW, gi[0].kind)
        assertEquals(35.0, gi[0].lat[0], 1e-9)
        assertEquals(TrafficKind.JAM, gi[1].kind)
        assertTrue("遅れ 450 秒に合わせて渋滞が強められる", gi[1].ratio!! < 0.2)
    }

    @Test
    fun userCameraCsvParsesFlexibleFormats() {
        val csv = """
            # 自分で登録したオービス
            緯度,経度,方位,制限速度,名称
            35.6812,139.7671,90,100,首都高 テスト
            "35.70","139.70",,,
            139.80,35.60,180,80,緯度経度が逆
            abc,def
        """.trimIndent()
        val r = UserCameras.parse(csv)
        assertEquals(3, r.entries.size)
        assertEquals(1, r.skipped)
        val a = r.entries[0]
        assertEquals(35.6812, a.lat, 1e-9)
        assertEquals(90.0, a.bearing!!, 1e-9)
        assertEquals(100, a.maxspeed)
        assertEquals("首都高 テスト", a.name)
        assertNull(r.entries[1].bearing)
        assertEquals(35.60, r.entries[2].lat, 1e-9)
        assertTrue(a.id < 0)
        val again = UserCameras.parse(UserCameras.toCsv(r.entries))
        assertEquals(r.entries.map { it.id }, again.entries.map { it.id })
        val node = a.toNode()
        assertEquals("speed_camera", node.tags["highway"])
        assertEquals("90", node.tags["user:bearing"])
        // 見出しなし・位置だけ
        assertEquals(1, UserCameras.parse("34.9,137.1").entries.size)
    }

    @Test
    fun cameraAlertsAt1kmAnd500m() {
        val planner = CameraAlertPlanner()
        fun cam(d: Double) = FacilityView(
            "c1", FacilityType.CAMERA, "速度取締機", "速度取締機", d, 0L, 0.0, emptyList(), false, null, emptyList(),
            0.0, 0.0, null, null, null, false, 1.0, maxspeedKmh = 100
        )
        assertNull(planner.update(listOf(cam(1500.0)), 100.0))
        val a1 = planner.update(listOf(cam(1150.0)), 100.0)
        assertNotNull("100km/h では 1.2km 手前で案内", a1)
        assertTrue(a1!!.text.contains("1キロ先") && a1.text.contains("100キロ"))
        assertNull(planner.update(listOf(cam(900.0)), 100.0))
        val a2 = planner.update(listOf(cam(560.0)), 100.0)
        assertNotNull(a2)
        assertEquals(1, a2!!.stage)
        assertNull(planner.update(listOf(cam(300.0)), 100.0))
        // 初めて見つかったのが 400m 手前なら 500m の案内だけ
        val p2 = CameraAlertPlanner()
        val b = p2.update(listOf(cam(400.0).let { FacilityView("c2", it.type, it.name, it.displayName, 400.0, 0L, 0.0, emptyList(), false, null, emptyList(), 0.0, 0.0, null, null, null, false, 1.0) }), 80.0)
        assertEquals(1, b!!.stage)
        // 低速（渋滞・停車中）は読み上げない
        assertNull(CameraAlertPlanner().update(listOf(cam(800.0)), 10.0))
    }
}
