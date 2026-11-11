package com.example.highwaymode.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.core.graphics.drawable.IconCompat
import com.example.highwaymode.R
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.dual.DualMode
import com.example.highwaymode.engine.EngineHost
import com.example.spectrum.SpectrumStyle

/**
 * 車の画面のメニュー（6 項目まで。走行中も表示できる件数に収める）。
 * 画面切替・ハイウェイの表示の切替・スペアナの表示（v2.14）・再探索・一般道モード・自動切替。
 * 「終了」は、v2.14 から右上の電源のボタン（6 項目に収めるため）。
 *
 * v2.15: 行の題名はどれも変えない（[CarTemplates] の決まり 2。v2.14 までは「自動切替: オン／オフ」のように
 * 題名を書き換えていて、3 回切り替えると画面の段の上限に達して落ちた）。オン・オフはスイッチ、いまの状態は行の説明で見せる。
 */
class CarMenuScreen(carContext: CarContext) : Screen(carContext) {

    private val prefs = Prefs(carContext)
    /** 一般道モードのスイッチを押した直後の状態（エンジンに反映されるまでの間、スイッチの表示に使う） */
    private var generalPending: Boolean? = null

    override fun onGetTemplate(): Template = CarTemplates.safe("メニュー") {
        val list = ItemList.Builder()
        val next = when (DualMode.layout) {
            DualLayout.SPLIT -> DualLayout.HIGHWAY
            DualLayout.HIGHWAY -> DualLayout.MUSIC
            DualLayout.MUSIC -> DualLayout.SPLIT
        }
        list.addItem(
            Row.Builder()
                .setTitle("画面を切り替える")
                .addText("いま: ${DualMode.label(DualMode.layout)} → 押すと「${DualMode.label(next)}」")
                .setOnClickListener(CarTemplates.click {
                    // 開いている間に画面が変わっていても、押したときの「次」へ
                    val n = when (DualMode.layout) {
                        DualLayout.SPLIT -> DualLayout.HIGHWAY
                        DualLayout.HIGHWAY -> DualLayout.MUSIC
                        DualLayout.MUSIC -> DualLayout.SPLIT
                    }
                    DualMode.select(n)
                    finish()
                }).build()
        )
        val modeLabel = when (prefs.displayMode) {
            1 -> "一覧"
            2 -> "SA/PA のみ"
            else -> "大きく"
        }
        list.addItem(
            Row.Builder()
                .setTitle("ハイウェイの表示を切替")
                .addText("いま: $modeLabel（大きく → 一覧 → SA/PA のみ）")
                .setOnClickListener(CarTemplates.click {
                    prefs.displayMode = (prefs.displayMode + 1) % 3
                    finish()
                }).build()
        )
        // スペアナの表示の種類（v2.14。見本の絵つきの一覧を開く）
        list.addItem(
            Row.Builder()
                .setTitle("スペアナの表示")
                .addText("いま: ${SpectrumStyle.VISUAL_LABELS[SpectrumStyle.visual]}")
                .setBrowsable(true)
                .setOnClickListener(CarTemplates.click { screenManager.push(CarSpectrumScreen(carContext)) })
                .build()
        )
        list.addItem(
            Row.Builder()
                .setTitle("現在地の道路を再探索")
                .addText("上下線・並走する道路を取り違えたとき")
                .setOnClickListener(CarTemplates.click {
                    EngineHost.post { it.forceRematch() }
                    CarToast.makeText(carContext, "現在地の道路を再探索します", CarToast.LENGTH_SHORT).show()
                    finish()
                }).build()
        )
        val general = generalPending ?: (EngineHost.latest?.status == EngineStatus.GENERAL_ROAD_MODE)
        list.addItem(
            Row.Builder()
                .setTitle("一般道モード")
                .addText("オンの間は高速道路の判定を止めて、音楽の画面のままにします")
                .setToggle(
                    Toggle.Builder { on ->
                        try {
                            generalPending = on
                            EngineHost.post { it.generalRoadMode = on }
                        } catch (e: Exception) {
                            // エンジンが止まっている
                        }
                    }.setChecked(general).build()
                )
                .build()
        )
        list.addItem(
            Row.Builder()
                .setTitle("自動切替")
                .addText("高速道路に入ると「${DualMode.label(DualMode.highwayDefault())}」、出ると「音楽」に切り替えます")
                .setToggle(
                    Toggle.Builder { on ->
                        try {
                            prefs.autoSwitch = on
                            DualMode.settingsChanged()
                        } catch (e: Exception) {
                            // 設定を保存できなかった
                        }
                    }.setChecked(prefs.autoSwitch).build()
                )
                .build()
        )
        @Suppress("DEPRECATION")
        ListTemplate.Builder()
            .setTitle("メニュー")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            // スマホでアプリを開かずに使えているかの確認（GPS・通信・常駐・曲情報）と、終了（電源のボタン。
            // この画面の右上のボタンは 2 つまで・文字を出せるのは 1 つまでなので、終了はアイコンだけ）
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setTitle("動作状態")
                            .setOnClickListener(CarTemplates.click { screenManager.push(RunStatusScreen(carContext)) })
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_exit)).build())
                            .setOnClickListener(CarTemplates.click { carContext.finishCarApp() })
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
