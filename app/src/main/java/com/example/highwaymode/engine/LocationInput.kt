package com.example.highwaymode.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import com.example.highwaymode.core.model.GpsFix
import kotlin.math.abs

/**
 * GNSS（GPS_PROVIDER）の生の測位を受け取る。通常は 1Hz 以上、電池の節約中は [setInterval] の間隔で。
 * ドップラー速度・方位とその精度（Android 8.0+）をそのままエンジンへ渡す。
 * コールバックはエンジン用スレッド（handler）で呼ばれる。
 */
class LocationInput(
    private val context: Context,
    private val handler: Handler,
    private val onFix: (GpsFix) -> Unit
) : LocationListener {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var started = false
    private var gnssRegistered = false

    /** 測位の間隔 [ms]（0 = できるだけ頻繁に。10 秒以上にすると GPS は合間に休む） */
    var intervalMs = 0L
        private set

    @Volatile
    var satellitesUsed = 0
        private set

    @Volatile
    var satellitesVisible = 0
        private set

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellitesUsed = used
            satellitesVisible = status.satelliteCount
        }
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (started) return true
        if (!hasPermission()) return false
        return request()
    }

    /** 測位の間隔を変える（受信中なら登録し直す） */
    fun setInterval(ms: Long) {
        if (ms == intervalMs) return
        intervalMs = ms
        if (started) {
            try {
                lm.removeUpdates(this)
            } catch (e: Exception) {
                // 登録し直す
            }
            started = false
            request()
        }
    }

    @SuppressLint("MissingPermission")
    private fun request(): Boolean = try {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, this, handler.looper)
        started = true
        // 衛星の状態は毎秒届く（その都度 CPU が起きる）ので、毎秒測位しているときだけ受け取る
        if (intervalMs == 0L) {
            if (!gnssRegistered) gnssRegistered = lm.registerGnssStatusCallback(gnssCallback, handler)
        } else if (gnssRegistered) {
            lm.unregisterGnssStatusCallback(gnssCallback)
            gnssRegistered = false
        }
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false // GPS プロバイダが無い端末
    }

    fun stop() {
        if (!started) return
        lm.removeUpdates(this)
        if (gnssRegistered) {
            lm.unregisterGnssStatusCallback(gnssCallback)
            gnssRegistered = false
        }
        started = false
    }

    val gpsEnabled: Boolean
        get() = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }

    override fun onLocationChanged(loc: Location) {
        onFix(toFix(loc))
    }

    companion object {
        /**
         * android.location.Location → エンジンの測位（車両の GPS でも使う）。
         * 測位時刻が端末の時計（elapsedRealtimeNanos）とかけ離れている場合（車両の GPS で時計の基準が違うなど）は、
         * 受け取った時刻を使う（時刻が前後すると、上下線・ランプの判定が乱れるため）。
         */
        fun toFix(loc: Location): GpsFix {
            val speedAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else null
            val bearingAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else null
            val now = SystemClock.elapsedRealtimeNanos()
            val lt = loc.elapsedRealtimeNanos
            val t = if (lt > 0 && abs(now - lt) < 5_000_000_000L) lt else now
            return GpsFix(
                timeNanos = t,
                wallTimeMs = System.currentTimeMillis(),
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 30.0,
                speedMs = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                speedAccMs = speedAcc,
                bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                bearingAccDeg = bearingAcc
            )
        }
    }

    // Android 10 以前ではこれらが抽象メソッドなので必ず実装する
    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
