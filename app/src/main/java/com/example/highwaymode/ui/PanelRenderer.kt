package com.example.highwaymode.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.traffic.TrafficKind
import com.example.highwaymode.core.vehicle.TollCardState
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ハイウェイモード画面の描画（スマホの View と Android Auto の車載画面で共用）。
 * 下から近い順に施設（種別バッジ・名称・距離・到着予定時刻・設備アイコン）を積み上げ、
 * 左側の帯で道路と予測した流れ（渋滞は赤/橙）を表す。上部にオービス・ETC・給油の注意を出す。
 *
 * 横長の画面（Android Auto・スマホ横向き）は、運転中に読みやすいよう施設の一覧だけを大きく出す
 * （車速・制限速度・方位などは出さない）。一覧の上の［︿ この先］で、さらに先の施設・渋滞を順に見られる
 * （［﹀ 手前へ］で戻る。操作しなければ 20 秒で現在地の表示に戻る）。
 */
class PanelRenderer(var density: Float) {

    /** 0=大きく 3 件, 1=一覧, 2=SA/PA のみ */
    var displayMode: Int = 0
    /** 一覧にオービスも並べる */
    var showCamerasInList: Boolean = false
    /** 下部のボタン（スマホ縦画面のみ。横画面・車載画面はメニューを使う） */
    var showButtons: Boolean = true
    /** ボタンを出さないとき、右下に地図データの出典を小さく出す（車載画面） */
    var attribution: Boolean = false
    /** 見出しの右端に時計を出す（車の画面では出さない） */
    var showClock: Boolean = true
    /** 時計を出さないとき、見出しの右側に空けておく幅（Android Auto の右上のボタンと重ならないように）[px] */
    var headerRightReserve: Float = 0f
    var overlayMessage: String? = null

    class Hit(val rect: RectF, val facility: FacilityView?, val button: Int)

    companion object {
        const val BUTTON_DISPLAY = 0
        const val BUTTON_REROUTE = 1
        const val BUTTON_MENU = 2
        /** ［︿ この先］ */
        const val BUTTON_PAGE_NEXT = 10
        /** ［﹀ 手前へ］ */
        const val BUTTON_PAGE_PREV = 11

        /** 先のページを見ていて操作が無ければ、現在地のページに戻るまで */
        const val PAGE_AUTO_RETURN_MS = 20_000L
    }

    // ================================================================== ページ送り（先の施設）

    /** 表示中のページ（0 = 現在地から） */
    @Volatile
    var page: Int = 0
        private set

    /** 直前の描画でのページ数 */
    @Volatile
    var pageCount: Int = 1
        private set

    @Volatile
    private var pageTouchedMs = 0L

    /** さらに先のページへ。@return 送れた */
    fun pageNext(): Boolean {
        pageTouchedMs = System.currentTimeMillis()
        if (page + 1 >= pageCount) return false
        page++
        return true
    }

    /** 手前のページへ。@return 戻れた */
    fun pagePrev(): Boolean {
        pageTouchedMs = System.currentTimeMillis()
        if (page <= 0) return false
        page--
        return true
    }

    fun pageHome() {
        page = 0
    }

    /** 描画中に作るタップ判定（描画スレッド専用） */
    private val hits = ArrayList<Hit>()

    /** 最後に描き終えた画面のタップ判定（別スレッドから読む） */
    @Volatile
    private var publishedHits: List<Hit> = emptyList()

    private val bgFill = Paint().apply { color = 0xFF0A0C10.toInt() }

    private fun dp(v: Float) = v * density

    // ---- 色
    private val cBg = 0xFF0A0C10.toInt()
    private val cBar = 0xFF2B313C.toInt()
    private val cBarEdge = 0xFF3D4552.toInt()
    private val cBarDim = 0xFF1C2028.toInt()
    private val cEta = 0xFF000000.toInt()
    private val cEtaEdge = 0xFF9AA4B2.toInt()
    private val cText = Color.WHITE
    private val cTextDim = 0xFF9AA3AE.toInt()
    private val cSub = 0xFFA7B0BD.toInt()
    private val cGreen = 0xFF1E8E3E.toInt()
    private val cPurple = 0xFF8E24AA.toInt()
    private val cBlue = 0xFF1565C0.toInt()
    private val cStrip = 0xFF7D8590.toInt()
    private val cOrange = 0xFFF08C00.toInt()
    private val cRed = 0xFFE53935.toInt()
    private val cDarkRed = 0xFFB71C1C.toInt()
    private val cAmber = 0xFFFFB300.toInt()
    private val cGray = 0xFF616A75.toInt()
    private val cBtn = 0xFF1A212B.toInt()
    private val cBtnEdge = 0xFF35404E.toInt()
    private val cBtnText = 0xFFDDE5EE.toInt()

