package com.example.highwaymode.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.ui.FacilityText
import com.example.highwaymode.ui.Formats

/** 種別バッジ（IC / JCT / SA …）のアイコン */
internal object CarBadges {
    private val cache = HashMap<String, CarIcon>()

    fun icon(type: FacilityType): CarIcon = cache.getOrPut(type.name) {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = when (type) {
            FacilityType.TB -> 0xFF1565C0.toInt()
            FacilityType.CAMERA -> 0xFFE53935.toInt()
            else -> 0xFF1E8E3E.toInt()
        }
        c.drawRoundRect(RectF(4f, 4f, size - 4f, size - 4f), 14f, 14f, p)
        val label = when (type) {
            FacilityType.TB -> "料金"
            FacilityType.CAMERA -> "ｵｰﾋﾞｽ"
            else -> type.badge
        }
        p.color = 0xFFFFFFFF.toInt()
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = if (label.length <= 2) 44f else if (label.length <= 3) 34f else 24f
        val fm = p.fontMetrics
        c.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, p)
        CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }
}

/** 前方の施設一覧（距離・到着予定・設備）。行を選ぶと詳細 */
class FacilityListScreen(carContext: CarContext) : Screen(carContext) {
    private val main = Handler(Looper.getMainLooper())
    private var shownTitles: List<String> = emptyList()
    private var restOnly = false

    private val refresh = object : Runnable {
        override fun run() {
            // 行の見出しが同じ間だけ自動で更新（テンプレートの更新回数の制限に配慮）
            if (titles() == shownTitles) invalidate()
            main.postDelayed(this, 10_000L)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                main.postDelayed(refresh, 10_000L)
            }

            override fun onStop(owner: LifecycleOwner) {
                main.removeCallbacks(refresh)
            }
        })
    }

    private fun items(): List<FacilityView> {
        val s = EngineHost.latest ?: return emptyList()
        if (s.status != EngineStatus.ON_HIGHWAY && s.status != EngineStatus.REST_STOP) return emptyList()
        val list = if (restOnly) s.facilities.filter { it.type.isRestArea } else (s.facilities + s.cameras.filter { it.distanceM < 5000 }).sortedBy { it.distanceM }
        return list.take(MAX_ROWS)
    }

    private fun titles(): List<String> = items().map { it.id }

    override fun onGetTemplate(): Template {
        val list = items()
        shownTitles = list.map { it.id }
        val b = ItemList.Builder()
        for (f in list) {
            val row = Row.Builder()
                .setTitle(f.displayName + if (f.etcOnly) "（ETC専用）" else "")
                .setImage(CarBadges.icon(f.type))
            val line1 = buildString {
                append(Formats.distance(f.distanceM))
                if (f.type == FacilityType.CAMERA) {
                    f.maxspeedKmh?.let { append("　制限").append(it).append("km/h") }
                } else {
                    append("　").append(if (f.closedBefore) "通行止" else Formats.clock(f.etaEpochMs) + "着")
                    if (f.jamBeforeM >= 200) append("　手前に渋滞").append(Formats.distance(f.jamBeforeM))
                    f.trafficNote?.let { append("　").append(it) }
                }
            }
            row.addText(line1)
            if (f.amenities.isNotEmpty()) row.addText(f.amenities.take(6).joinToString("・") { it.label })
            else f.destination?.let { row.addText(it) }
            row.setOnClickListener { screenManager.push(FacilityDetailScreen(carContext, f)) }
            b.addItem(row.build())
        }
        b.setNoItemsMessage(if (EngineHost.latest?.status == EngineStatus.ON_HIGHWAY) "前方に施設がありません" else "高速道路を走行中に表示します")
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle(if (restOnly) "前方の SA/PA" else "前方の施設")
            .setHeaderAction(Action.BACK)
            .setSingleList(b.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle(if (restOnly) "すべて" else "SA/PA").setOnClickListener {
                        restOnly = !restOnly
                        invalidate()
                    }.build())
                    .addAction(Action.Builder().setTitle("更新").setOnClickListener { invalidate() }.build())
                    .build()
            )
            .build()
    }

    companion object {
        private const val MAX_ROWS = 12
    }
}

/** 施設の詳細 */
class FacilityDetailScreen(carContext: CarContext, private val f: FacilityView) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        for ((k, v) in FacilityText.lines(f).take(4)) {
            pane.addRow(Row.Builder().setTitle(k).addText(v.take(120)).build())
        }
        @Suppress("DEPRECATION")
        return PaneTemplate.Builder(pane.build())
            .setTitle(f.displayName)
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/** 権限の確認（許可はスマホ側の画面で行う） */
class PermissionScreen(carContext: CarContext, private val missing: List<String>, private val onGranted: () -> Unit) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val needLocation = missing.contains(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val needMic = missing.contains(android.Manifest.permission.RECORD_AUDIO)
        val msg = buildString {
            if (needLocation) append("高速道路の判定に、現在地（正確な位置）の権限が必要です。")
            if (needMic) append("スペクトラム表示に、音声の解析（マイク）の権限が必要です（録音はしません）。")
            if (!needLocation && !needMic) append("車速・燃料の情報を使うと、トンネル内の位置や到着予定がより正確になります。")
            append("「許可」を押して、スマホの画面で許可してください。")
        }
        @Suppress("DEPRECATION")
        return MessageTemplate.Builder(msg)
            .setTitle("Spectrum ハイウェイ")
            .setHeaderAction(Action.APP_ICON)
            .addAction(Action.Builder().setTitle("許可").setOnClickListener {
                carContext.requestPermissions(missing) { _, _ ->
                    onGranted()
                    finish()
                }
            }.build())
            .addAction(Action.Builder().setTitle(if (needLocation) "あとで" else "使わない").setOnClickListener {
                onGranted()
                finish()
            }.build())
            .build()
    }
}
