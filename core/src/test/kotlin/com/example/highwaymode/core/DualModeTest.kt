package com.example.highwaymode.core

import com.example.highwaymode.core.dual.Box
import com.example.highwaymode.core.dual.DualGeometry
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.dual.HighwayPresence
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwayEngine
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.model.GpsFix
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
 * Spectrum との 2 画面表示: 自動切替の判定（HighwayPresence）と画面の区画割り（DualGeometry）。
 */
class DualModeTest {

    // ================================================================== 自動切替の判定

    /** 状態の並び（秒ごと）を流して、切り替わった時刻を返す */
    private fun run(p: HighwayPresence, seq: List<Pair<EngineStatus, Int>>, step: Double = 1.0): List<Pair<Double, Boolean>> {
        val out = ArrayList<Pair<Double, Boolean>>()
        var t = 0.0
        for ((st, sec) in seq) {
            val n = (sec / step).toInt()
            repeat(n) {
                if (p.update(st, t)) out.add(t to p.active)
                t += step
            }
        }
        return out
    }

    @Test
    fun presence_entersAfter5s_exitsAfter15s() {
        val p = HighwayPresence()
        val ch = run(p, listOf(EngineStatus.OFF_HIGHWAY to 10, EngineStatus.ON_HIGHWAY to 60, EngineStatus.OFF_HIGHWAY to 30))
        assertEquals(2, ch.size)
        assertEquals(15.0, ch[0].first, 0.01)      // 10 秒目から高速上 → 5 秒後
        assertTrue(ch[0].second)
        assertEquals(85.0, ch[1].first, 0.01)      // 70 秒目から高速外 → 15 秒後
        assertFalse(ch[1].second)
    }

    @Test
    fun presence_ignoresShortMatchesAndShortExits() {
        val p = HighwayPresence()
        // 高架下の一般道で 3 秒だけ高速道路に当てはまった → 入らない
        assertTrue(run(p, listOf(EngineStatus.OFF_HIGHWAY to 5, EngineStatus.ON_HIGHWAY to 3, EngineStatus.OFF_HIGHWAY to 20)).isEmpty())
        // 高速上 → ランプ・料金所で 10 秒だけ外れる → 戻る（切り替わらない）
        val p2 = HighwayPresence()
        val ch2 = run(p2, listOf(EngineStatus.ON_HIGHWAY to 20, EngineStatus.OFF_HIGHWAY to 10, EngineStatus.ON_HIGHWAY to 30))
        assertEquals(1, ch2.size)
        assertTrue(p2.active)
        // 高速外が続く途中で 1 秒だけ当てはまっても、外にいた時間は通算する（側道を並走したとき）
        val p3 = HighwayPresence()
        val ch3 = run(p3, listOf(EngineStatus.ON_HIGHWAY to 20, EngineStatus.OFF_HIGHWAY to 10, EngineStatus.ON_HIGHWAY to 1, EngineStatus.OFF_HIGHWAY to 10))
        assertEquals(2, ch3.size)
        assertEquals(35.0, ch3[1].first, 0.01)     // 20 秒目に外れてから 15 秒
        assertFalse(p3.active)
    }

    @Test
    fun presence_restStopGeneralRoadAndUnknown() {
        // SA/PA で停車中は対象エリア内のまま
        val p = HighwayPresence()
        run(p, listOf(EngineStatus.ON_HIGHWAY to 30, EngineStatus.REST_STOP to 600))
        assertTrue(p.active)
        // 一般道モードは即座に対象外
        assertTrue(p.update(EngineStatus.GENERAL_ROAD_MODE, 1000.0))
        assertFalse(p.active)
        // 測位待ち・読み込み中は 60 秒までは保つ
        val q = HighwayPresence()
        run(q, listOf(EngineStatus.ON_HIGHWAY to 30, EngineStatus.LOADING_MAP to 59))
        assertTrue(q.active)
        q.update(EngineStatus.LOADING_MAP, 30.0 + 60.0)
        assertFalse(q.active)
        // 測位待ちの間は入らない
        val w = HighwayPresence()
        assertTrue(run(w, listOf(EngineStatus.WAITING_GPS to 120)).isEmpty())
    }

