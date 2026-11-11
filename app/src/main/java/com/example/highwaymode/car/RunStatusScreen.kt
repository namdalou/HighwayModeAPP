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
 * 動作状態（スマホでアプリを開かずに使えているか）。開いたときに問題のある項目を先に並べる。
 * 設定の変更はスマホで行う（運転中は操作しないでください）。
 *
 * v2.15: 開いたときの項目の並び（行の数・題名）は、この画面が出ている間は変えない（[CarTemplates] の決まり 2）。
 * 「更新」では、各行の説明（○・× と内容）だけをいまの状態にする。v2.14 までは、題名に ○・× を入れて並べ替えていたので、
 * 状態が変わったあとの「更新」で画面の段が増え、何度か押すと上限で落ちることがあった。
 */
class RunStatusScreen(carContext: CarContext) : Screen(carContext) {

    /** 開いたときの項目の題名（この順のまま） */
    private var titles: List<String>? = null

    override fun onGetTemplate(): Template = CarTemplates.safe("動作状態") {
        // 表示のたびに、止まっていれば常駐サービスの起動を試みる
        CarRuntime.ensure(carContext)
        val items = RunDiagnostics.collect(carContext)
        val order = titles ?: items.sortedBy { it.ok }.map { CarTemplates.title(it.title) }.distinct().take(MAX_ROWS).also { titles = it }
        val list = ItemList.Builder()
        for (t in order) {
            val i = items.firstOrNull { CarTemplates.title(it.title) == t }
            val row = Row.Builder().setTitle(t)
            if (i == null) {
                // 開いたあとに無くなった項目（省電力モードがオフになった など）
                row.addText("○ いまは該当しません")
            } else {
                row.addText((if (i.ok) "○ " else "× ") + i.value.take(120))
                if (!i.ok) {
                    val msg = if (i.fix != null) "停車中にスマホで「設定 → スマホを開かずに Android Auto で使う」を開いてください" else i.value
                    row.setOnClickListener(CarTemplates.click {
                        CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
                    })
                }
            }
            list.addItem(row.build())
        }
        @Suppress("DEPRECATION")
        ListTemplate.Builder()
            .setTitle("動作状態")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .setActionStrip(
                ActionStrip.Builder()
                    // 行の数・題名は変えないので、何度押しても「更新」扱い（段は増えない）
                    .addAction(Action.Builder().setTitle("更新").setOnClickListener(CarTemplates.click { invalidate() }).build())
                    .build()
            )
            .build()
    }

    companion object {
        private const val MAX_ROWS = 6
    }
}
