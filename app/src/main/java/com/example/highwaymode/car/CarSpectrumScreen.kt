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
 *  - 2 段目: まとめの中の種類（アナログメーターは、照明の色・メーターを 1 つにするスイッチも）
 * 選ぶとすぐに反映して、車の画面（音楽）に戻る。行には見本の小さな絵（止まった 1 枚）を付ける。
 *
 * 画面の段の数（[CarTemplates] の決まり 1）: 音楽の画面(1) → メニュー(2) → この画面の 1 段目(3) → 2 段目(4)。
 * **これより深い画面は作らない**（5 段目は一覧にできず、アプリが落とされる。v2.14 は照明の色を 5 段目の一覧にしていて落ちた）。
 * 照明の色は、2 段目の行を押すたびに次の色へ替える（行の題名は変えず、説明と見本の絵だけが変わる＝「更新」扱いで段は増えない）。
 */
class CarSpectrumScreen(carContext: CarContext, private val group: Int = GROUP_TOP) : Screen(carContext) {

    override fun onGetTemplate(): Template = CarTemplates.safe("スペアナの表示") {
        val list = ItemList.Builder()
        val title = when (group) {
            GROUP_CAR_AUDIO -> {
                for (v in CAR_AUDIO) list.addItem(visualRow(v))
                "カーオーディオ"
            }
            GROUP_METER -> {
                for (v in METERS) list.addItem(visualRow(v))
                // 照明の色: 押すたびに次の色へ（行の題名は変えない。見本の絵と説明が変わる）
                val light = SpectrumStyle.meterLight
                val nextLight = (light + 1) % SpectrumStyle.METER_LIGHT_LABELS.size
                list.addItem(
                    Row.Builder()
                        .setTitle("照明の色（押すたびに切り替え）")
                        .addText("いま: ${SpectrumStyle.METER_LIGHT_LABELS[light]} → 次: ${SpectrumStyle.METER_LIGHT_LABELS[nextLight]}")
                        .setOnClickListener(CarTemplates.click {
                            SpectrumStyle.setMeterLight(carContext, (SpectrumStyle.meterLight + 1) % SpectrumStyle.METER_LIGHT_LABELS.size)
                            AppSettings.changed(carContext)
                            // 行の数・題名は同じなので「更新」になる（段は増えない）
                            invalidate()
                        })
                        .build()
                )
                list.addItem(
                    Row.Builder()
                        .setTitle("メーターを 1 つにする")
                        .addText("オフ: 2 つ（低音・高音）／ オン: 1 つ（全体を大きく）")
                        .setToggle(
                            Toggle.Builder { on ->
                                try {
                                    SpectrumStyle.setMeterSingle(carContext, on)
                                    AppSettings.changed(carContext)
                                } catch (e: Exception) {
                                    // 設定を保存できなかった
                                }
                            }.setChecked(SpectrumStyle.meterSingle).build()
                        )
                        .build()
                )
                "アナログメーター"
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
        ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    /** 1 つの表示の種類（押すと選んで、車の画面に戻る） */
    private fun visualRow(v: Int): Row {
        val b = Row.Builder()
            .setTitle(SpectrumStyle.VISUAL_LABELS[v])
            .setOnClickListener(CarTemplates.click {
                SpectrumStyle.setVisual(carContext, v)
                AppSettings.changed(carContext)
                CarToast.makeText(carContext, "スペアナ: ${SpectrumStyle.VISUAL_LABELS[v]}", CarToast.LENGTH_SHORT).show()
                screenManager.popToRoot()
            })
        icon(v)?.let { b.setImage(it, Row.IMAGE_TYPE_LARGE) }
        if (SpectrumStyle.visual == v) b.addText("✓ いまの表示")
        return b.build()
    }

    /** まとめ（押すと中の種類の一覧へ。ここから先は 1 段だけ） */
    private fun groupRow(title: String, g: Int, members: IntArray): Row {
        val cur = SpectrumStyle.visual
        val inGroup = members.contains(cur)
        val b = Row.Builder()
            .setTitle(title)
            .addText(
                if (inGroup) "✓ いま: ${SpectrumStyle.VISUAL_SHORT[cur]}"
                else members.joinToString("・") { SpectrumStyle.VISUAL_SHORT[it] }
            )
            .setBrowsable(true)
            .setOnClickListener(CarTemplates.click { screenManager.push(CarSpectrumScreen(carContext, g)) })
        icon(if (inGroup) cur else members[0])?.let { b.setImage(it, Row.IMAGE_TYPE_LARGE) }
        return b.build()
    }

    /** 見本の絵（描けなかったら絵なしで行だけ出す） */
    private fun icon(v: Int): CarIcon? = try {
        CarIcon.Builder(IconCompat.createWithBitmap(sample(v))).build()
    } catch (e: Exception) {
        null
    }

    companion object {
        const val GROUP_TOP = 0
        const val GROUP_CAR_AUDIO = 1
        const val GROUP_METER = 2
        const val GROUP_OTHER = 3

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
