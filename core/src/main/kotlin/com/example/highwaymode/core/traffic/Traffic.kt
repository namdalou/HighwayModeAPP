package com.example.highwaymode.core.traffic

import com.example.highwaymode.core.geo.Geo

/** 交通情報の種類 */
enum class TrafficKind(val label: String) {
    /** 流れ（速度）だけの情報 */
    FLOW("流れ"),
    SLOW("混雑"),
    JAM("渋滞"),
    ACCIDENT("事故"),
    CLOSURE("通行止"),
    ROADWORK("工事"),
    LANE_CLOSED("車線規制"),
    HAZARD("障害物"),
    WEATHER("気象"),
    OTHER("規制");

    val isEvent: Boolean get() = this != FLOW && this != SLOW && this != JAM
}

/**
 * 1 件の交通情報。座標列は「その情報が当てはまる進行方向」の順。
 * @param ratio 自由流に対する速度比（1=順調, 0.2=渋滞）。不明なら null
 */
class TrafficItem(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val kind: TrafficKind,
    val ratio: Double?,
    val speedKmh: Double? = null,
    val delaySec: Double? = null,
    val description: String? = null,
    /** 0〜4（4=最も深刻） */
    val severity: Int = 0
) {
    val length: Double by lazy {
        var s = 0.0
        for (i in 0 until lat.size - 1) s += Geo.distance(lat[i], lon[i], lat[i + 1], lon[i + 1])
        s
    }
}

/**
 * 取得した交通情報一式。
 * @param coverageAll true なら「問い合わせた経路上で情報の無い区間は順調」とみなせる（事故・渋滞情報型の API）
 * @param coveredLat/coveredLon 問い合わせた経路（coverageAll のときの対象範囲）
 */
class TrafficData(
    val provider: String,
    val fetchedWallMs: Long,
    val items: List<TrafficItem>,
    val coverageAll: Boolean,
    val coveredLat: DoubleArray,
    val coveredLon: DoubleArray,
    /** 経路全体の遅れ（Google の duration − staticDuration 等）[s] */
    val totalDelaySec: Double? = null
)

/** 交通情報の問い合わせ範囲（エンジンが前方経路から作る） */
class TrafficQuery(
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** 各点での進行方位 [deg] */
    val bearing: DoubleArray,
    /** 各点の現在地からの道のり [m] */
    val dist: DoubleArray,
    val lengthM: Double
) {
    val isEmpty: Boolean get() = lat.size < 2
}

/** 交通情報の取得元 */
interface TrafficProvider {
    val name: String

    /** 通信して取得する（作業スレッドから呼ぶ） */
    fun fetch(q: TrafficQuery, cancel: com.example.highwaymode.core.net.CancelToken? = null): TrafficData
}
