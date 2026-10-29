package com.example.highwaymode.core.model

/**
 * GPS 測位 1 回分。時刻は端末起動からの経過時間 [ns]（Location.getElapsedRealtimeNanos と同じ基準）。
 */
class GpsFix(
    val timeNanos: Long,
    val wallTimeMs: Long,
    val lat: Double,
    val lon: Double,
    /** 水平精度 [m]（68%） */
    val accuracyM: Double,
    /** ドップラー速度 [m/s]（無ければ null） */
    val speedMs: Double?,
    val speedAccMs: Double?,
    /** 進行方位 [deg]（無ければ null） */
    val bearingDeg: Double?,
    val bearingAccDeg: Double?
)

/** 融合済みの方位観測（マップマッチング用） */
class HeadingObs(val headingDeg: Double, val sigmaDeg: Double)
