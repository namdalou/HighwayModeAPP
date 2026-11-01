package com.example.highwaymode.engine

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.example.highwaymode.core.engine.HighwayEngine
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ジャイロ・重力・加速度・回転ベクトル（コンパス）・磁気センサーをエンジンへ流す。
 * コールバックはエンジン用スレッド（handler）で呼ばれる。
 * 電池の節約中（高速道路から離れている・停車中）は、端末の「動き出し」の検知（significant motion）を使って
 * 動き出したらすぐ知らせる（[onMotion]）。
 */
class SensorInput(
    context: Context,
    private val handler: Handler,
    private val engine: HighwayEngine
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot = FloatArray(9)
    private val rotD = DoubleArray(9)
    private var magMagnitude = 0.0
    private var expectedFieldUt = 46.0
    private var hasGravitySensor = false
    private val lpGravity = DoubleArray(3)
    private var lpInit = false
    private var timeOffset = 0L
    private var timeOffsetValid = false
    private var lastDeclinationUpdate = 0L

    var hasGyro = false
        private set

    /** センサーを受信中 */
    var running = false
        private set

    /** 動き出しを検知したとき（エンジン用スレッドで呼ぶ） */
    var onMotion: (() -> Unit)? = null

    private val sigMotion: Sensor? = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    @Volatile
    private var motionArmed = false
    private val motionTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            motionArmed = false
            handler.post { onMotion?.invoke() }
        }
    }

    fun start() {
        if (running) return
        hasGyro = register(Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME)
        hasGravitySensor = register(Sensor.TYPE_GRAVITY, SensorManager.SENSOR_DELAY_GAME)
        register(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
        register(Sensor.TYPE_ROTATION_VECTOR, 100_000)
        register(Sensor.TYPE_MAGNETIC_FIELD, 100_000)
        running = true
    }

    fun stop() {
        sm.unregisterListener(this)
        running = false
        lpInit = false
    }

    /** 動き出しの検知を 1 回分だけ待つ（検知すると自動で解除される） */
    fun armMotion() {
        val s = sigMotion ?: return
        if (motionArmed) return
        motionArmed = try {
            sm.requestTriggerSensor(motionTrigger, s)
        } catch (e: Exception) {
            false
        }
    }

    fun disarmMotion() {
        val s = sigMotion ?: return
        if (!motionArmed) return
        try {
            sm.cancelTriggerSensor(motionTrigger, s)
        } catch (e: Exception) {
            // 解除済み
        }
        motionArmed = false
    }

    /** 動き出しの検知に対応した端末か */
    val hasMotionTrigger: Boolean get() = sigMotion != null

    private fun register(type: Int, rate: Int): Boolean {
        val s = sm.getDefaultSensor(type) ?: return false
        return sm.registerListener(this, s, rate, handler)
    }

    /** 現在地の磁気偏角・地磁気強度を更新（10 分に 1 回程度で十分） */
    fun updateGeomagnetic(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        if (now - lastDeclinationUpdate < 600_000L) return
        lastDeclinationUpdate = now
        val gf = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, now)
        engine.declinationDeg = gf.declination.toDouble()
        expectedFieldUt = gf.fieldStrength / 1000.0 // nT → μT
    }

    /** 一部端末でセンサー時刻が elapsedRealtimeNanos 基準でない場合の補正 */
    private fun fixTime(ts: Long): Long {
        if (!timeOffsetValid) {
            val now = SystemClock.elapsedRealtimeNanos()
            timeOffset = if (abs(now - ts) > 3_000_000_000L) now - ts else 0L
            timeOffsetValid = true
        }
        return ts + timeOffset
    }

    override fun onSensorChanged(e: SensorEvent) {
        try {
            dispatch(e)
        } catch (ex: Exception) {
            Log.w("SensorInput", "sensor dispatch failed: ${ex.message}")
        }
    }

    private fun dispatch(e: SensorEvent) {
        val t = fixTime(e.timestamp)
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> engine.onGyro(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            Sensor.TYPE_GRAVITY -> engine.onGravity(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasGravitySensor) {
                    // 重力センサーが無い端末: 加速度の低域通過で代用
                    if (!lpInit) {
                        lpGravity[0] = v[0].toDouble(); lpGravity[1] = v[1].toDouble(); lpGravity[2] = v[2].toDouble()
                        lpInit = true
                    } else {
                        val a = 0.02
                        for (i in 0..2) lpGravity[i] += a * (v[i] - lpGravity[i])
                    }
                    engine.onGravity(t, lpGravity[0], lpGravity[1], lpGravity[2])
                }
                engine.onAccel(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magMagnitude = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                try {
                    SensorManager.getRotationMatrixFromVector(rot, v)
                } catch (ex: IllegalArgumentException) {
                    SensorManager.getRotationMatrixFromVector(rot, v.copyOf(4))
                }
                for (i in 0 until 9) rotD[i] = rot[i].toDouble()
                engine.onRotationMatrix(t, rotD, magMagnitude, expectedFieldUt)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
