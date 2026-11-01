package com.example.highwaymode.core

import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.PowerLevel
import com.example.highwaymode.core.engine.PowerPolicy
import com.example.highwaymode.core.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 電池の節約（GPS の間隔・センサー）の判定。
 * 高速道路上・付近を走行中は必ず毎秒測位のまま（IC の判定・上下線の判定を損なわない）であることを確かめる。
 */
class PowerPolicyTest {

    private fun input(
        t: Double, status: EngineStatus, near: Double?, still: Boolean,
        demo: Boolean = false, motion: Double = Double.NaN
    ) = PowerPolicy.Input(t, status, demo, near, 10.0, still, motion)

    @Test
    fun highwayAndNearbyStayActive() {
        val p = PowerPolicy()
        // 高速道路上は渋滞で止まっていても毎秒
        for (t in 0..900) p.update(input(t.toDouble(), EngineStatus.ON_HIGHWAY, 0.0, still = true))
        assertEquals(PowerLevel.ACTIVE, p.level)
        // 高速道路の近く（3km 以内）を走行中は毎秒
        for (t in 0..300) p.update(input(t.toDouble(), EngineStatus.OFF_HIGHWAY, 2500.0, still = false))
        assertEquals(PowerLevel.ACTIVE, p.level)
        // 地図データが不明・測位待ち・読み込み中・デモは毎秒
        p.update(input(301.0, EngineStatus.OFF_HIGHWAY, null, still = false))
        assertEquals(PowerLevel.ACTIVE, p.level)
        p.update(input(302.0, EngineStatus.WAITING_GPS, 99_999.0, still = true))
        assertEquals(PowerLevel.ACTIVE, p.level)
        p.update(input(303.0, EngineStatus.LOADING_MAP, 99_999.0, still = true))
        assertEquals(PowerLevel.ACTIVE, p.level)
        p.update(input(304.0, EngineStatus.OFF_HIGHWAY, 99_999.0, still = false, demo = true))
        assertEquals(PowerLevel.ACTIVE, p.level)
    }

    @Test
    fun farFromHighwayRelaxes() {
        val p = PowerPolicy()
        p.update(input(0.0, EngineStatus.OFF_HIGHWAY, 8000.0, still = false))
        assertEquals(PowerLevel.FAR, p.level)
        assertTrue("10 秒以上の間隔（GPS が合間に休める）", p.level.gpsIntervalMs >= 10_000)
        assertTrue("センサーは止める", !p.level.sensors)
        // 高速道路に 3km まで近づいたら毎秒に戻る
        p.update(input(10.0, EngineStatus.OFF_HIGHWAY, 2950.0, still = false))
        assertEquals(PowerLevel.ACTIVE, p.level)
        p.update(input(20.0, EngineStatus.OFF_HIGHWAY, 5000.0, still = false))
        assertEquals(PowerLevel.FAR, p.level)
        // 停車が 2 分続いたら 1 分ごと
        for (t in 30..150) p.update(input(t.toDouble(), EngineStatus.OFF_HIGHWAY, 5000.0, still = true))
        assertEquals(PowerLevel.FAR_PARKED, p.level)
        p.update(input(151.0, EngineStatus.OFF_HIGHWAY, 5000.0, still = false))
        assertEquals(PowerLevel.FAR, p.level)
    }

    @Test
    fun parkedNearHighwaySlowsDownAndWakesOnMotion() {
        val p = PowerPolicy()
        for (t in 0..59) p.update(input(t.toDouble(), EngineStatus.OFF_HIGHWAY, 800.0, still = true))
        assertEquals(PowerLevel.ACTIVE, p.level)
        p.update(input(60.0, EngineStatus.OFF_HIGHWAY, 800.0, still = true))
        assertEquals(PowerLevel.STILL, p.level)
        assertTrue("動き出しをすぐ検知できるようセンサーは使う", p.level.sensors)
        for (t in 61..600) p.update(input(t.toDouble(), EngineStatus.OFF_HIGHWAY, 800.0, still = true))
        assertEquals(PowerLevel.PARKED, p.level)
        // 動き出しを検知 → 確かめる間は毎秒
        p.update(input(601.0, EngineStatus.OFF_HIGHWAY, 800.0, still = true, motion = 601.0))
        assertEquals(PowerLevel.ACTIVE, p.level)
        // スマホを持って歩いただけ（GPS の速度は停車）→ 確認が終われば元の間隔へすぐ戻る
        p.update(input(617.0, EngineStatus.OFF_HIGHWAY, 800.0, still = true, motion = 601.0))
        assertEquals(PowerLevel.PARKED, p.level)
        // 走り出した → 毎秒のまま
        p.update(input(620.0, EngineStatus.OFF_HIGHWAY, 800.0, still = false, motion = 619.0))
        p.update(input(640.0, EngineStatus.OFF_HIGHWAY, 800.0, still = false, motion = 619.0))
        assertEquals(PowerLevel.ACTIVE, p.level)
    }

    @Test
    fun restStopSlowsAfterAMinute() {
        val p = PowerPolicy()
        for (t in 0..59) p.update(input(t.toDouble(), EngineStatus.REST_STOP, 0.0, still = true))
        assertEquals(PowerLevel.ACTIVE, p.level)
        p.update(input(60.0, EngineStatus.REST_STOP, 0.0, still = true))
        assertEquals(PowerLevel.STILL, p.level)
        // 出発（GPS の速度・車速）→ すぐ毎秒
        p.update(input(61.0, EngineStatus.REST_STOP, 0.0, still = false))
        assertEquals(PowerLevel.ACTIVE, p.level)
    }

    @Test
    fun nearestDistanceOnGraph() {
        val net = TestNetwork().build()
        val g = net.graph()
        val on = net.sbPoint(15_000.0)
        val d0 = g.nearestDistance(on.lat, on.lon, 4500.0)
        assertNotNull(d0)
        assertTrue("本線上は 0m 付近: $d0", d0!! < 5.0)
        // 東へ 2km 離れた地点
        val p2 = Geo.destination(on.lat, on.lon, 90.0, 2000.0)
        val d2 = g.nearestDistance(p2.lat, p2.lon, 4500.0)!!
        assertTrue("約 2km: $d2", d2 in 1500.0..2100.0)
        // 東へ 8km 離れた地点は範囲外
        val p8 = Geo.destination(on.lat, on.lon, 90.0, 8000.0)
        assertNull(g.nearestDistance(p8.lat, p8.lon, 4500.0))
    }
}
