package com.example.highwaymode.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.highwaymode.engine.RunDiagnostics

/**
 * 動作状態（スマホでアプリを開かずに使えているか）。問題のある項目を先に並べる。
 * 設定の変更はスマホで行う（運転中は操作しないでください）。
 */
class RunStatusScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        // 表示のたびに、止まっていれば常駐サービスの起動を試みる
        CarRuntime.ensure(carContext)
        val items = RunDiagnostics.collect(carContext).sortedBy { it.ok }
        val list = ItemList.Builder()
        for (i in items.take(MAX_ROWS)) {
            val row = Row.Builder()
                .setTitle((if (i.ok) "○ " else "× ") + i.title)
                .addText(i.value.take(120))
            if (!i.ok) {
                row.setOnClickListener {
                    CarToast.makeText(
                        carContext,
                        if (i.fix != null) "停車中にスマホで「メニュー →Android Auto だけで使うための設定」を開いてください" else i.value,
                        CarToast.LENGTH_LONG
                    ).show()
                }
            }
            list.addItem(row.build())
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle(if (items.all { it.ok }) "動作状態: 正常" else "動作状態")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("更新").setOnClickListener { invalidate() }.build())
                    .build()
            )
            .build()
    }

    companion object {
        private const val MAX_ROWS = 6
    }
}