    // ================================================================== 実際のエンジンでの判定（走行シミュレーション）

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
    fun presence_withEngine_staysOnThroughTunnelAndJunction_thenLeavesAfterExit() {
        val net = TestNetwork().build()
        val graph = net.graph()
        val p0 = net.sbPoint(500.0)
        val start = graph.nearby(p0.lat, p0.lon, 20.0) { it.wayId in net.sbWayIds }.first()
        val route = RouteAheadBuilder().build(graph, start.edge, start.offset)
        val sim = TraceSimulator(SimPath.fromRoute(route, graph), SimParams(driverRatio = 1.05, initialSpeedKmh = 95.0), seed = 3)
        val e = HighwayEngine()
        e.setGraph(graph)
        val sink = Sink(e)
        val presence = HighwayPresence()
        val t0 = sim.t
        var firstOn = Double.NaN
        var activatedAt = Double.NaN
        var deactivations = 0
        var tunnelTicks = 0
        // 下り 0.5km → 47km（JCT 30km・トンネル 35〜40km・スマートIC 45km を通過）
        while (sim.advance(0.25, sink) && sim.s < 46_500.0) {
            val snap = e.onTick(sim.t, sim.wallMs())
            val tSec = (sim.t - t0) * 1e-9
            if (snap.status == EngineStatus.ON_HIGHWAY && firstOn.isNaN()) firstOn = tSec
            if (presence.update(snap.status, tSec)) {
                if (presence.active) activatedAt = tSec else deactivations++
            }
            if (sim.inTunnel && presence.active) tunnelTicks++
        }
        println("2画面への自動切替: 高速道路と判定 %.1f 秒後 → 切替 %.1f 秒後（その後の解除 %d 回、トンネル内 %d tick）".format(firstOn, activatedAt, deactivations, tunnelTicks))
        assertFalse(activatedAt.isNaN())
        assertTrue("判定から 5〜6 秒で切替", activatedAt - firstOn in 4.9..6.5)
        assertEquals("高速道路を走っている間は戻らない", 0, deactivations)
        assertTrue("トンネル内も 2 画面のまま", tunnelTicks > 100)

        // IC を出て一般道へ（いまの位置から、道路の無い東へ時速 40km で離れていく）
        var p = sim.trueLatLon
        var t = sim.t
        var wall = sim.wallMs()
        val exitT = (t - t0) * 1e-9
        var leftAt = Double.NaN
        for (i in 0 until 240) {             // 60 秒
            t += 250_000_000L
            wall += 250
            if (i % 4 == 0) {
                p = Geo.destination(p.lat, p.lon, 90.0, 11.1)
                e.onGps(GpsFix(t, wall, p.lat, p.lon, 5.0, 11.1, 0.5, 90.0, 5.0))
            }
            val snap = e.onTick(t, wall)
            val tSec = (t - t0) * 1e-9
            if (presence.update(snap.status, tSec) && !presence.active) leftAt = tSec
        }
        println("高速道路を離れ始めてから音楽画面に戻るまで: %.1f 秒".format(leftAt - exitT))
        assertFalse(presence.active)
        // 道路から外れたと判定されるまで（数秒）＋ 15 秒
        assertTrue("離れ始めて 15〜30 秒で音楽画面へ", leftAt - exitT in 15.0..30.0)
    }

    // ================================================================== 画面の区画割り

    private fun Box.inside(o: Box) = l >= o.l - 0.01f && t >= o.t - 0.01f && r <= o.r + 0.01f && b <= o.b + 0.01f
    private fun overlap(a: Box, b: Box) = a.l < b.r - 0.01f && b.l < a.r - 0.01f && a.t < b.b - 0.01f && b.t < a.b - 0.01f