    // ---- 描画オブジェクト
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cText
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cSub }
    private val rect = RectF()
    private val path = Path()

    private class Banner(val text: String, val color: Int, val icon: String?, val soft: Boolean = false)

    private enum class RowStyle { BIG, NORMAL, COMPACT }

    /**
     * @param nowMs 時計表示用の現在時刻
     */
    fun draw(c: Canvas, l0: Float, t0: Float, r0: Float, b0: Float, s: HighwaySnapshot?, nowMs: Long) {
        hits.clear()
        try {
            drawPanel(c, l0, t0, r0, b0, s, nowMs)
        } finally {
            publishedHits = ArrayList(hits)
        }
    }

    private fun drawPanel(c: Canvas, l0: Float, t0: Float, r0: Float, b0: Float, s: HighwaySnapshot?, nowMs: Long) {
        // 自分の区画だけを塗る（2 画面表示では左側に Spectrum がある）
        bgFill.color = cBg
        c.drawRect(l0, t0, r0, b0, bgFill)
        val landscape = (r0 - l0) > (b0 - t0) * 1.15f
        val pad = if (landscape) ((b0 - t0) * 0.014f).coerceIn(dp(4f), dp(8f)) else dp(8f)
        val left = l0 + pad
        val right = r0 - pad
        val top = t0 + pad
        val bottom = b0 - pad
        if (right - left < dp(100f) || bottom - top < dp(90f)) return

        // 先のページを見たまま操作が無ければ、現在地のページへ戻す。高速道路を出たら戻す
        val onHw = s?.status == EngineStatus.ON_HIGHWAY || s?.status == EngineStatus.REST_STOP
        if (page > 0 && (!onHw || System.currentTimeMillis() - pageTouchedMs > PAGE_AUTO_RETURN_MS)) page = 0

        if (landscape) {
            // 見出し（路線）と施設の一覧だけ。運転中に読めるよう、一覧を大きく
            val headerH = ((bottom - top) * 0.11f).coerceIn(dp(34f), dp(56f))
            drawHeader(c, s, left, top, right, top + headerH, showSubLine = false, nowMs = nowMs)
            val showAttr = attribution || showButtons
            val attrH = if (showAttr) dp(11f) else 0f
            drawMainArea(c, s, left, top + headerH + pad, right, bottom - attrH, landscape = true)
            if (showAttr) drawAttribution(c, r0, b0)
        } else {
            val headerH = ((bottom - top) * 0.115f).coerceIn(dp(56f), dp(96f))
            drawHeader(c, s, left, top, right, top + headerH, showSubLine = true, nowMs = nowMs)
            val buttonsH = if (showButtons) dp(50f) else 0f
            val btnTop = bottom - buttonsH
            if (showButtons) drawButtons(c, left, btnTop, right, bottom)
            val mainBottom = if (showButtons) btnTop - pad else bottom - (if (attribution) dp(10f) else 0f)
            val statusH = dp(52f)
            drawStatusRow(c, s, left, mainBottom - statusH, right, mainBottom)
            drawMainArea(c, s, left, top + headerH + pad, right, mainBottom - statusH - pad, landscape = false)
            if (!showButtons && attribution) drawAttribution(c, r0, b0)
        }
        overlayMessage?.let { drawOverlay(c, it, left, top, right, bottom) }
    }

    private fun drawAttribution(c: Canvas, r0: Float, b0: Float) {
        textNormal.textSize = dp(9f)
        textNormal.color = 0xFF5F6873.toInt()
        textNormal.textAlign = Paint.Align.RIGHT
        c.drawText("地図 © OpenStreetMap contributors", r0 - dp(6f), b0 - dp(2.5f), textNormal)
        textNormal.textAlign = Paint.Align.LEFT
    }

    private fun drawHeader(c: Canvas, s: HighwaySnapshot?, l: Float, t: Float, r: Float, b: Float, showSubLine: Boolean, nowMs: Long) {
        val h = b - t
        // 時計（車の画面では Android Auto が時刻を出すので省き、右上のボタンの分を空ける）
        val cw: Float
        if (showClock) {
            val clock = Formats.clock(nowMs)
            text.textSize = if (showSubLine) h * 0.46f else h * 0.62f
            cw = text.measureText(clock) + h * 0.4f
            rect.set(r - cw, t, r, t + h * (if (showSubLine) 0.78f else 0.92f))
            fill.color = 0xFF1A1E25.toInt()
            c.drawRoundRect(rect, h * 0.12f, h * 0.12f, fill)
            text.color = cText
            drawTextCentered(c, clock, rect.centerX(), rect.centerY(), text, Paint.Align.CENTER)
        } else {
            cw = headerRightReserve.coerceIn(0f, (r - l) * 0.62f)
        }

        var x = l
        val maxR = r - cw - dp(8f)
        val onHw = s?.status == EngineStatus.ON_HIGHWAY || s?.status == EngineStatus.REST_STOP
        val general = onHw && s?.generalRoad == true
        // 一般国道は「国道23号」→ 青地に「23」（おにぎり標識風）
        val ref = (if (onHw) s?.roadRef else null)?.let { if (general && it.startsWith("国道")) it.removePrefix("国道").removeSuffix("号") else it }
        val line1Y = if (showSubLine) t + h * 0.30f else t + h * 0.46f
        if (ref != null) {
            text.textSize = if (showSubLine) h * 0.30f else h * 0.46f
            val maxBadge = (maxR - l) * 0.3f
            if (text.measureText(ref) + h * 0.3f > maxBadge) text.textSize = fitSize(text, ref, maxBadge - h * 0.3f, text.textSize)
            val bw = text.measureText(ref) + h * 0.3f
            val bh = if (showSubLine) h * 0.22f else h * 0.36f
            rect.set(x, line1Y - bh, x + bw, line1Y + bh)
            // 高速道路ナンバリングは緑、一般国道は青
            fill.color = if (general) cBlue else cGreen
            c.drawRoundRect(rect, h * 0.1f, h * 0.1f, fill)
            stroke.color = Color.WHITE
            stroke.strokeWidth = dp(1.2f)
            rect.inset(dp(2f), dp(2f))
            c.drawRoundRect(rect, h * 0.08f, h * 0.08f, stroke)
            drawTextCentered(c, ref, rect.centerX(), rect.centerY(), text, Paint.Align.CENTER)
            x += bw + dp(8f)
        }
        var generalTagShown = false
        if (general) {
            textNormal.textSize = if (showSubLine) h * 0.2f else h * 0.3f
            val label = "一般道"
            val w = textNormal.measureText(label) + h * 0.2f
            if (maxR - x > w + h * 2.0f) {
                generalTagShown = true
                val bh = if (showSubLine) h * 0.15f else h * 0.24f
                rect.set(x, line1Y - bh, x + w, line1Y + bh)
                fill.color = cGray
                c.drawRoundRect(rect, bh, bh, fill)
                textNormal.color = Color.WHITE
                drawTextCentered(c, label, rect.centerX(), rect.centerY(), textNormal, Paint.Align.CENTER)
                x += w + dp(8f)
            }
        }
        val title = when (s?.status) {
            EngineStatus.ON_HIGHWAY, EngineStatus.REST_STOP -> s.roadName ?: if (general) "一般道（バイパス）" else "高速道路"
            else -> "ハイウェイモード"
        }
        val titleSize = if (showSubLine) h * 0.36f else h * 0.54f
        if (maxR - x > titleSize * 1.5f) {
            text.color = cText
            text.textSize = fitSize(text, title, maxR - x, titleSize)
            drawTextCentered(c, ellipsize(text, title, maxR - x), x, line1Y, text, Paint.Align.LEFT)
        }

        if (!showSubLine) return
        val sub = buildString {
            if (general && !generalTagShown) append("一般道（信号・歩行者に注意）　")
            if (onHw) {
                s?.directionText?.let { append(it) }
                s?.nextRoadText?.let { if (isNotEmpty()) append("　"); append(it) }
            }
            val dm = s?.dataMessage
            if (dm != null) {
                if (isNotEmpty()) append("　")
                append(dm)
            }
        }
        if (sub.isNotEmpty()) {
            textNormal.textSize = fitSize(textNormal, sub, maxR - l, h * 0.22f)
            textNormal.color = if (s?.dataMessage != null && !onHw) cAmber else cSub
            drawTextCentered(c, sub, l, t + h * 0.78f, textNormal, Paint.Align.LEFT)
        }
    }

    /** 上部に出す注意（オービス接近・ETC カード・給油・通行止め。横画面ではデータの状態も） */
    private fun banners(s: HighwaySnapshot, withDataMessage: Boolean): List<Banner> {
        val out = ArrayList<Banner>()
        val cam = s.cameras.firstOrNull()
        if (cam != null && cam.distanceM < 2000) {
            val lim = cam.maxspeedKmh?.let { "　制限${it}km/h" } ?: ""
            val who = if (cam.userDefined) "オービス（登録地点）" else "オービス"
            out.add(Banner("$who ${Formats.distance(cam.distanceM)}先$lim", cRed, "camera"))
        }
        val v = s.vehicle
        if (v != null && (v.tollCard == TollCardState.NOT_INSERTED || v.tollCard == TollCardState.INVALID)) {
            out.add(Banner(if (v.tollCard == TollCardState.NOT_INSERTED) "ETCカードが挿入されていません" else "ETCカードを確認してください（無効）", cPurple, "etc"))
        }
        if (v?.fuelAdvice != null) {
            out.add(Banner(v.fuelAdvice!!, if (v.fuelAdviceLevel >= 2) cDarkRed else 0xFFB26A00.toInt(), "fuel"))
        }
        val closure = s.traffic?.events?.firstOrNull { it.second == TrafficKind.CLOSURE }
        if (closure != null && closure.first < 50_000) {
            out.add(Banner("${Formats.distance(closure.first)}先 通行止め" + (closure.third?.let { "（${it.take(18)}）" } ?: ""), cDarkRed, null))
        }
        if (withDataMessage) s.dataMessage?.let { out.add(Banner(it, cAmber, null, soft = true)) }
        return out
    }

    /** 一覧に並べる施設（表示モード・オービスの設定に応じて） */
    private fun itemsFor(s: HighwaySnapshot): List<FacilityView> {
        val base = when (displayMode) {
            2 -> s.facilities.filter { it.type.isRestArea }
            else -> s.facilities
        }
        return if (showCamerasInList && displayMode != 2 && s.cameras.isNotEmpty()) (base + s.cameras).sortedBy { it.distanceM } else base
    }

    /** 1 ページの行数と行の高さ */
    private fun rowsFor(areaH: Float, landscape: Boolean): Pair<Int, Float> = when (displayMode) {
        1 -> {
            val rh = if (landscape) max(areaH / 5f, dp(56f)) else dp(68f)
            val n = max(1, (areaH / rh).toInt())
            n to (if (landscape) areaH / n else rh)
        }
        2 -> {
            val n = if (landscape) 3 else 4
            n to (if (landscape) areaH / n else min(areaH / n, dp(150f)))
        }
        else -> if (landscape) {
            // 大きく: 3 件（縦に十分な余裕があれば 4 件）
            val n = if (areaH / 4 >= dp(118f)) 4 else 3
            n to areaH / n
        } else {
            val n = if (areaH / 4 >= dp(135f)) 4 else 3
            n to min(areaH / n, dp(155f))
        }
    }

    private fun drawMainArea(c: Canvas, s: HighwaySnapshot?, l: Float, t0: Float, r: Float, b: Float, landscape: Boolean) {
        if (s == null || (s.status != EngineStatus.ON_HIGHWAY && s.status != EngineStatus.REST_STOP)) {
            drawStatusMessage(c, s, l, t0, r, b)
            return
        }
        // ---- 注意の帯
        var t = t0
        val bannerH = if (landscape) ((b - t0) * 0.085f).coerceIn(dp(26f), dp(40f)) else dp(30f)
        for (bn in banners(s, withDataMessage = landscape).take(2)) {
            val bh = if (bn.soft) bannerH * 0.8f else bannerH
            rect.set(l, t, r, t + bh)
            if (bn.soft) {
                // データの状態（GPS・通信など）は控えめに: 暗い地に黄色の文字
                fill.color = 0xFF2A2410.toInt()
                c.drawRoundRect(rect, dp(5f), dp(5f), fill)
                textNormal.color = bn.color
                val msg = "⚠ " + bn.text
                textNormal.textSize = fitSize(textNormal, msg, r - l - dp(16f), bh * 0.56f)
                drawTextCentered(c, ellipsize(textNormal, msg, r - l - dp(16f)), l + dp(8f), t + bh / 2, textNormal, Paint.Align.LEFT)
            } else {
                fill.color = bn.color
                c.drawRoundRect(rect, dp(5f), dp(5f), fill)
                var tx = l + dp(10f)
                if (bn.icon == "camera") {
                    drawCameraIcon(c, tx + bh * 0.35f, t + bh / 2, bh * 0.36f)
                    tx += bh * 0.8f
                }
                text.color = cText
                text.textSize = fitSize(text, bn.text, r - tx - dp(8f), bh * 0.58f)
                drawTextCentered(c, bn.text, tx, t + bh / 2, text, Paint.Align.LEFT)
            }
            t += bh + dp(4f)
        }

        // ---- ページ送りの帯・一覧の範囲
        val list = itemsFor(s)
        val pageBarH = if (landscape) ((b - t) * 0.12f).coerceIn(dp(34f), dp(50f)) else dp(42f)
        val markerH = if (landscape) dp(22f) else dp(40f)
        val listTop = t + pageBarH + dp(6f)
        val rowsBottom = b - markerH
        val areaH = rowsBottom - listTop
        val (n, rowH) = rowsFor(areaH, landscape)
        val pages = max(1, (list.size + n - 1) / n)
        pageCount = pages
        val pg = page.coerceIn(0, pages - 1)
        if (pg != page) page = pg
        val from = min(pg * n, list.size)
        val shown = list.subList(from, min(from + n, list.size))
        drawPageBar(c, s, list, pg, pages, from + shown.size, l, t, r, t + pageBarH)

        // ---- 道路の帯（下=現在地。先のページでは下端を点線にする）
        val stripX = l + (if (landscape) dp(17f) else dp(16f))
        val rowLeft = l + (if (landscape) dp(40f) else dp(38f))
        val carY = b - markerH / 2
        val stripW = if (landscape) dp(11f) else dp(10f)
        val topY = if (shown.isEmpty()) listTop else rowsBottom - (shown.size - 1) * rowH - rowH * 0.69f
        val stripBottom = if (pg == 0) carY else rowsBottom
        fill.color = cStrip
        rect.set(stripX - stripW / 2, topY - dp(10f), stripX + stripW / 2, stripBottom)
        c.drawRect(rect, fill)
        var prevY = stripBottom
        for (i in shown.indices) {
            val f = shown[i]
            val cy = rowsBottom - i * rowH - rowH * 0.69f
            if (f.type != FacilityType.CAMERA) {
                val flowColor = when {
                    f.closedBefore -> cDarkRed
                    f.flowBefore < 0.55 || f.jamBeforeM > 1000 -> cRed
                    f.flowBefore < 0.85 || f.jamBeforeM > 200 -> cOrange
                    else -> 0
                }
                if (flowColor != 0) {
                    fill.color = flowColor
                    rect.set(stripX - stripW / 2, cy, stripX + stripW / 2, prevY)
                    c.drawRect(rect, fill)
                }
                prevY = cy
            }
            stroke.color = if (f.type == FacilityType.CAMERA) cRed else cStrip
            stroke.strokeWidth = dp(2f)
            c.drawLine(stripX, cy, rowLeft, cy, stroke)
        }

        // ---- 行
        val style = when {
            displayMode == 1 -> RowStyle.COMPACT
            landscape -> RowStyle.BIG
            else -> RowStyle.NORMAL
        }
        for (i in shown.indices) {
            val rb = rowsBottom - i * rowH
            drawRow(c, shown[i], rowLeft, rb - rowH, r, rb, style)
        }
        if (shown.isEmpty()) {
            textNormal.textSize = if (landscape) dp(20f) else dp(16f)
            textNormal.color = cSub
            val msg = if (s.routeTruncated) "前方の地図データを取得中…" else "前方に施設がありません"
            drawTextCentered(c, msg, (rowLeft + r) / 2, (listTop + rowsBottom) / 2, textNormal, Paint.Align.CENTER)
        }

        // ---- 自車マーク（先のページでは「…」）
        if (pg == 0) {
            val mr = min(markerH * 0.42f, dp(17f))
            fill.color = 0xFF2A2F37.toInt()
            c.drawCircle(stripX, carY, mr, fill)
            stroke.color = 0xFFCFD6DE.toInt()
            stroke.strokeWidth = dp(2f)
            c.drawCircle(stripX, carY, mr, stroke)
            path.reset()
            path.moveTo(stripX, carY - mr * 0.62f)
            path.lineTo(stripX + mr * 0.48f, carY + mr * 0.5f)
            path.lineTo(stripX, carY + mr * 0.22f)
            path.lineTo(stripX - mr * 0.48f, carY + mr * 0.5f)
            path.close()
            fill.color = 0xFF1E88E5.toInt()
            c.drawPath(path, fill)
        } else {
            fill.color = cStrip
            for (k in 0..2) c.drawCircle(stripX, rowsBottom + markerH * (0.2f + 0.28f * k), dp(2.2f), fill)
        }
        if (!landscape) {
            text.color = cText
            val label = buildString {
                if (pg > 0) append("現在地から ${pg * n + 1}件目〜")
                else if (s.status == EngineStatus.REST_STOP) append(s.directionText ?: "停車中")
                else append(s.roadName ?: "")
                if (s.deadReckoning) append("　（推測航法）")
            }
            text.textSize = fitSize(text, label, r - rowLeft - dp(8f), dp(16f))
            drawTextCentered(c, label, rowLeft + dp(4f), carY, text, Paint.Align.LEFT)
        } else if (pg == 0 && s.deadReckoning) {
            textNormal.color = cAmber
            textNormal.textSize = markerH * 0.55f
            drawTextCentered(c, "推測航法（トンネル内など）", rowLeft, carY, textNormal, Paint.Align.LEFT)
        }
    }

    /**
     * 一覧の上の帯。現在地のページでは［︿ この先 … 先の渋滞または次の施設 … 1/4］、
     * 先のページでは［﹀ 手前へ］［︿ この先 …］。
     */
    private fun drawPageBar(
        c: Canvas, s: HighwaySnapshot, list: List<FacilityView>, pg: Int, pages: Int, shownEnd: Int,
        l: Float, t: Float, r: Float, b: Float
    ) {
        val h = b - t
        val hasNext = pg + 1 < pages
        var nextL = l
        if (pg > 0) {
            // ［﹀ 手前へ］（1 ページ目に戻るときは「現在地へ」）
            val w = (r - l) * 0.34f
            rect.set(l, t, l + w, b)
            drawButtonBg(c, rect, enabled = true)
            val label = if (pg == 1) "現在地へ" else "手前へ"
            text.color = cBtnText
            text.textSize = fitSize(text, label, w - h * 1.2f, h * 0.44f)
            val tw = text.measureText(label)
            val cx = rect.centerX() - (tw + h * 0.55f) / 2
            drawChevron(c, cx + h * 0.2f, (t + b) / 2, h * 0.2f, up = false, color = cBtnText)
            drawTextCentered(c, label, cx + h * 0.55f, (t + b) / 2, text, Paint.Align.LEFT)
            hits.add(Hit(RectF(rect), null, BUTTON_PAGE_PREV))
            nextL = l + w + dp(6f)
        }
        // ［︿ この先］
        rect.set(nextL, t, r, b)
        drawButtonBg(c, rect, enabled = hasNext)
        if (hasNext) hits.add(Hit(RectF(rect), null, BUTTON_PAGE_NEXT))
        val fg = if (hasNext) cBtnText else 0xFF6B7480.toInt()
        var x = nextL + h * 0.3f
        drawChevron(c, x + h * 0.2f, (t + b) / 2, h * 0.2f, up = true, color = fg)
        x += h * 0.6f
        text.color = fg
        text.textSize = h * 0.44f
        val label = "この先"
        drawTextCentered(c, label, x, (t + b) / 2, text, Paint.Align.LEFT)
        x += text.measureText(label) + h * 0.35f
        // 右端: ページ
        textNormal.textSize = h * 0.36f
        textNormal.color = 0xFF8A94A1.toInt()
        val pageText = "${pg + 1}/$pages"
        val pw = textNormal.measureText(pageText)
        drawTextCentered(c, pageText, r - h * 0.3f - pw, (t + b) / 2, textNormal, Paint.Align.LEFT)
        // 中: この先の渋滞・次の施設
        val (summary, color) = beyondSummary(s, list, shownEnd)
        val maxW = r - h * 0.6f - pw - x
        if (maxW > h) {
            textNormal.color = color
            textNormal.textSize = fitSize(textNormal, summary, maxW, h * 0.40f)
            drawTextCentered(c, ellipsize(textNormal, summary, maxW), x, (t + b) / 2, textNormal, Paint.Align.LEFT)
        }
    }

    /** 表示中の範囲より先の、いちばん気にすべきこと（渋滞・事故・通行止め。無ければ次の施設） */
    private fun beyondSummary(s: HighwaySnapshot, list: List<FacilityView>, from: Int): Pair<String, Int> {
        if (from >= list.size) {
            return (if (s.routeTruncated) "前方の地図データを読み込み中…" else "この先の施設はありません") to 0xFF8A94A1.toInt()
        }
        val rest = list.subList(from, list.size)
        val ev = rest.firstOrNull { it.type != FacilityType.CAMERA && (it.closedBefore || it.jamBeforeM >= 200 || it.trafficNote != null) }
        if (ev != null) {
            val what = when {
                ev.closedBefore -> "通行止"
                ev.jamBeforeM >= 200 -> "渋滞${Formats.distance(ev.jamBeforeM)}"
                else -> ev.trafficNote ?: "交通情報"
            }
            val color = when {
                ev.closedBefore || ev.jamBeforeM >= 1000 -> 0xFFFF6B66.toInt()
                else -> 0xFFFFA94D.toInt()
            }
            return "$what（${ev.displayName}の手前）" to color
        }
        val nx = rest.first()
        val name = nx.displayName + if (nx.noExit) "（出口なし）" else ""
        return "$name ${Formats.distance(nx.distanceM)}" to cSub
    }

    private fun drawButtonBg(c: Canvas, r: RectF, enabled: Boolean) {
        val rad = r.height() * 0.24f
        fill.color = if (enabled) cBtn else 0xFF12161C.toInt()
        c.drawRoundRect(r, rad, rad, fill)
        stroke.color = if (enabled) cBtnEdge else 0xFF222831.toInt()
        stroke.strokeWidth = dp(1f)
        c.drawRoundRect(r, rad, rad, stroke)
    }

    /** 山形の矢印（up=true で ︿） */
    private fun drawChevron(c: Canvas, cx: Float, cy: Float, s: Float, up: Boolean, color: Int) {
        stroke.color = color
        stroke.strokeWidth = max(dp(2.2f), s * 0.34f)
        stroke.strokeCap = Paint.Cap.ROUND
        val dy = if (up) -s * 0.5f else s * 0.5f
        c.drawLine(cx - s, cy - dy, cx, cy + dy, stroke)
        c.drawLine(cx, cy + dy, cx + s, cy - dy, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawCameraIcon(c: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = Color.WHITE
        rect.set(cx - r, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f)
        c.drawRoundRect(rect, r * 0.2f, r * 0.2f, fill)
        path.reset()
        path.moveTo(cx + r * 0.6f, cy - r * 0.25f)
        path.lineTo(cx + r, cy - r * 0.55f)
        path.lineTo(cx + r, cy + r * 0.55f)
        path.lineTo(cx + r * 0.6f, cy + r * 0.25f)
        path.close()
        c.drawPath(path, fill)
        fill.color = cRed
        c.drawCircle(cx - r * 0.2f, cy, r * 0.28f, fill)
    }

    private fun drawRow(c: Canvas, f: FacilityView, l: Float, t: Float, r: Float, b: Float, style: RowStyle) {
        val h = b - t
        // 名称の段と到着予定の段の割合・文字の大きさ
        val nameTopK: Float
        val nameBottomK: Float
        val nameFontK: Float
        val numK: Float
        val etaBottomK: Float
        val etaFontK: Float
        when (style) {
            RowStyle.BIG -> {
                nameTopK = 0.03f; nameBottomK = 0.60f; nameFontK = 0.64f; numK = 0.74f; etaBottomK = 0.97f; etaFontK = 0.74f
            }
            RowStyle.NORMAL -> {
                nameTopK = 0.06f; nameBottomK = 0.56f; nameFontK = 0.56f; numK = 0.66f; etaBottomK = 0.95f; etaFontK = 0.66f
            }
            RowStyle.COMPACT -> {
                nameTopK = 0.04f; nameBottomK = 0.56f; nameFontK = 0.58f; numK = 0.66f; etaBottomK = 0.95f; etaFontK = 0.66f
            }
        }
        val nameTop = t + h * nameTopK
        val nameBottom = t + h * nameBottomK
        val nameH = nameBottom - nameTop
        val isCam = f.type == FacilityType.CAMERA
        // この方向には出口が無い IC（ハーフ IC）は控えめに
        val dim = f.noExit && !isCam
        // 名称バー
        rect.set(l, nameTop, r, nameBottom)
        fill.color = when {
            isCam -> 0xFF3A2224.toInt()
            dim -> cBarDim
            else -> cBar
        }
        c.drawRoundRect(rect, dp(4f), dp(4f), fill)
        stroke.color = if (isCam) 0xFF6D2E31.toInt() else cBarEdge
        stroke.strokeWidth = dp(1f)
        c.drawRoundRect(rect, dp(4f), dp(4f), stroke)

        // 種別バッジ
        val badge = when (f.type) {
            FacilityType.TB -> "料金所"
            else -> f.type.badge
        }
        val bs = nameH * 0.78f
        val bx = l + nameH * 0.11f
        val by = nameTop + nameH * 0.11f
        text.textSize = bs * 0.5f
        val bw = max(bs, text.measureText(badge) + bs * 0.3f)
        rect.set(bx, by, bx + bw, by + bs)
        fill.color = when {
            dim -> cGray
            f.type == FacilityType.TB -> cBlue
            isCam -> cRed
            else -> cGreen
        }
        c.drawRoundRect(rect, dp(3f), dp(3f), fill)
        text.color = if (dim) 0xFFD0D5DB.toInt() else cText
        text.textSize = fitSize(text, badge, bw - bs * 0.15f, bs * 0.5f)
        drawTextCentered(c, badge, rect.centerX(), rect.centerY(), text, Paint.Align.CENTER)

        // 距離（右寄せ、数値大・単位小）
        val (num, unit) = Formats.distanceParts(f.distanceM)
        val numSize = nameH * numK
        val unitSize = nameH * 0.40f
        text.color = if (dim) cTextDim else cText
        text.textSize = unitSize
        val uw = text.measureText(unit)
        text.textSize = numSize
        val nw = text.measureText(num)
        val dx = r - nameH * 0.2f
        val baseY = nameBottom - nameH * 0.2f
        text.textSize = unitSize
        drawTextBaseline(c, unit, dx - uw, baseY, text)
        text.textSize = numSize
        drawTextBaseline(c, num, dx - uw - nw - dp(2f), baseY, text)

        // 名称
        val nameX = bx + bw + nameH * 0.18f
        val nameMax = dx - uw - nw - dp(12f) - nameX
        val nm = if (isCam) (f.name + if (f.userDefined) "（登録）" else "") else f.name.ifEmpty { f.displayName }
        // 長い名前（道の駅など）は小さくしてでも全体を出す。それでも入らなければ 2 行に分け、なお入らなければ末尾を省く
        text.textSize = fitSize(text, nm, nameMax, nameH * nameFontK, minScale = 0.4f)
        if (text.measureText(nm) > nameMax && nm.length >= 6 && nameMax > 0) {
            val (l1, l2) = splitName(nm)
            val size2 = min(nameH * 0.36f, min(fitSize(text, l1, nameMax, nameH * 0.36f), fitSize(text, l2, nameMax, nameH * 0.36f)))
            text.textSize = size2
            val cy = (nameTop + nameBottom) / 2
            drawTextCentered(c, ellipsize(text, l1, nameMax), nameX, cy - nameH * 0.21f, text, Paint.Align.LEFT)
            drawTextCentered(c, ellipsize(text, l2, nameMax), nameX, cy + nameH * 0.21f, text, Paint.Align.LEFT)
        } else {
            drawTextCentered(c, ellipsize(text, nm, nameMax), nameX, (nameTop + nameBottom) / 2, text, Paint.Align.LEFT)
        }
        text.color = cText

        // 到着予定時刻バー（左端を斜めに）
        val etaTop = nameBottom - dp(1f)
        val etaBottom = t + h * etaBottomK
        val etaH = etaBottom - etaTop
        val slant = min(etaH * 0.45f, dp(22f))
        val el = l + nameH * 0.55f
        path.reset()
        path.moveTo(el, etaTop)
        path.lineTo(r, etaTop)
        path.lineTo(r, etaBottom)
        path.lineTo(el + slant, etaBottom)
        path.close()
        fill.color = cEta
        c.drawPath(path, fill)
        stroke.color = if (dim) 0xFF59616C.toInt() else cEtaEdge
        stroke.strokeWidth = dp(1.2f)
        c.drawPath(path, stroke)

        val etaText = when {
            isCam -> f.maxspeedKmh?.let { "制限 ${it}km/h" } ?: "速度取締機"
            f.closedBefore -> "通行止"
            else -> Formats.clock(f.etaEpochMs)
        }
        text.textSize = min(etaH * (if (isCam) 0.5f else etaFontK), if (style == RowStyle.BIG) dp(46f) else dp(36f))
        text.color = when {
            f.closedBefore && !isCam -> cRed
            dim -> cTextDim
            else -> cText
        }
        val etaX = el + slant + etaH * 0.25f
        drawTextCentered(c, etaText, etaX, (etaTop + etaBottom) / 2, text, Paint.Align.LEFT)
        text.color = cText
        var ix = etaX + text.measureText(etaText) + etaH * 0.4f
        val iconSize = min(etaH * 0.78f, if (style == RowStyle.BIG) dp(42f) else dp(34f))
        val iy = etaTop + (etaH - iconSize) / 2

        // 交通情報のタグ（手前の区間の渋滞・事故など）・出口なし・ETC 専用
        val tags = ArrayList<Pair<String, Int>>()
        if (!isCam) {
            if (f.noExit) tags.add("出口なし" to cGray)
            if (f.jamBeforeM >= 200) tags.add("渋滞${Formats.distance(f.jamBeforeM)}" to cRed)
            f.trafficNote?.let { if (!f.closedBefore || it != "通行止") tags.add(it to cOrange) }
            if (f.etcOnly) tags.add("ETC専用" to cPurple)
        }
        for ((label, color) in tags) {
            text.textSize = iconSize * 0.5f
            val w = text.measureText(label) + iconSize * 0.4f
            if (ix + w >= r - dp(4f)) break
            rect.set(ix, iy, ix + w, iy + iconSize)
            fill.color = color
            c.drawRoundRect(rect, dp(3f), dp(3f), fill)
            text.color = cText
            drawTextCentered(c, label, rect.centerX(), rect.centerY(), text, Paint.Align.CENTER)
            ix += w + dp(5f)
        }
        for (a in f.amenities) {
            if (ix + iconSize > r - dp(4f)) break
            AmenityIcons.draw(c, a, ix, iy, iconSize)
            ix += iconSize + dp(4f)
        }
        hits.add(Hit(RectF(l, t, r, b), f, -1))
    }

    private fun drawStatusRow(c: Canvas, s: HighwaySnapshot?, l: Float, t: Float, r: Float, b: Float) {
        val h = b - t
        var x = l
        // 制限速度標識（白地に赤枠・青数字）
        val lim = s?.limitKmh
        if (s?.status == EngineStatus.ON_HIGHWAY && lim != null) {
            val rad = h * 0.46f
            val cx = x + rad
            val cy = t + h / 2
            fill.color = Color.WHITE
            c.drawCircle(cx, cy, rad, fill)
            stroke.color = 0xFFD32F2F.toInt()
            stroke.strokeWidth = rad * 0.2f
            c.drawCircle(cx, cy, rad * 0.88f, stroke)
            text.color = cBlue
            text.textSize = rad * (if (lim >= 100) 0.72f else 0.92f)
            drawTextCentered(c, lim.toString(), cx, cy, text, Paint.Align.CENTER)
            text.color = cText
            if (!s.limitTagged) {
                textNormal.textSize = h * 0.22f
                textNormal.color = cSub
                drawTextCentered(c, "推定", cx, t + h * 1.02f, textNormal, Paint.Align.CENTER)
            }
            x += rad * 2 + dp(12f)
        }
        // 車速
        val spd = s?.speedKmh
        text.textSize = h * 0.62f
        text.color = cText
        val spText = spd?.roundToInt()?.toString() ?: "--"
        drawTextCentered(c, spText, x, t + h / 2, text, Paint.Align.LEFT)
        x += text.measureText(spText) + dp(4f)
        textNormal.textSize = h * 0.3f
        textNormal.color = cSub
        drawTextCentered(c, "km/h", x, t + h * 0.62f, textNormal, Paint.Align.LEFT)
        x += textNormal.measureText("km/h") + dp(14f)
        drawChips(c, s, x, t, r, b)
    }

    private fun drawChips(c: Canvas, s: HighwaySnapshot?, l: Float, t: Float, r: Float, b: Float) {
        val h = b - t
        val chips = ArrayList<Pair<String, Int>>()
        if (s != null) {
            val acc = s.gpsAccuracyM
            when {
                s.deadReckoning -> chips.add((if (s.speedSource == "車速信号") "推測航法（車速）" else "推測航法") to cAmber)
                acc != null && (s.gpsAgeSec ?: 99.0) < 3 -> chips.add("GPS ±${acc.roundToInt()}m" to 0xFF43A047.toInt())
                else -> chips.add("GPS 待ち" to cAmber)
            }
            val tr = s.traffic
            if (tr != null) {
                val jd = tr.nextJamDistanceM
                val jl = tr.nextJamLengthM
                when {
                    jd != null && jl != null && jd < 40_000 -> {
                        val delay = tr.nextJamDelaySec?.let { if (it >= 60) " +${(it / 60).roundToInt()}分" else "" } ?: ""
                        val trend = when (tr.nextJamTrend) {
                            1 -> "↑"
                            -1 -> "↓"
                            else -> ""
                        }
                        val where = if (jd < 50) "渋滞中 残り" else "${Formats.distance(jd)}先 渋滞"
                        chips.add("$where${Formats.distance(jl)}$trend$delay" to cRed)
                    }
                    tr.message != null -> chips.add(tr.message!! to cGray)
                    tr.fetchedWallMs != null -> {
                        val age = ((System.currentTimeMillis() - tr.fetchedWallMs!!) / 60_000).toInt()
                        chips.add("交通情報 ${tr.provider ?: ""} ${if (age <= 0) "取得済" else "${age}分前"}" to 0xFF2E7D32.toInt())
                    }
                }
            }
            if (s.vehicle?.connected == true) {
                chips.add((if (s.vehicle?.speedSignal == true) "車速信号" else "車両接続") to 0xFF1565C0.toInt())
            }
            val hs = s.headingSigmaDeg
            if (s.headingDeg != null && hs != null) {
                chips.add(String.format(Locale.US, "方位 %s ±%.1f°", s.headingSource, hs) to 0xFF546E7A.toInt())
            }
            if (s.status == EngineStatus.ON_HIGHWAY && s.matchConfidence < 0.8) chips.add("車線判定中" to cOrange)
        }
        var x = l
        textNormal.textSize = h * 0.27f
        val ch = h * 0.46f
        var row = 0
        for ((label, color) in chips) {
            val w = textNormal.measureText(label) + ch * 0.6f
            if (x + w > r) {
                if (row == 1) break
                row = 1
                x = l
                if (x + w > r) break
            }
            val y0 = t + h * 0.04f + row * (ch + h * 0.04f)
            rect.set(x, y0, x + w, y0 + ch)
            fill.color = color
            c.drawRoundRect(rect, ch / 2, ch / 2, fill)
            textNormal.color = Color.WHITE
            drawTextCentered(c, label, rect.centerX(), rect.centerY(), textNormal, Paint.Align.CENTER)
            x += w + dp(6f)
        }
    }

    private fun drawStatusMessage(c: Canvas, s: HighwaySnapshot?, l: Float, t: Float, r: Float, b: Float) {
        val (title, sub) = when (s?.status) {
            null, EngineStatus.WAITING_GPS -> "GPS 測位中…" to "空が開けた場所で少しお待ちください"
            EngineStatus.LOADING_MAP -> "高速道路データを読み込み中…" to (s.dataMessage ?: "周辺の高速道路を読み込んでいます")
            EngineStatus.OFF_HIGHWAY -> "高速道路外" to "高速道路・有料道路・都市高速・主要バイパスに入ると自動で表示します"
            EngineStatus.GENERAL_ROAD_MODE -> "一般道モード" to "高速道路の判定を停止中です（メニューから解除）"
            EngineStatus.ON_HIGHWAY, EngineStatus.REST_STOP -> "" to ""
        }
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        text.color = cText
        text.textSize = fitSize(text, title, r - l, dp(28f))
        drawTextCentered(c, title, cx, cy - dp(18f), text, Paint.Align.CENTER)
        textNormal.color = cSub
        textNormal.textSize = fitSize(textNormal, sub, r - l, dp(15f))
        drawTextCentered(c, sub, cx, cy + dp(18f), textNormal, Paint.Align.CENTER)
        // GPS・通信などの問題（読み込み中の表示で出していないもの）
        val dm = s?.dataMessage
        if (dm != null && s.status != EngineStatus.LOADING_MAP) {
            textNormal.color = cAmber
            textNormal.textSize = fitSize(textNormal, dm, r - l, dp(15f))
            drawTextCentered(c, dm, cx, cy + dp(48f), textNormal, Paint.Align.CENTER)
        }
    }

    private fun drawButtons(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val labels = arrayOf(
            when (displayMode) {
                1 -> "表示: 一覧"
                2 -> "表示: SA/PA"
                else -> "表示変更"
            },
            "再探索", "メニュー"
        )
        val attribW = dp(118f)
        val gap = dp(6f)
        val bw = (r - l - attribW - gap * labels.size) / labels.size
        var x = l
        for (i in labels.indices) {
            rect.set(x, t + dp(4f), x + bw, b)
            fill.color = 0xFF232830.toInt()
            c.drawRoundRect(rect, dp(6f), dp(6f), fill)
            stroke.color = cBarEdge
            stroke.strokeWidth = dp(1f)
            c.drawRoundRect(rect, dp(6f), dp(6f), stroke)
            text.color = cText
            text.textSize = fitSize(text, labels[i], bw - dp(8f), dp(17f))
            drawTextCentered(c, labels[i], rect.centerX(), rect.centerY(), text, Paint.Align.CENTER)
            hits.add(Hit(RectF(rect), null, i))
            x += bw + gap
        }
        textNormal.textSize = dp(9.5f)
        textNormal.color = 0xFF7D8590.toInt()
        drawTextCentered(c, "地図 © OpenStreetMap", r - attribW / 2, (t + b) / 2 - dp(6f), textNormal, Paint.Align.CENTER)
        drawTextCentered(c, "contributors (ODbL)", r - attribW / 2, (t + b) / 2 + dp(8f), textNormal, Paint.Align.CENTER)
    }

    private fun drawOverlay(c: Canvas, msg: String, l: Float, t: Float, r: Float, b: Float) {
        fill.color = 0xE6000000.toInt()
        c.drawRect(l, t, r, b, fill)
        textNormal.color = cText
        textNormal.textSize = dp(17f)
        val lines = msg.split('\n')
        var y = (t + b) / 2 - (lines.size - 1) * dp(14f)
        for (line in lines) {
            drawTextCentered(c, line, (l + r) / 2, y, textNormal, Paint.Align.CENTER)
            y += dp(28f)
        }
    }

    // ================================================================== text helpers

    private fun drawTextCentered(c: Canvas, s: String, x: Float, cy: Float, p: Paint, align: Paint.Align) {
        p.textAlign = align
        val fm = p.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawTextBaseline(c: Canvas, s: String, x: Float, baseline: Float, p: Paint) {
        p.textAlign = Paint.Align.LEFT
        c.drawText(s, x, baseline, p)
    }

    /** 長い名前を 2 行に分ける（「道の駅」の後・「・」「」」の後など、切れ目の良い所を優先） */
    private fun splitName(s: String): Pair<String, String> {
        val half = s.length / 2
        var best = -1
        var bestScore = Int.MAX_VALUE
        for (i in 2 until s.length - 1) {
            val prev = s[i - 1]
            val good = prev == '・' || prev == '」' || prev == '）' || prev == ')' || s.startsWith("道の駅") && i == 3 || s[i] == '「'
            if (!good) continue
            val score = kotlin.math.abs(i - half)
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        // 切れ目の良い所でも、2 行の長さが大きく違うなら真ん中で分ける（長い方の行が小さくなりすぎないように）
        if (best < 0 || maxOf(best, s.length - best) > (s.length + 1) / 2 + 1) best = (s.length + 1) / 2
        return s.substring(0, best) to s.substring(best)
    }

    /** 収まらなければ末尾を「…」にする */
    private fun ellipsize(p: Paint, s: String, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var n = s.length
        while (n > 1 && p.measureText(s.substring(0, n) + "…") > maxWidth) n--
        return s.substring(0, n) + "…"
    }

    /** maxWidth に収まる文字サイズ（最大 size、最小 size×minScale） */
    private fun fitSize(p: Paint, s: String, maxWidth: Float, size: Float, minScale: Float = 0.5f): Float {
        if (maxWidth <= 0) return size * minScale
        p.textSize = size
        val w = p.measureText(s)
        if (w <= maxWidth) return size
        // 文字の大きさと幅は厳密には比例しないので、少しだけ余裕をみる
        return max(size * minScale, size * maxWidth / w * 0.97f)
    }

    /** タップ位置の施設・ボタン（描画と別スレッドから呼んでよい） */
    fun hitAt(x: Float, y: Float): Hit? = publishedHits.firstOrNull { it.rect.contains(x, y) }
}
