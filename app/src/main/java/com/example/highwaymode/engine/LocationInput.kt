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
import com.example.highwaymode.core.model.GpsFix

/**
 * GNSS（GPS_PROVIDER）の生の測位を 1Hz 以上で受け取る。
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

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (started) return true
        if (!hasPermission()) return false
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, handler.looper)
            lm.registerGnssStatusCallback(gnssCallback, handler)
            started = true
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false // GPS プロバイダが無い端末
        }
    }

    fun stop() {
        if (!started) return
        lm.removeUpdates(this)
        lm.unregisterGnssStatusCallback(gnssCallback)
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
        /** android.location.Location → エンジンの測位（車両の GPS でも使う） */
        fun toFix(loc: Location): GpsFix {
            val speedAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else null
            val bearingAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else null
            val t = if (loc.elapsedRealtimeNanos > 0) loc.elapsedRealtimeNanos else android.os.SystemClock.elapsedRealtimeNanos()
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
