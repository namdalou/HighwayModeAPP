package com.example.highwaymode.data

import android.content.Context
import com.example.highwaymode.ui.Formats
import com.example.spectrum.Guidance
import com.example.spectrum.SpectrumStyle

/**
 * 描画のたびに使う設定（到着予定の表示・スペアナの色と高さ・音楽の画面のナビの案内）を、描画用の変数に写す。
 * スマホの画面・車の画面・常駐サービスの起動時と、設定画面で変えたときに呼ぶ（同じプロセスなので、どちらの画面にもすぐ反映される）。
 *
 * 表示中の車の画面は [version] が変わったのを見て、ハイウェイの表示の種類などを読み直す。
 */
object AppSettings {

    /** 設定画面で表示に関わる設定を変えるたびに増える */
    @Volatile
    var version = 0
        private set

    fun apply(ctx: Context) {
        val p = Prefs(ctx)
        Formats.etaRemaining = p.etaStyle == 1
        SpectrumStyle.load(ctx)
        // 音楽の画面に出すナビの案内（設定でオフなら出さない）
        Guidance.enabled = p.navBanner
    }

    /** 設定画面で設定を変えた */
    fun changed(ctx: Context) {
        apply(ctx)
        version++
    }
}
