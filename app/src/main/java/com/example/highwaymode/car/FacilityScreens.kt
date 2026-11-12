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
import androidx.lifecycle.Lifecycle
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

/**
 * 前方の施設一覧（距離・到着予定・設備）。行を選ぶと詳細。
 *
 * v2.15: Android Auto の決まり（[CarTemplates]）に合わせて作り直した。
 *  - 開いたときの施設の並び（行の数・題名）は、この画面が出ている間は変えない。10 秒ごとに、各行の説明（距離・到着予定など）だけを
 *    いまの値に更新する（「更新」扱いで段は増えない）。通り過ぎた施設の行は「通過しました」になる。
 *    v2.14 までは、行の題名が変わる更新・手動の「更新」で段が増えていき、上限で落ちることがあった。
 *  - 「SA/PA だけ」「すべての施設」の切り替えは、最初の画面に戻してから開き直す（行が入れ替わるため）。
 *  - 右上のボタンは 1 つだけ（文字のボタンは 1 つまで。v2.14 までは 2 つとも文字で、この画面を開くだけで落ちていた）。
 */
class FacilityListScreen(carContext: CarContext, private val restOnly: Boolean = false) : Screen(carContext) {
    private val main = Handler(Looper.getMainLooper())

    /** 開いたときの行（施設の ID と題名）。この画面が出ている間は変えない */
    private var rowIds: List<String>? = null
    private var rowTitles: List<String> = emptyList()
    /** 開いたときの施設（いまの一覧に無くなった行の詳細・種別の絵に使う） */
    private val opened = HashMap<String, FacilityView>()

    private val refresh = object : Runnable {
        override fun run() {
            try {
                val ids = rowIds
                if (ids != null && ids.isEmpty() && current().isNotEmpty() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    // 開いたときは空（高速道路の外）だったが、施設が出てきた → 開き直す（行が増えるのは「更新」にならないため）
                    val ctx = carContext
                    val rest = restOnly
                    CarTemplates.reopenLater(screenManager) { FacilityListScreen(ctx, rest) }
                    return
                }
                // 行の数・題名は変えず、説明だけを更新する
                invalidate()
            } catch (e: Exception) {
                // 接続が切れた
            }
            main.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                main.removeCallbacks(refresh)
                main.postDelayed(refresh, REFRESH_MS)
            }

            override fun onStop(owner: LifecycleOwner) {
                main.removeCallbacks(refresh)
            }
        })
    }

    /** いまの前方の施設（近い順） */
    private fun current(): List<FacilityView> {
        val s = EngineHost.latest ?: return emptyList()
        if (s.status != EngineStatus.ON_HIGHWAY && s.status != EngineStatus.REST_STOP) return emptyList()
        val list = if (restOnly) s.facilities.filter { it.type.isRestArea } else (s.facilities + s.cameras.filter { it.distanceM < 5000 }).sortedBy { it.distanceM }
        return list.take(MAX_ROWS)
    }

    private fun titleOf(f: FacilityView): String =
        CarTemplates.title(f.displayName) + (if (f.noExit) "（出口なし）" else "") + if (f.etcOnly) "（ETC専用）" else ""

    override fun onGetTemplate(): Template = CarTemplates.safe(if (restOnly) "前方の SA/PA" else "前方の施設") {
        val now = current()
        if (rowIds == null) {
            // 開いたときの並びを覚える（同じ ID が 2 つあったら 1 つだけ）
            val seen = LinkedHashMap<String, FacilityView>()
            for (f in now) if (!seen.containsKey(f.id)) seen[f.id] = f
            rowIds = seen.keys.toList()
            rowTitles = seen.values.map { titleOf(it) }
            opened.putAll(seen)
        }
        val ids = rowIds ?: emptyList()
        val byId = HashMap<String, FacilityView>()
        for (f in now) if (!byId.containsKey(f.id)) byId[f.id] = f
        val b = ItemList.Builder()
        for ((i, id) in ids.withIndex()) {
            val f = byId[id]
            val first = opened[id]
            val row = Row.Builder().setTitle(rowTitles[i])
            (f ?: first)?.let { row.setImage(CarBadges.icon(it.type)) }
            if (f == null) {
                // 通り過ぎた（または経路が変わって前方に無くなった）
                row.addText("通過しました")
            } else {
                val line1 = buildString {
                    append(Formats.distance(f.distanceM))
                    if (f.type == FacilityType.CAMERA) {
                        f.maxspeedKmh?.let { append("　制限").append(it).append("km/h") }
                    } else {
                        append("　").append(if (f.closedBefore) "通行止" else Formats.etaLabel(f.etaEpochMs))
                        if (f.navExit) append("　ナビ: ここで降りる")
                        if (f.jamBeforeM >= 200) append("　手前に渋滞").append(Formats.distance(f.jamBeforeM))
                        f.trafficNote?.let { append("　").append(it) }
                        if (!f.navExit) f.navNote?.let { append("　ナビ ").append(it.removePrefix("→ ")) }
                    }
                }
                row.addText(line1)
                if (f.amenities.isNotEmpty()) row.addText(f.amenities.take(6).joinToString("・") { it.label })
                else f.destination?.takeIf { it.isNotBlank() }?.let { row.addText(it) }
            }
            row.setOnClickListener(CarTemplates.click {
                // 押したときの最新の内容で詳細を開く（通り過ぎていたら、開いたときの内容）
                val latest = current().firstOrNull { it.id == id } ?: first
                if (latest != null) screenManager.push(FacilityDetailScreen(carContext, latest))
            })
            b.addItem(row.build())
        }
        b.setNoItemsMessage(
            when {
                EngineHost.latest?.status != EngineStatus.ON_HIGHWAY && EngineHost.latest?.status != EngineStatus.REST_STOP -> "高速道路を走行中に表示します"
                restOnly -> "前方に SA/PA がありません"
                else -> "前方に施設がありません"
            }
        )
        val ctx = carContext
        val rest = restOnly
        @Suppress("DEPRECATION")
        ListTemplate.Builder()
            .setTitle(if (restOnly) "前方の SA/PA" else "前方の施設")
            .setHeaderAction(Action.BACK)
            .setSingleList(b.build())
            .setActionStrip(
                ActionStrip.Builder()
                    // 文字のボタンは 1 つだけ。行が入れ替わるので、最初の画面に戻してから開き直す
                    .addAction(
                        Action.Builder().setTitle(if (restOnly) "すべての施設" else "SA/PA だけ")
                            .setOnClickListener(CarTemplates.click {
                                CarTemplates.reopenLater(screenManager) { FacilityListScreen(ctx, !rest) }
                            }).build()
                    )
                    .build()
            )
            .build()
    }

    companion object {
        private const val MAX_ROWS = 12
        /** 行の説明（距離・到着予定）を更新する間隔 */
        private const val REFRESH_MS = 10_000L
    }
}

/** 施設の詳細 */
class FacilityDetailScreen(carContext: CarContext, private val f: FacilityView) : Screen(carContext) {
    override fun onGetTemplate(): Template = CarTemplates.safe(CarTemplates.title(f.displayName)) {
        val pane = Pane.Builder()
        for ((k, v) in FacilityText.lines(f).take(4)) {
            pane.addRow(Row.Builder().setTitle(CarTemplates.title(k, "—")).addText(v.take(120)).build())
        }
        @Suppress("DEPRECATION")
        PaneTemplate.Builder(pane.build())
            .setTitle(CarTemplates.title(f.displayName))
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
