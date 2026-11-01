package com.example.highwaymode.core.engine

import com.example.highwaymode.core.facility.Amenity
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.facility.PoiInfo
import com.example.highwaymode.core.traffic.TrafficKind
import com.example.highwaymode.core.vehicle.TollCardState

enum class EngineStatus {
    /** GPS 測位待ち */
    WAITING_GPS,
    /** 周辺の高速道路データを取得中 */
    LOADING_MAP,
    /** 高速道路の外（一般道・SA/PA の駐車場など） */
    OFF_HIGHWAY,
    /** 高速道路走行中 */
    ON_HIGHWAY,
    /** SA/PA で停車中（直前の前方施設と、出発した場合の到着予定を表示） */
    REST_STOP,
    /** 利用者が「一般道走行中」を指定 */
    GENERAL_ROAD_MODE
}

/** 画面に表示する前方施設 1 件 */
class FacilityView(
    val id: String,
    val type: FacilityType,
    val name: String,
    val displayName: String,
    /** 道のり [m] */
    val distanceM: Double,
    /** 表示用到着予定時刻（分単位丸め済み）[epoch ms] */
    val etaEpochMs: Long,
    /** 到着までの予測秒数 */
    val travelSec: Double,
    val amenities: List<Amenity>,
    val etcOnly: Boolean,
    val etcReason: String?,
    val pois: List<PoiInfo>,
    val lat: Double,
    val lon: Double,
    val exitRef: String?,
    val destination: String?,
    val directionNote: String?,
    val typeInferred: Boolean,
    /** 手前の区間の流れ（自由流所要時間/予測所要時間, 1=順調） */
    val flowBefore: Double,
    /** 手前の区間（1 つ前の施設から）にある渋滞の長さ [m]（交通情報による） */
    val jamBeforeM: Double = 0.0,
    /** 手前の区間の事故・通行止め・工事など（「事故」「通行止」…） */
    val trafficNote: String? = null,
    /** 手前に通行止めがある（到着予定は参考値） */
    val closedBefore: Boolean = false,
    /** オービス: 取締速度 */
    val maxspeedKmh: Int? = null,
    val cameraType: String? = null,
    val userDefined: Boolean = false,
    /** この進行方向には出口が無い IC（ハーフ IC）。「出口なし」と表示する */
    val noExit: Boolean = false,
    /** 手前の渋滞の先頭（解消地点）までの道のり [m]（渋滞の中にいるときなど、無ければ null） */
    val jamEndM: Double? = null
)

/** 交通情報の状態 */
class TrafficView(
    /** 取得元（HERE / TomTom / Google） */
    val provider: String?,
    /** 取得時刻 [epoch ms] */
    val fetchedWallMs: Long?,
    /** 状態メッセージ（未設定・失敗など） */
    val message: String?,
    /** 次の渋滞までの距離 [m] と長さ [m]、見込みの遅れ [s] */
    val nextJamDistanceM: Double?,
    val nextJamLengthM: Double?,
    val nextJamDelaySec: Double?,
    /** 前方の出来事（距離 [m], 種類, 説明） */
    val events: List<Triple<Double, TrafficKind, String?>>,
    /** 交通情報を考慮した場合の、考慮しない場合からの遅れ（経路全体）[s] */
    val routeDelaySec: Double?,
    /** 次の渋滞が 伸びている (+1) / 縮んでいる (-1) / 変わらない・不明 (0) */
    val nextJamTrend: Int = 0
)

/** 車両の状態（Android Auto 接続時） */
class VehicleView(
    val connected: Boolean,
    val model: String?,
    val speedSignal: Boolean,
    val tollCard: TollCardState,
    val rangeRemainingM: Double?,
    val fuelPercent: Double?,
    val energyLow: Boolean?,
    /** 給油の目安（例: 「次の◯◯SAで給油を」） */
    val fuelAdvice: String?,
    /** 0=情報, 1=注意, 2=警告 */
    val fuelAdviceLevel: Int,
    val vehicleGyro: Boolean
)

class HighwaySnapshot(
    val status: EngineStatus,
    val wallTimeMs: Long,
    val roadName: String?,
    val roadRef: String?,
    val onLink: Boolean,
    /** 例: 「南行き・岡谷JCT方面」 */
    val directionText: String?,
    /** 例: 「12km先 岡谷JCTから 中央自動車道」 */
    val nextRoadText: String?,
    val speedKmh: Double?,
    val limitKmh: Int?,
    val limitTagged: Boolean,
    val headingDeg: Double?,
    val headingSigmaDeg: Double?,
    val headingSource: String,
    val deadReckoning: Boolean,
    val gpsAccuracyM: Double?,
    val gpsAgeSec: Double?,
    val matchConfidence: Double,
    val facilities: List<FacilityView>,
    val compassCalibrated: Boolean,
    val compassOffsetDeg: Double?,
    val forwardAxisValid: Boolean,
    val gyroBiasDegPerSec: Double,
    /** 現在の制限速度帯で学習した速度比 */
    val driverRatio: Double?,
    /** 直近の流れ（1=順調） */
    val trafficRatio: Double?,
    val lat: Double?,
    val lon: Double?,
    val dataMessage: String?,
    val routeLengthM: Double,
    val routeTruncated: Boolean,
    /** 前方のオービス（近い順, 30km 以内） */
    val cameras: List<FacilityView> = emptyList(),
    val traffic: TrafficView? = null,
    val vehicle: VehicleView? = null,
    /** 車速の取得元（「車速信号」「GPS」「推定」） */
    val speedSource: String = "GPS",
    /** 一般道のバイパスを走行中（信号・歩行者がありうる） */
    val generalRoad: Boolean = false
) {
    companion object {
        fun initial(wallMs: Long) = HighwaySnapshot(
            EngineStatus.WAITING_GPS, wallMs, null, null, false, null, null, null, null, false,
            null, null, "-", false, null, null, 0.0, emptyList(), false, null, false, 0.0,
            null, null, null, null, null, 0.0, false
        )
    }
}
