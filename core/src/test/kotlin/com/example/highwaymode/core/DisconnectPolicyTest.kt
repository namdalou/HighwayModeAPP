package com.example.highwaymode.core

import com.example.highwaymode.core.engine.DisconnectPolicy
import com.example.highwaymode.core.engine.DisconnectPolicy.Action
import com.example.highwaymode.core.engine.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android Auto との接続が切れたあと、常駐を止めるかどうかの判定 */
class DisconnectPolicyTest {

    private val grace = 180_000L

    private fun decide(
        car: Boolean = false, phone: Boolean = false, since: Long = grace + 1, g: Long = grace,
        status: EngineStatus? = EngineStatus.OFF_HIGHWAY, speed: Double? = 0.0, age: Double? = 1.0
    ) = DisconnectPolicy.decide(car, phone, since, g, status, speed, age)

    @Test
    fun waitsDuringTheGracePeriodThenStops() {
        assertEquals(Action.WAIT, decide(since = 10_000))
        assertEquals(Action.WAIT, decide(since = grace - 1))
        assertEquals(Action.STOP, decide(since = grace))
        assertEquals(Action.STOP, decide(since = grace * 10))
    }

    @Test
    fun keepsWhenTheCarReconnectsOrThePhoneScreenIsOpen() {
        assertEquals(Action.KEEP, decide(car = true, since = 5_000))
        assertEquals(Action.KEEP, decide(car = true))
        assertEquals(Action.KEEP, decide(phone = true, since = 5_000))
        assertEquals(Action.KEEP, decide(phone = true))
    }

    @Test
    fun neverStopsWhenTheSettingIsOff() {
        assertEquals(Action.KEEP, decide(g = -1))
        assertEquals(Action.KEEP, decide(g = -1, since = Long.MAX_VALUE / 2))
    }

    @Test
    fun keepsWaitingWhileDrivingOnAHighway() {
        // 高速道路を走っている最中に接続が切れた（ケーブルが抜けた など）→ 降りる・止まるまで待つ
        assertEquals(Action.WAIT, decide(status = EngineStatus.ON_HIGHWAY, speed = 95.0))
        assertEquals(Action.WAIT, decide(status = EngineStatus.ON_HIGHWAY, speed = DisconnectPolicy.MOVING_KMH))
        // 渋滞で止まっている・SA/PA に止めた・高速道路を降りた → 止める
        assertEquals(Action.STOP, decide(status = EngineStatus.ON_HIGHWAY, speed = 3.0))
        assertEquals(Action.STOP, decide(status = EngineStatus.REST_STOP, speed = 0.0))
        assertEquals(Action.STOP, decide(status = EngineStatus.OFF_HIGHWAY, speed = 60.0))
        assertEquals(Action.STOP, decide(status = EngineStatus.GENERAL_ROAD_MODE, speed = 60.0))
        // 位置情報が古い（スマホを車から持ち出した など）・速さが分からない → 走っているとはみなさない
        assertEquals(Action.STOP, decide(status = EngineStatus.ON_HIGHWAY, speed = 95.0, age = 120.0))
        assertEquals(Action.STOP, decide(status = EngineStatus.ON_HIGHWAY, speed = null))
        assertEquals(Action.STOP, decide(status = EngineStatus.ON_HIGHWAY, speed = 95.0, age = null))
        assertEquals(Action.STOP, decide(status = null, speed = null, age = null))
    }
}
