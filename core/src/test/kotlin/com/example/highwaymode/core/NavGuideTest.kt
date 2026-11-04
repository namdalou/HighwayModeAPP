package com.example.highwaymode.core

import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwayEngine
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.route.NavGuide
import com.example.highwaymode.core.route.NavHint
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.sim.SimParams
import com.example.highwaymode.core.sim.SimPath
import com.example.highwaymode.core.sim.SimSink
import com.example.highwaymode.core.sim.TraceSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * カーナビ（Google マップ）の案内に合わせる: 案内の文と前方経路の照合、JCT での乗り換え先の選び方、降りる IC。
 * 合成した道路網（TestNetwork）: テスト自動車道 E99 を南下、30km の JCT「中央」で別線自動車道 E98 へ分かれる。
 */
class NavGuideTest {

    @Test
    fun textMatching() {
        val n = NavGuide.normalize("豊田ＪＣＴ で 東名高速道路/E1 東京 方面へ")
        assertEquals("豊田JCTで東名高速道路E1東京方面へ", n)
        // 道路名: 名前全体 > 核（東名）だけ。新東名の文では、新東名の方が高い
        val shin = NavGuide.normalize("新東名高速道路 に入る")
        assertTrue(NavGuide.roadNameScore(shin, "新東名高速道路") > NavGuide.roadNameScore(shin, "東名高速道路"))
        assertTrue(NavGuide.roadNameScore(n, "東名高速道路") >= 10)
        assertEquals(0, NavGuide.roadNameScore(n, "伊勢湾岸自動車道"))
        // 路線番号は前後が英数字でないときだけ（E1 と E1A を取り違えない）
        assertTrue(NavGuide.containsRef(n, "E1"))
        assertFalse(NavGuide.containsRef(NavGuide.normalize("新東名高速道路/E1A"), "E1"))
        assertTrue(NavGuide.containsRef(NavGuide.normalize("新東名高速道路/E1A"), "E1A"))
        // 出口の言葉
        assertTrue(NavHint("豊田IC で 降りる", 5000.0, 0.0).exitWords)
        assertFalse(NavHint("豊田JCT で 東名高速道路 に入る", 5000.0, 0.0).exitWords)
        assertTrue(NavGuide.transferWords(NavGuide.normalize("東名高速道路 に入る")))
    }

    private fun startOnSouthbound(net: TestNetwork, s: Double): Pair<Int, Double> {
        val g = net.graph()
        val p = net.sbPoint(s)
        val m = g.nearby(p.lat, p.lon, 20.0) { it.wayId in net.sbWayIds }.first()
        return m.edge to m.offset
    }

    @Test
    fun junctionTransferFollowsNavRoad() {
        val net = TestNetwork().build()
        val g = net.graph()
        val (edge, off) = startOnSouthbound(net, 500.0)
        val builder = RouteAheadBuilder()
        val base = builder.build(g, edge, off)
        // いつもは同じ路線（テスト自動車道）を進み、45km のスマートIC・50km の南IC が並ぶ
        assertTrue(base.facilities.any { it.facility.baseName.startsWith("テスト南") })

        for (text in listOf("29.5 km 中央JCT で 別線自動車道 に入る", "別線自動車道/E98 方面")) {
            val hint = NavHint(text, 29_500.0, 0.0)
            val plan = NavGuide.plan(base, hint, hint.distanceNow(0.0))
            assertNotNull("乗り換えの案内として扱う: $text", plan.chooser)
            val rt = builder.build(g, edge, off, plan.chooser)
            val m = NavGuide.match(rt, hint, plan, hint.distanceNow(0.0))
            println("ナビ「$text」: 乗り換え ${m.transferText}・JCT ${m.transferId != null}・施設 ${rt.facilities.map { it.facility.displayName }}")
            assertTrue("分岐で別線へ進む", plan.chooser!!.changed)
            assertTrue("別線自動車道へ乗り換える", rt.roadChanges.any { it.toName == "別線自動車道" })
            // 乗り換えた先にテスト自動車道の南IC・スマートIC は無い
            assertFalse(rt.facilities.any { it.facility.baseName.startsWith("テスト南") && it.distance > 31_000.0 })
            assertTrue(m.matched)
            assertEquals("別線自動車道", m.transferText?.substringBefore(" "))
            assertNull("乗り換えなので降りる IC は無い", m.exitId)
        }

        // 今の路線を道なりに進む案内なら、いつもどおり（変えない）
        val stay = NavHint("50 km テスト自動車道 を道なりに進む", 50_000.0, 0.0)
        val plan2 = NavGuide.plan(base, stay, 50_000.0)
        val rt2 = builder.build(g, edge, off, plan2.chooser)
        assertFalse(plan2.chooser?.changed ?: false)
        assertTrue(rt2.facilities.any { it.facility.baseName.startsWith("テスト南") })
    }

