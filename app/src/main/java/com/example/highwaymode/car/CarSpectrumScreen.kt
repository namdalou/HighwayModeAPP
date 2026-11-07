package com.example.highwaymode.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.core.graphics.drawable.IconCompat
import com.example.highwaymode.data.AppSettings
import com.example.highwaymode.ui.SpectrumPreviewView
import com.example.spectrum.SpectrumEngine
import com.example.spectrum.SpectrumPainter
import com.example.spectrum.SpectrumStyle

/**
 * 車の画面で、スペアナの表示の種類を選ぶ（v2.14。メニューの「スペアナの表示」から開く）。
 *
 * 走行中に出せる一覧の数（6 件）に収まるよう、種類をまとめて 2 段にする:
 *  - 1 段目: LED バー（標準）・カーオーディオ（3 種類）・アナログメーター（2 種類）・バー・曲線・ドット（4 種類）
 *  - 2 段目: まとめの中の種類（アナログメーターは、照明の色（色の一覧へ）・メーターを 1 つにする（スイッチ）も）
 * 選ぶとすぐに反映して、車の画面（音楽）に戻る。行には見本の小さな絵（止まった 1 枚）を付ける。
 * 文字を読ませすぎないよう、行の説明は「いま選んでいる」ことと、まとめの中身だけにする。
 */
class CarSpectrumScreen(carContext: CarContext, private val group: Int = GROUP_TOP) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val title = when (group) {
            GROUP_CAR_AUDIO -> {
                for (v in CAR_AUDIO) list.addItem(visualRow(v))
                "カーオーディオ"
            }
            GROUP_METER -> {
                for (v in METERS) list.addItem(visualRow(v))
                // 照明の色（色の一覧へ）と、メーターの数（スイッチ）。
                // 行の題名を書き換えると Android Auto では「新しい画面」と数えられる（続けて操作できる画面の数に限りがある）ので、
                // 題名は変えず、スイッチの状態だけを変える
                list.addItem(
                    Row.Builder()
                        .setTitle("照明の色を選ぶ")
                        .addText("いま: ${SpectrumStyle.METER_LIGHT_LABELS[SpectrumStyle.meterLight]}")
                        .setBrowsable(true)
                        .setOnClickListener { screenManager.push(CarSpectrumScreen(carContext, GROUP_LIGHT)) }
                        .build()
                )
                list.addItem(
                    Row.Builder()
                        .setTitle("メーターを 1 つにする")
                        .addText("オフ: 2 つ（低音・高音）／ オン: 1 つ（全体を大きく）")
                        .setToggle(
                            Toggle.Builder { on ->
                                SpectrumStyle.setMeterSingle(carContext, on)
                                AppSettings.changed(carContext)
                            }.setChecked(SpectrumStyle.meterSingle).build()
                        )
                        .build()
                )
                "アナログメーター"
            }
            GROUP_LIGHT -> {
                // 照明の色（6 色。選ぶと車の画面に戻る）
                for ((i, label) in SpectrumStyle.METER_LIGHT_LABELS.withIndex()) {
                    val b = Row.Builder()
                        .setTitle(label)
                        .setOnClickListener {
                            SpectrumStyle.setMeterLight(carContext, i)
                            AppSettings.changed(carContext)
                            CarToast.makeText(carContext, "メーターの照明: $label", CarToast.LENGTH_SHORT).show()
                            screenManager.popToRoot()
                        }
                    if (i == SpectrumStyle.meterLight) b.addText("✓ いまの色")
                    else if (i == SpectrumStyle.LIGHT_STANDARD) b.addText("明るい文字盤は電球色・暗い文字盤は青")
                    list.addItem(b.build())
                }
                "メーターの照明の色"
            }
            GROUP_OTHER -> {
                for (v in OTHERS) list.addItem(visualRow(v))
                "バー・曲線・ドット"
            }
            else -> {
                list.addItem(visualRow(SpectrumStyle.VISUAL_LED))
                list.addItem(groupRow("カーオーディオ（3 種類）", GROUP_CAR_AUDIO, CAR_AUDIO))
                list.addItem(groupRow("アナログメーター（2 種類）", GROUP_METER, METERS))
                list.addItem(groupRow("バー・曲線・ドット（4 種類）", GROUP_OTHER, OTHERS))
                "スペアナの表示"
            }
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    /** 1 つの表示の種類（押すと選んで、車の画面に戻る） */
    private fun visualRow(v: Int): Row {
        val b = Row.Builder()
            .setTitle(SpectrumStyle.VISUAL_LABELS[v])
            .setImage(icon(v), Row.IMAGE_TYPE_LARGE)
            .setOnClickListener {
                SpectrumStyle.setVisual(carContext, v)
                AppSettings.changed(carContext)
                CarToast.makeText(carContext, "スペアナ: ${SpectrumStyle.VISUAL_LABELS[v]}", CarToast.LENGTH_SHORT).show()
                screenManager.popToRoot()
            }
        if (SpectrumStyle.visual == v) b.addText("✓ いまの表示")
        return b.build()
    }

