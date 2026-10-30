package com.example.highwaymode.core.dual

import com.example.highwaymode.core.engine.EngineStatus

/**
 * 「ハイウェイモードの対象エリア（高速道路・有料道路・都市高速・主要バイパス）にいるか」を、
 * エンジンの状態からちらつきなく判定する。画面の自動切替（Spectrum ⇔ 2 画面）に使う。
 *
 *  - 入る: 高速道路上（ON_HIGHWAY）が [enterSec] 秒続いたら。
 *          高架下・側道の一般道で一瞬だけ高速道路に当てはまった場合などは入らない。
 *  - 出る: 高速道路外（OFF_HIGHWAY）が通算 [exitSec] 秒続いたら。
 *          IC のランプ・料金所・JCT・SA/PA の出入りでは戻らない。
 *          高速道路外の途中で一瞬だけ高速道路に当てはまっても、[reconfirmSec] 秒続かなければ数え直さない。
 *  - SA/PA で停車中（REST_STOP）は高速道路上と同じ扱い。
 *  - 一般道モード（利用者の指定）は即座に対象外。
 *  - 測位待ち・地図の読み込み中は状態を保ち、[unknownExitSec] 秒続いたら対象外にする。
 */
class HighwayPresence(
    val enterSec: Double = 5.0,
    val exitSec: Double = 15.0,
    val reconfirmSec: Double = 3.0,
    val unknownExitSec: Double = 60.0
) {
    /** 対象エリアにいる */
    var active = false
        private set

    /** 高速道路上が続いている開始時刻 */
    private var onSince = Double.NaN
    /** 対象エリア内で、高速道路外が始まった時刻 */
    private var offSince = Double.NaN
    /** 対象エリア内で、状態不明（測位待ち・読み込み中）が始まった時刻 */
    private var unknownSince = Double.NaN

    /**
     * エンジンの状態を 1 回分反映する。
     * @param tSec 単調に増える時刻 [秒]
     * @return 対象エリアの内外が変わったら true
     */
    fun update(status: EngineStatus, tSec: Double): Boolean {
        val before = active
        when (status) {
            EngineStatus.GENERAL_ROAD_MODE -> reset()

            EngineStatus.ON_HIGHWAY, EngineStatus.REST_STOP -> {
                if (onSince.isNaN()) onSince = tSec
                val onFor = tSec - onSince
                if (!active) {
                    if (onFor >= enterSec) active = true
                } else if (onFor >= reconfirmSec) {
                    // 高速道路上に戻ったことが確かになったら、外にいた時間は数え直す
                    offSince = Double.NaN
                    unknownSince = Double.NaN
                }
            }

            EngineStatus.OFF_HIGHWAY -> {
                onSince = Double.NaN
                unknownSince = Double.NaN
                if (active) {
                    if (offSince.isNaN()) offSince = tSec
                    if (tSec - offSince >= exitSec) leave()
                }
            }

            EngineStatus.WAITING_GPS, EngineStatus.LOADING_MAP -> {
                onSince = Double.NaN
                if (active) {
                    if (unknownSince.isNaN()) unknownSince = tSec
                    when {
                        // 高速道路外が続いていた途中なら、その時間も数える
                        !offSince.isNaN() && tSec - offSince >= exitSec -> leave()
                        tSec - unknownSince >= unknownExitSec -> leave()
                    }
                }
            }
        }
        return active != before
    }

    /** 対象外へ（次に入るときは改めて [enterSec] 秒の確認から） */
    fun reset() {
        active = false
        onSince = Double.NaN
        offSince = Double.NaN
        unknownSince = Double.NaN
    }

    private fun leave() {
        reset()
    }
}