    @Test
    fun exitIcIsFoundByName() {
        val net = TestNetwork().build()
        val g = net.graph()
        val (edge, off) = startOnSouthbound(net, 500.0)
        val builder = RouteAheadBuilder()
        val base = builder.build(g, edge, off)
        val hint = NavHint("49 km テスト南IC で 降りる", 49_000.0, 0.0)
        val plan = NavGuide.plan(base, hint, 49_000.0)
        val rt = builder.build(g, edge, off, plan.chooser)
        val m = NavGuide.match(rt, hint, plan, 49_000.0)
        println("ナビ「${hint.text}」: 降りる ${m.exitName}（${m.exitDistance?.let { "%.1fkm".format(it / 1000) }}）")
        assertEquals("テスト南IC", m.exitName)
        assertNotNull(m.exitDistance)
        assertTrue(m.exitDistance!! in 48_000.0..51_000.0)
        // 名前の無い出口の案内でも、案内地点の近く（1.2km 以内）の IC を降りる IC にする
        val noName = NavHint("出口を出る", 9_400.0, 0.0)
        val plan3 = NavGuide.plan(base, noName, 9_400.0)
        val m3 = NavGuide.match(builder.build(g, edge, off, plan3.chooser), noName, plan3, 9_400.0)
        assertEquals("テスト北IC", m3.exitName)
    }

    private class Sink(val e: HighwayEngine) : SimSink {
        override fun gyro(t: Long, x: Double, y: Double, z: Double) = e.onGyro(t, x, y, z)
        override fun accel(t: Long, x: Double, y: Double, z: Double) = e.onAccel(t, x, y, z)
        override fun gravity(t: Long, x: Double, y: Double, z: Double) = e.onGravity(t, x, y, z)
        override fun rotation(t: Long, r: DoubleArray, fieldUt: Double, expectedUt: Double) = e.onRotationMatrix(t, r, fieldUt, expectedUt)
        override fun gps(fix: GpsFix) = e.onGps(fix)
        override fun vehicleSpeed(t: Long, rawMs: Double) = e.onVehicleSpeed(t, rawMs, null)
        override fun vehicleGyro(t: Long, x: Double, y: Double, z: Double) = e.onVehicleGyro(t, x, y, z)
    }

    @Test
    fun engineShowsFacilitiesUpToExitIc() {
        val net = TestNetwork().build()
        val graph = net.graph()
        val (edge, off) = startOnSouthbound(net, 500.0)
        val route = RouteAheadBuilder().build(graph, edge, off)
        val sim = TraceSimulator(SimPath.fromRoute(route, graph), SimParams(driverRatio = 1.0, initialSpeedKmh = 95.0), seed = 5)
        val e = HighwayEngine()
        e.setGraph(graph)
        val sink = Sink(e)
        var sentAt = -1.0
        var withNav: List<String> = emptyList()
        var exitFlag = false
        var navText: String? = null
        // 5km 走ってから、ナビが「テスト南IC で降りる」と案内（距離は通知の値）
        while (sim.advance(0.25, sink) && sim.s < 12_000.0) {
            val snap = e.onTick(sim.t, sim.wallMs())
            if (sentAt < 0 && sim.s > 5_000.0 && snap.status == EngineStatus.ON_HIGHWAY) {
                sentAt = sim.s
                e.setNavInstruction("テスト南IC で 降りる", 50_000.0 - sim.s, sim.t)
            }
            if (sentAt > 0 && sim.s > sentAt + 2_000.0) {
                withNav = snap.facilities.map { it.displayName }
                exitFlag = snap.facilities.lastOrNull()?.navExit == true
                navText = snap.nav?.exitName
                break
            }
        }
        println("ナビ連携: 施設 $withNav・降りる ${navText}")
        assertTrue("最後がテスト南IC（降りる IC）", withNav.lastOrNull() == "テスト南IC" && exitFlag)
        assertFalse("降りる IC の先（本線料金所）は出さない", withNav.any { it.contains("料金所") })
        assertEquals("テスト南IC", navText)
        // ナビが終わったら元に戻る（本線料金所も並ぶ）
        e.setNavInstruction(null, null, sim.t)
        var after: List<String> = emptyList()
        var guard = 0
        while (sim.advance(0.25, sink) && guard++ < 40) after = e.onTick(sim.t, sim.wallMs()).facilities.map { it.displayName }
        println("ナビ終了後: $after")
        assertTrue(after.isEmpty() || after.any { it.contains("料金所") } || after.size > withNav.size)
        assertTrue(FacilityType.IC.isExit)
    }
}
