package com.example.highwaymode.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.dual.DualMode
import com.example.highwaymode.engine.EngineHost

/**
 * 車の画面のメニュー（6 項目まで。走行中も表示できる件数に収める）。
 * 画面切替・表示の切替・再探索・一般道モード・自動切替・終了。
 */
class CarMenuScreen(carContext: CarContext) : Screen(carContext) {

    private val prefs = Prefs(carContext)

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val next = when (DualMode.layout) {
            DualLayout.SPLIT -> DualLayout.HIGHWAY
            DualLayout.HIGHWAY -> DualLayout.MUSIC
            DualLayout.MUSIC -> DualLayout.SPLIT
        }
        list.addItem(
            Row.Builder()
                .setTitle("画面を「${DualMode.label(next)}」にする")
                .addText("いま: ${DualMode.label(DualMode.layout)}")
                .setOnClickListener {
                    DualMode.select(next)
                    finish()
                }.build()
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
                .setOnClickListener {
                    prefs.displayMode = (prefs.displayMode + 1) % 3
                    finish()
                }.build()
        )
        list.addItem(
            Row.Builder()
                .setTitle("現在地の道路を再探索")
                .addText("上下線・並走する道路を取り違えたとき")
                .setOnClickListener {
                    EngineHost.post { it.forceRematch() }
                    CarToast.makeText(carContext, "現在地の道路を再探索します", CarToast.LENGTH_SHORT).show()
                    finish()
                }.build()
        )
        val general = EngineHost.latest?.status == EngineStatus.GENERAL_ROAD_MODE
        list.addItem(
            Row.Builder()
                .setTitle(if (general) "一般道モードを解除" else "一般道モード")
                .addText(if (general) "高速道路の判定を再開します" else "高速道路の判定を止めて、音楽の画面のままにします")
                .setOnClickListener {
                    EngineHost.post { it.generalRoadMode = !general }
                    finish()
                }.build()
        )
        val auto = prefs.autoSwitch
        list.addItem(
            Row.Builder()
                .setTitle(if (auto) "自動切替: オン" else "自動切替: オフ")
                .addText(
                    if (auto) "高速道路に入ると「${DualMode.label(DualMode.highwayDefault())}」、出ると「音楽」に切り替えます"
                    else "画面は手動で切り替えます（タップでオン）"
                )
                .setOnClickListener {
                    prefs.autoSwitch = !auto
                    DualMode.settingsChanged()
                    invalidate()
                }.build()
        )
        list.addItem(
            Row.Builder()
                .setTitle("終了")
                .addText("車の画面のアプリを閉じます")
                .setOnClickListener { carContext.finishCarApp() }
                .build()
        )
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle("メニュー")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