    /** まとめ（押すと中の種類の一覧へ） */
    private fun groupRow(title: String, g: Int, members: IntArray): Row {
        val cur = SpectrumStyle.visual
        val inGroup = members.contains(cur)
        return Row.Builder()
            .setTitle(title)
            .addText(
                if (inGroup) "✓ いま: ${SpectrumStyle.VISUAL_SHORT[cur]}"
                else members.joinToString("・") { SpectrumStyle.VISUAL_SHORT[it] }
            )
            .setImage(icon(if (inGroup) cur else members[0]), Row.IMAGE_TYPE_LARGE)
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(CarSpectrumScreen(carContext, g)) }
            .build()
    }

    private fun icon(v: Int): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(sample(v))).build()

    companion object {
        const val GROUP_TOP = 0
        const val GROUP_CAR_AUDIO = 1
        const val GROUP_METER = 2
        const val GROUP_OTHER = 3
        const val GROUP_LIGHT = 4

        private val CAR_AUDIO = intArrayOf(SpectrumStyle.VISUAL_RETRO, SpectrumStyle.VISUAL_CA_STEREO, SpectrumStyle.VISUAL_CA_EQ)
        private val METERS = intArrayOf(SpectrumStyle.VISUAL_METER, SpectrumStyle.VISUAL_METER_DARK)
        private val OTHERS = intArrayOf(SpectrumStyle.VISUAL_BARS, SpectrumStyle.VISUAL_MIRROR, SpectrumStyle.VISUAL_LINE, SpectrumStyle.VISUAL_DOTS)

        /** 見本の絵の大きさ（画素）。車へ送る量を抑えるため、画面の密度によらず小さく */
        private const val SAMPLE_W = 144
        private const val SAMPLE_H = 90

        /** 見本の絵（色・メーターの設定が変わったら作り直す） */
        private val samples = HashMap<Int, Bitmap>()
        private var samplesVersion = -1

        @Synchronized
        private fun sample(v: Int): Bitmap {
            if (samplesVersion != SpectrumStyle.version) {
                samples.clear()
                samplesVersion = SpectrumStyle.version
            }
            return samples.getOrPut(v) { render(v) }
        }

        /** 見本の絵を描く（設定画面の見本と同じ、止まった 1 枚） */
        private fun render(v: Int): Bitmap {
            val bmp = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cols = IntArray(2)
            SpectrumPainter.screenBackground(v, cols)
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cols[0], cols[1])).apply {
                cornerRadius = 10f
                setBounds(0, 0, SAMPLE_W, SAMPLE_H)
            }.draw(c)
            val n = SpectrumEngine.BANDS
            val bottoms = IntArray(n)
            val tops = IntArray(n)
            SpectrumStyle.colorsFor(SpectrumStyle.theme, SpectrumPreviewView.SAMPLE_ART, bottoms, tops)
            val p = SpectrumPainter(n)
            p.setColors(bottoms, tops)
            // 小さな絵なので、メーターは 1 つを大きく
            p.oneMeter = true
            val s = 1.8f
            c.scale(s, s)
            p.drawDemo(c, RectF(4f, 4f, SAMPLE_W / s - 4f, SAMPLE_H / s - 3f), v, s, 1.35f, 0f, still = true)
            return bmp
        }
    }
}
