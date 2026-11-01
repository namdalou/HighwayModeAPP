package com.example.highwaymode.core.engine

/**
 * 電池の節約の段階。GPS の測位間隔・センサーを使うか・処理の周期。
 * 10 秒以上の間隔にすると、GPS は測位の合間に休む（端末によっては受信機そのものを止める）。
 */
enum class PowerLevel(val gpsIntervalMs: Long, val sensors: Boolean, val tickMs: Long, val label: String) {
    /** 毎秒測位・センサー使用（高速道路上・高速道路の近くを走行中） */
    ACTIVE(0L, true, 250L, "通常（毎秒測位）"),
    /** 高速道路の近く・SA/PA で停車中: 測位は 15 秒ごと。動き出しはセンサーで検知してすぐ通常に戻る */
    STILL(15_000L, true, 500L, "停車中（15 秒ごとに測位）"),
    /** 高速道路の近くで長く停車中（10 分以上） */
    PARKED(60_000L, true, 1000L, "駐車中（1 分ごとに測位）"),
    /** 高速道路から離れた一般道を走行中: 測位は 10 秒ごと、センサーは止める */
    FAR(10_000L, false, 1000L, "高速道路から離れています（10 秒ごとに測位）"),
    /** 高速道路から離れた場所で停車中 */
    FAR_PARKED(60_000L, false, 2000L, "高速道路から離れて停車中（1 分ごとに測位）")
}

/**
 * 電池の節約: 高速道路から離れている・停車しているときは、GPS の間隔をあけてセンサーを止める。
 *
 *  - 高速道路上（渋滞で止まっていても）・地図データの読み込み中・測位待ち・デモ走行中は、これまでどおり毎秒測位
 *  - 高速道路（ハイウェイモードの対象の道路）から [nearM] 以内は、走行中なら毎秒測位。
 *    停車が [stillAfterSec] 秒続いたら 15 秒ごと、[parkedAfterSec] 秒続いたら 1 分ごと
 *  - それより離れた一般道は 10 秒ごと（時速 100km でも、3km 進む間に 10 回以上測位できる）。
 *    停車が [farStillAfterSec] 秒続いたら 1 分ごと
 *  - SA/PA で停車中は、高速道路の近くで停車中と同じ
 *  - 動き出しを検知したら（端末の「動き出し」の検知・センサーの揺れ）、[motionHoldSec] 秒だけ測位を増やして確かめる。
 *    走り出していれば GPS の速度で通常に戻り、スマホを持って歩いただけなら元の間隔に戻る
 */
class PowerPolicy(
    val nearM: Double = 3000.0,
    val stillAfterSec: Double = 60.0,
    val parkedAfterSec: Double = 600.0,
    val farStillAfterSec: Double = 120.0,
    val motionHoldSec: Double = 15.0
) {
    class Input(
        /** 単調に増える時刻 [秒] */
        val tSec: Double,
        val status: EngineStatus,
        val demo: Boolean,
        /** いちばん近い高速道路までの距離 [m]。周辺の地図データがまだ無い（不明）なら null、無ければ +∞ */
        val nearestHighwayM: Double?,
        /** GPS の誤差 [m] */
        val accuracyM: Double,
        /** 停車中（車速信号・センサー・GPS の速度から） */
        val stationary: Boolean,
        /** 最後に大きな動きを検知した時刻 [秒]（無ければ NaN） */
        val motionAtSec: Double = Double.NaN
    )

    var level = PowerLevel.ACTIVE
        private set

    private var stillSince = Double.NaN

    /** @return 段階が変わったら true */
    fun update(i: Input): Boolean {
        val before = level
        level = decide(i)
        return level != before
    }

    fun reset() {
        level = PowerLevel.ACTIVE
        stillSince = Double.NaN
    }

    private fun decide(i: Input): PowerLevel {
        // 停車が続いている時間（車速・GPS の速度から。歩く程度の速さは停車とみなす）
        if (i.stationary) {
            if (stillSince.isNaN()) stillSince = i.tSec
        } else {
            stillSince = Double.NaN
        }
        val stillFor = if (stillSince.isNaN()) 0.0 else i.tSec - stillSince
        // 動き出しを検知した直後は、測位を増やして本当に走り出したか確かめる
        // （走り出していれば GPS の速度で停車の時間が数え直しになる。歩いていただけなら元に戻る）
        val checking = !i.motionAtSec.isNaN() && i.tSec - i.motionAtSec in 0.0..motionHoldSec
        if (i.demo) return PowerLevel.ACTIVE
        when (i.status) {
            EngineStatus.WAITING_GPS, EngineStatus.LOADING_MAP, EngineStatus.ON_HIGHWAY -> return PowerLevel.ACTIVE
            EngineStatus.REST_STOP -> return nearLevel(stillFor, checking)
            EngineStatus.OFF_HIGHWAY, EngineStatus.GENERAL_ROAD_MODE -> {}
        }
        val d = i.nearestHighwayM ?: return nearLevel(stillFor, checking)
        val near = d - i.accuracyM.coerceIn(0.0, 500.0) <= nearM && i.status != EngineStatus.GENERAL_ROAD_MODE
        if (near) return nearLevel(stillFor, checking)
        return if (stillFor >= farStillAfterSec && !checking) PowerLevel.FAR_PARKED else PowerLevel.FAR
    }

    private fun nearLevel(stillFor: Double, checking: Boolean): PowerLevel = when {
        checking -> PowerLevel.ACTIVE
        stillFor >= parkedAfterSec -> PowerLevel.PARKED
        stillFor >= stillAfterSec -> PowerLevel.STILL
        else -> PowerLevel.ACTIVE
    }
}
