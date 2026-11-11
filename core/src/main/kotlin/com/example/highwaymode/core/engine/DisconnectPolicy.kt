package com.example.highwaymode.core.engine

/**
 * Android Auto との接続が切れたあと、スマホ側の常駐（位置情報の取得・通知）を続けるかどうかの判定（v2.15）。
 *
 * 利用者の報告: スマホを操作せずに Android Auto だけで使っていると、切断したあとも常駐がずっと続いていた。
 *
 * 切断してすぐには止めない（ケーブルの接触・無線のつなぎ直し・エンジンのかけ直しで、すぐにつながり直すことがある）。
 * 判定時間のあいだ様子を見て、
 *  - 車の画面がつながり直した → 続ける
 *  - スマホの画面でこのアプリを開いた → 続ける（スマホで使っている）
 *  - 高速道路を走っている最中 → 待つ（走行中に接続が切れただけ。音声案内を続け、降りる・止まるまで待つ）
 *  - それ以外 → 判定時間が過ぎたら止める
 */
object DisconnectPolicy {

    enum class Action {
        /** 続ける（止める予定を取り消す） */
        KEEP,
        /** まだ止めない（あとでもう一度確かめる） */
        WAIT,
        /** 常駐を止める */
        STOP
    }

    /** 走っているとみなす速さ [km/h] */
    const val MOVING_KMH = 20.0

    /** 位置情報がこれより古ければ、走っているとはみなさない [秒] */
    const val FRESH_GPS_SEC = 30.0

    /**
     * @param carConnected 車の画面（Android Auto）がつながっている
     * @param phoneVisible スマホの画面でこのアプリを開いている
     * @param sinceDisconnectMs 接続が切れてからの時間
     * @param graceMs 判定時間（負なら「止めない」設定）
     * @param status エンジンの状態（動いていなければ null）
     * @param speedKmh いまの速さ（分からなければ null）
     * @param gpsAgeSec 最後の位置情報からの時間（分からなければ null）
     */
    fun decide(
        carConnected: Boolean,
        phoneVisible: Boolean,
        sinceDisconnectMs: Long,
        graceMs: Long,
        status: EngineStatus?,
        speedKmh: Double?,
        gpsAgeSec: Double?
    ): Action {
        if (carConnected || phoneVisible || graceMs < 0L) return Action.KEEP
        if (sinceDisconnectMs < graceMs) return Action.WAIT
        val driving = status == EngineStatus.ON_HIGHWAY &&
            speedKmh != null && speedKmh >= MOVING_KMH &&
            gpsAgeSec != null && gpsAgeSec <= FRESH_GPS_SEC
        return if (driving) Action.WAIT else Action.STOP
    }
}
