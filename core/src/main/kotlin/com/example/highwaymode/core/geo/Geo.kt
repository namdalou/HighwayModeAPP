package com.example.highwaymode.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/**
 * 地理計算ユーティリティ。
 * 方位は「真北基準・時計回り・度」で統一する（GPS の bearing と同じ定義）。
 */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val DEG = PI / 180.0
    const val RAD = 180.0 / PI

    /** 緯度 1 度あたりのメートル（WGS84 楕円体近似） */
    fun metersPerDegLat(latDeg: Double): Double {
        val p = latDeg * DEG
        return 111132.92 - 559.82 * cos(2 * p) + 1.175 * cos(4 * p) - 0.0023 * cos(6 * p)
    }

    /** 経度 1 度あたりのメートル（WGS84 楕円体近似） */
    fun metersPerDegLon(latDeg: Double): Double {
        val p = latDeg * DEG
        return 111412.84 * cos(p) - 93.5 * cos(3 * p) + 0.118 * cos(5 * p)
    }

    /** 大円距離 [m] */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * 局所平面近似の距離 [m]。数十 km 以内なら誤差 0.1% 未満で、haversine より高速。
     * 道路ポリラインの区間長計算に使う。
     */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val midLat = (lat1 + lat2) * 0.5
        val dy = (lat2 - lat1) * metersPerDegLat(midLat)
        val dx = (lon2 - lon1) * metersPerDegLon(midLat)
        return sqrt(dx * dx + dy * dy)
    }

    /** 2 点間の方位 [deg]（短距離用の局所平面近似） */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val midLat = (lat1 + lat2) * 0.5
        val dy = (lat2 - lat1) * metersPerDegLat(midLat)
        val dx = (lon2 - lon1) * metersPerDegLon(midLat)
        return normalizeDeg(atan2(dx, dy) * RAD)
    }

    /** 指定方位・距離の地点（局所平面近似、数十 km まで） */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): LatLon {
        val b = bearingDeg * DEG
        val dy = distM * cos(b)
        val dx = distM * sin(b)
        val nLat = lat + dy / metersPerDegLat(lat)
        val nLon = lon + dx / metersPerDegLon((lat + nLat) * 0.5)
        return LatLon(nLat, nLon)
    }

    /** [0, 360) に正規化 */
    fun normalizeDeg(a: Double): Double {
        var r = a % 360.0
        if (r < 0) r += 360.0
        if (r >= 360.0) r -= 360.0
        return r
    }

    /** a - b を (-180, 180] に正規化した角度差 */
    fun diffDeg(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** ラジアン角を (-π, π] に正規化 */
    fun wrapPi(a: Double): Double {
        var d = a % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d <= -PI) d += 2 * PI
        return d
    }

    /** ラジアン角を [0, 2π) に正規化 */
    fun wrap2Pi(a: Double): Double {
        var d = a % (2 * PI)
        if (d < 0) d += 2 * PI
        return d
    }

    /** 8 方位の日本語表記 */
    fun compassJa(bearingDeg: Double): String {
        val names = arrayOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")
        val idx = ((normalizeDeg(bearingDeg) + 22.5) / 45.0).toInt() % 8
        return names[idx]
    }

    fun absDiffDeg(a: Double, b: Double): Double = abs(diffDeg(a, b))
}

/**
 * 基準点まわりの局所直交座標（x=東, y=北, 単位 m）。
 * 半径数十 km 以内での射影・内積計算に使う。
 */
class LocalFrame(val lat0: Double, val lon0: Double) {
    val mLat = Geo.metersPerDegLat(lat0)
    val mLon = Geo.metersPerDegLon(lat0)
    fun x(lon: Double) = (lon - lon0) * mLon
    fun y(lat: Double) = (lat - lat0) * mLat
    fun lat(y: Double) = lat0 + y / mLat
    fun lon(x: Double) = lon0 + x / mLon
}

/** 線分への射影結果 */
class SegmentProjection {
    /** 線分上の位置 [0,1] */
    var t = 0.0
    /** 点から線分までの距離 [m] */
    var dist = 0.0
    /** 進行方向に対する横方向オフセット [m]。正 = 左側 */
    var lateral = 0.0
}

object SegmentMath {
    /**
     * 点 P を線分 A→B に射影する（局所平面座標）。
     * lateral の符号は外積 (B-A)×(P-A) の z 成分で、正なら P は A→B の左側。
     */
    fun project(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
        out: SegmentProjection
    ) {
        val vx = bx - ax
        val vy = by - ay
        val wx = px - ax
        val wy = py - ay
        val len2 = vx * vx + vy * vy
        var t = if (len2 > 1e-9) (wx * vx + wy * vy) / len2 else 0.0
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val qx = ax + t * vx
        val qy = ay + t * vy
        val dx = px - qx
        val dy = py - qy
        out.t = t
        out.dist = sqrt(dx * dx + dy * dy)
        val len = sqrt(len2)
        out.lateral = if (len > 1e-9) (vx * wy - vy * wx) / len else 0.0
    }
}