    @Test
    fun geometry_carSplitKeepsSpectrumPortraitAndHighwayWide() {
        // 一般的な車載画面の解像度（幅, 高さ, 密度）
        val screens = listOf(
            Triple(800f, 480f, 1.0f), Triple(1280f, 720f, 1.25f), Triple(1920f, 720f, 1.5f),
            Triple(1024f, 600f, 1.0f), Triple(2400f, 900f, 2.0f), Triple(1280f, 480f, 1.0f)
        )
        for ((w, h, d) in screens) {
            val full = Box(0f, 0f, w, h)
            val f = DualGeometry.car(w, h, 0f, w, 0f, DualLayout.SPLIT, true, d)
            val m = f.music!!
            val hw = f.highway!!
            val bar = f.bar!!
            listOf(m, hw, bar).forEach { assertTrue("$w×$h: 画面内 $it", it.inside(full)) }
            assertFalse(overlap(m, hw)); assertFalse(overlap(m, bar)); assertFalse(overlap(hw, bar))
            // 左の Spectrum は縦レイアウト（高さ > 幅）。右のハイウェイモードは画面の半分以上
            assertTrue("$w×$h: Spectrum は縦長 $m", m.h > m.w * 1.2f)
            assertTrue("$w×$h: ハイウェイ側は半分以上 $hw", hw.w >= w * 0.55f)
            // 下部バーは押しやすい高さ（56dp 以上）で、画面の 22% 以下
            assertTrue(bar.h >= minOf(56f * d, h * 0.22f) - 0.01f && bar.h <= h * 0.22f + 0.01f)
            println("  %4.0f×%3.0f: Spectrum %s（%.0f%%）/ ハイウェイ %s / バー高さ %.0f".format(w, h, m, m.w / w * 100, hw, bar.h))
        }
    }

    @Test
    fun geometry_carModesAndVisibleArea() {
        // 高速道路外: バー無しで Spectrum 全画面（今までの Spectrum と同じ）
        val off = DualGeometry.car(1280f, 720f, 0f, 1280f, 0f, DualLayout.MUSIC, false, 1.25f)
        assertNull(off.bar); assertNull(off.highway)
        assertEquals(720f, off.music!!.b, 0.01f)
        // 音楽カードが横に並んで表示範囲が狭いとき: その範囲に収める。上端はハイウェイ側だけ下げる
        val f = DualGeometry.car(1280f, 720f, 0f, 860f, 64f, DualLayout.SPLIT, true, 1.25f)
        assertEquals(860f, f.highway!!.r, 0.01f)
        assertEquals(64f, f.highway!!.t, 0.01f)
        assertEquals(0f, f.music!!.t, 0.01f)
        assertEquals(860f, f.bar!!.r, 0.01f)
        // おかしな表示範囲（画面外・狭すぎ）は無視して全体を使う
        val bad = DualGeometry.car(1280f, 720f, -10f, 300f, 0f, DualLayout.HIGHWAY, true, 1.25f)
        assertEquals(0f, bad.highway!!.l, 0.01f)
        assertEquals(1280f, bad.highway!!.r, 0.01f)
        // ハイウェイだけ
        val hw = DualGeometry.car(800f, 480f, 0f, 800f, 0f, DualLayout.HIGHWAY, true, 1f)
        assertNull(hw.music)
        assertNotNull(hw.bar)
    }

    @Test
    fun geometry_phonePortraitStacksAndLandscapeSplits() {
        val port = DualGeometry.phone(1080f, 2200f, DualLayout.SPLIT, 2.75f)
        assertTrue(port.stacked)
        val m = port.music!!
        val hw = port.highway!!
        assertTrue("縦画面: 上にハイウェイ、下に音楽", hw.b <= m.t)
        assertTrue("音楽は横レイアウトが収まる高さ", m.h >= 1080f * DualGeometry.LANDSCAPE_H_PER_W * 0.9f)
        assertTrue(hw.h > m.h)
        val land = DualGeometry.phone(2200f, 1080f, DualLayout.SPLIT, 2.75f)
        assertFalse(land.stacked)
        assertTrue(land.music!!.r <= land.highway!!.l)
        for (lay in DualLayout.values()) {
            val f = DualGeometry.phone(1080f, 2200f, lay, 2.75f)
            assertNotNull("スマホは常にバーを表示", f.bar)
        }
    }
}
