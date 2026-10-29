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
 */
class PanelRenderer(var density: Float) {

    /** 0=大きく 3 件, 1=一覧, 2=SA/PA のみ */
    var displayMode: Int = 0
    /** 一覧にオービスも並べる */
    var showCamerasInList: Boolean = false
    /** 下部のボタン（スマホのみ。車載画面はアクションストリップを使う） */
    var showButtons: Boolean = true
    var overlayMessage: String? = null

    class Hit(val rect: RectF, val facility: FacilityView?, val button: Int)

    val hits = ArrayList<Hit>()

    private fun dp(v: Float) = v * density

    // ---- 色
    private val cBg = 0xFF0A0C10.toInt()
    private val cBar = 0xFF2B313C.toInt()
    private val cBarEdge = 0xFF3D4552.toInt()
    private val cEta = 0xFF000000.toInt()
    private val cEtaEdge = 0xFF9AA4B2.toInt()
    private val cText = Color.WHITE
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

    private class Banner(val text: String, val color: Int, val icon: String?)

    /**
     * @param nowMs 時計表示用の現在時刻
     */
    fun draw(c: Canvas, l0: Float, t0: Float, r0: Float, b0: Float, s: HighwaySnapshot?, nowMs: Long) {
        c.drawColor(cBg)
        hits.clear()
        val pad = dp(8f)
        val left = l0 + pad
        val right = r0 - pad
        val top = t0 + pad
        val bottom = b0 - pad
        if (right - left < dp(100f) || bottom - top < dp(90f)) return
        val landscape = (right - left) > (bottom - top) * 1.15f

        val headerH = if (landscape) ((bottom - top) * 0.12f).coerceIn(dp(40f), dp(64f))
        else ((bottom - top) * 0.115f).coerceIn(dp(56f), dp(96f))
        drawHeader(c, s, left, top, right, top + headerH, showSubLine = !landscape, nowMs = nowMs)

        val buttonsH = if (showButtons) dp(50f) else 0f
        val btnTop = bottom - buttonsH
        if (showButtons) drawButtons(c, left, btnTop, right, bottom)
        val mainBottom = if (showButtons) btnTop - pad else bottom

        if (landscape) {
            val sideW = if (right - left > dp(560f)) 0.30f else 0.34f
            val split = left + (right - left) * sideW
            drawSidePanel(c, s, left, top + headerH + pad, split - pad, mainBottom)
            drawMainArea(c, s, split, top + headerH + pad, right, mainBottom)
        } else {
            val statusH = dp(52f)
            drawStatusRow(c, s, left, mainBottom - statusH, right, mainBottom)
            drawMainArea(c, s, left, top + headerH + pad, right, mainBottom - statusH - pad)
        }
        overlayMessage?.let { drawOverlay(c, it, left, top, right, bottom) }
    }

    private fun drawHeader(c: Canvas, s: HighwaySnapshot?, l: Float, t: Float, r: Float, b: Float, showSubLine: Boolean, nowMs: Long) {
        val h = b - t
        // 時計
        val clock = Formats.clock(nowMs)
        text.textSize = if (showSubLine) h * 0.46f else h * 0.62f
        val cw = text.measureText(clock) + h * 0.4f
        rect.set(r - cw, t, r, t + h * 0.78f)
        fill.color = 0xFF1A1E25.toInt()
        c.drawRoundRect(rect, h * 0.12f, h * 0.12f, fill)
        text.color = cText
        drawTextCentered(c, clock, rect.centerX(), rect.centerY(), text, Paint.Align.CENTER)

        var x = l
        val maxR = r - cw - dp(8f)
        val onHw = s?.status == EngineStatus.ON_HIGHWAY || s?.status == EngineStatus.REST_STOP
        val general = onHw && s?.generalRoad == true
        // 一般国道は「国道23号」→ 青地に「23」（おにぎり標識風）
        val ref = (if (onHw) s?.roadRef else null)?.let { if (general && it.startsWith("国道")) it.removePrefix("国道").removeSuffix("号") else it }
        val line1Y = if (showSubLine) t + h * 0.30f else t + h * 0.40f
        if (ref != null) {
            text.textSize = if (showSubLine) h * 0.30f else h * 0.40f
            val maxBadge = (maxR - l) * 0.3f
            if (text.measureText(ref) + h * 0.3f > maxBadge) text.textSize = fitSize(text, ref, maxBadge - h * 0.3f, text.textSize)
            val bw = text.measureText(ref) + h * 0.3f
            val bh = if (showSubLine) h * 0.22f else h * 0.32f
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
            textNormal.textSize = if (showSubLine) h * 0.2f else h * 0.28f
            val label = "一般道"
            val w = textNormal.measureText(label) + h * 0.2f
            if (maxR - x > w + h * 2.0f) {
                generalTagShown = true
                val bh = if (showSubLine) h * 0.15f else h * 0.22f
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
        val titleSize = if (showSubLine) h * 0.36f else h * 0.48f
        if (maxR - x > titleSize * 1.5f) {
            text.textSize = fitSize(text, title, maxR - x, titleSize)
            drawTextCentered(c, ellipsize(text, title, maxR - x), x, line1Y, text, Paint.Align.LEFT)
        }

        val sub = buildString {
            if (general && !generalTagShown && showSubLine) append("一般道（信号・歩行者に注意）　")
            if (onHw && showSubLine) {
                s?.directionText?.let { append(it) }
                s?.nextRoadText?.let { if (isNotEmpty()) append("　"); append(it) }
            }
            val dm = s?.dataMessage
            if (dm != null) {
                if (isNotEmpty()) append("　")
                append(dm)
            }
        }
        if (sub.isNotEmpty() && showSubLine) {
            textNormal.textSize = fitSize(textNormal, sub, maxR - l, h * 0.22f)
            textNormal.color = if (s?.dataMessage != null && !onHw) cAmber else cSub
            drawTextCentered(c, sub, l, t + h * 0.78f, textNormal, Paint.Align.LEFT)
        }
    }

    /** 上部に出す注意（オービス接近・ETC カード・給油・通行止め） */
    private fun banners(s: HighwaySnapshot): List<Banner> {
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
        return out
    }

    private fun drawMainArea(c: Canvas, s: HighwaySnapshot?, l: Float, t0: Float, r: Float, b: Float) {
        if (s == null || (s.status != EngineStatus.ON_HIGHWAY && s.status != EngineStatus.REST_STOP)) {
            drawStatusMessage(c, s, l, t0, r, b)
            return
        }
        // ---- 注意の帯
        var t = t0
        val bannerH = dp(30f)
        for (bn in banners(s).take(2)) {
            rect.set(l, t, r, t + bannerH)
            fill.color = bn.color
            c.drawRoundRect(rect, dp(5f), dp(5f), fill)
            var tx = l + dp(10f)
            if (bn.icon == "camera") {
                drawCameraIcon(c, tx + bannerH * 0.35f, t + bannerH / 2, bannerH * 0.36f)
                tx += bannerH * 0.8f
            }
            text.color = cText
            text.textSize = fitSize(text, bn.text, r - tx - dp(8f), bannerH * 0.55f)
            drawTextCentered(c, bn.text, tx, t + bannerH / 2, text, Paint.Align.LEFT)
            t += bannerH + dp(4f)
        }

        val markerH = dp(40f)
        val rowsBottom = b - markerH
        val stripX = l + dp(16f)
        val rowLeft = l + dp(38f)

        val base = when (displayMode) {
            2 -> s.facilities.filter { it.type.isRestArea }
            else -> s.facilities
        }
        val list = if (showCamerasInList && displayMode != 2 && s.cameras.isNotEmpty()) (base + s.cameras).sortedBy { it.distanceM } else base
        val areaH = rowsBottom - t
        val rowH: Float
        val n: Int
        when (displayMode) {
            1 -> {
                rowH = dp(68f)
                n = max(1, (areaH / rowH).toInt())
            }
            2 -> {
                n = 4
                rowH = min(areaH / n, dp(150f))
            }
            else -> {
                // 大きく表示: 通常 3 件、縦に余裕があれば 4 件
                n = if (areaH / 4 >= dp(135f)) 4 else 3
                rowH = min(areaH / n, dp(155f))
            }
        }
        val shown = list.take(n)

        // ---- 道路の帯（下=現在地）
        val carY = b - markerH / 2
        val stripW = dp(10f)
        val topY = if (shown.isEmpty()) t else rowsBottom - (shown.size - 1) * rowH - rowH * 0.69f
        fill.color = cStrip
        rect.set(stripX - stripW / 2, topY - dp(12f), stripX + stripW / 2, carY)
        c.drawRect(rect, fill)
        var prevY = carY
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
        for (i in shown.indices) {
            val rb = rowsBottom - i * rowH
            drawRow(c, shown[i], rowLeft, rb - rowH, r, rb, compact = displayMode == 1)
        }
        if (shown.isEmpty()) {
            textNormal.textSize = dp(16f)
            textNormal.color = cSub
            val msg = if (s.routeTruncated) "前方の地図データを取得中…" else "前方に施設がありません"
            drawTextCentered(c, msg, (l + r) / 2, (t + rowsBottom) / 2, textNormal, Paint.Align.CENTER)
        }

        // ---- 自車マーク
        val mr = markerH * 0.42f
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

        text.textSize = dp(16f)
        text.color = cText
        val label = buildString {
            if (s.status == EngineStatus.REST_STOP) append(s.directionText ?: "停車中")
            else append(s.roadName ?: "")
            if (s.deadReckoning) append("　（推測航法）")
        }
        text.textSize = fitSize(text, label, r - rowLeft - dp(8f), dp(16f))
        drawTextCentered(c, label, rowLeft + dp(4f), carY, text, Paint.Align.LEFT)
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

    private fun drawRow(c: Canvas, f: FacilityView, l: Float, t: Float, r: Float, b: Float, compact: Boolean) {
        val h = b - t
        val nameTop = t + h * (if (compact) 0.04f else 0.06f)
        val nameBottom = t + h * 0.56f
        val nameH = nameBottom - nameTop
        val isCam = f.type == FacilityType.CAMERA
        // 名称バー
        rect.set(l, nameTop, r, nameBottom)
        fill.color = if (isCam) 0xFF3A2224.toInt() else cBar
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
        fill.color = when (f.type) {
            FacilityType.TB -> cBlue
            FacilityType.CAMERA -> cRed
            else -> cGreen
        }
        c.drawRoundRect(rect, dp(3f), dp(3f), fill)
        text.color = cText
        text.textSize = fitSize(text, badge, bw - bs * 0.15f, bs * 0.5f)
        drawTextCentered(c, badge, rect.centerX(), rect.centerY(), text, Paint.Align.CENTER)

        // 距離（右寄せ、数値大・単位小）
        val (num, unit) = Formats.distanceParts(f.distanceM)
        val numSize = nameH * 0.66f
        val unitSize = nameH * 0.40f
        text.textSize = unitSize
        val uw = text.measureText(unit)
        text.textSize = numSize
        val nw = text.measureText(num)
        val dx = r - nameH * 0.2f
        text.textSize = unitSize
        drawTextBaseline(c, unit, dx - uw, nameBottom - nameH * 0.2f, text)
        text.textSize = numSize
        drawTextBaseline(c, num, dx - uw - nw - dp(2f), nameBottom - nameH * 0.2f, text)

        // 名称
        val nameX = bx + bw + nameH * 0.18f
        val nameMax = dx - uw - nw - dp(12f) - nameX
        val nm = if (isCam) (f.name + if (f.userDefined) "（登録）" else "") else f.name.ifEmpty { f.displayName }
        text.textSize = fitSize(text, nm, nameMax, nameH * 0.56f)
        drawTextCentered(c, nm, nameX, (nameTop + nameBottom) / 2, text, Paint.Align.LEFT)

        // 到着予定時刻バー（左端を斜めに）
        val etaTop = nameBottom - dp(1f)
        val etaBottom = t + h * 0.95f
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
        stroke.color = cEtaEdge
        stroke.strokeWidth = dp(1.2f)
        c.drawPath(path, stroke)

        val etaText = when {
            isCam -> f.maxspeedKmh?.let { "制限 ${it}km/h" } ?: "速度取締機"
            f.closedBefore -> "通行止"
            else -> Formats.clock(f.etaEpochMs)
        }
        text.textSize = min(etaH * (if (isCam) 0.5f else 0.66f), dp(36f))
        text.color = if (f.closedBefore && !isCam) cRed else cText
        val etaX = el + slant + etaH * 0.25f
        drawTextCentered(c, etaText, etaX, (etaTop + etaBottom) / 2, text, Paint.Align.LEFT)
        text.color = cText
        var ix = etaX + text.measureText(etaText) + etaH * 0.4f
        val iconSize = min(etaH * 0.78f, dp(34f))
        val iy = etaTop + (etaH - iconSize) / 2

        // 交通情報のタグ（手前の区間の渋滞・事故など）
        val tags = ArrayList<Pair<String, Int>>()
        if (!isCam) {
            if (f.jamBeforeM >= 200) tags.add("渋滞${Formats.distance(f.jamBeforeM)}" to cRed)
            f.trafficNote?.let { if (!f.closedBefore || it != "通行止") tags.add(it to cOrange) }
            if (f.etcOnly) tags.add("ETC専用" to cPurple)
        }
        for ((label, color) in tags) {
            text.textSize = iconSize * 0.48f
            val w = text.measureText(label) + iconSize * 0.4f
            if (ix + w >= r - dp(4f)) break
            rect.set(ix, iy, ix + w, iy + iconSize)
            fill.color = color
            c.drawRoundRect(rect, dp(3f), dp(3f), fill)
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
                        chips.add("${Formats.distance(jd)}先 渋滞${Formats.distance(jl)}$delay" to cRed)
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

    private fun drawSidePanel(c: Canvas, s: HighwaySnapshot?, l: Float, t: Float, r: Float, b: Float) {
        val h = b - t
        s?.dataMessage?.let { dm ->
            textNormal.color = cAmber
            textNormal.textSize = fitSize(textNormal, dm, r - l, dp(13f))
            drawTextCentered(c, dm, l, b - dp(8f), textNormal, Paint.Align.LEFT)
        }
        // 車速（大）と制限速度、状態チップ
        val statusH = min(h * 0.22f, dp(70f))
        drawStatusRow(c, s, l, t, r, t + statusH)
        var y = t + statusH + dp(14f)
        if (s != null && (s.status == EngineStatus.ON_HIGHWAY || s.status == EngineStatus.REST_STOP)) {
            textNormal.color = cSub
            s.directionText?.let {
                textNormal.textSize = fitSize(textNormal, it, r - l, dp(15f))
                drawTextCentered(c, it, l, y, textNormal, Paint.Align.LEFT)
                y += dp(24f)
            }
            s.nextRoadText?.let {
                textNormal.textSize = fitSize(textNormal, it, r - l, dp(15f))
                drawTextCentered(c, it, l, y, textNormal, Paint.Align.LEFT)
                y += dp(24f)
            }
            // 渋滞情報（横画面では状態チップが入りきらないので文字で）
            val tr = s.traffic
            val jd = tr?.nextJamDistanceM
            val jl = tr?.nextJamLengthM
            val trafficLine = when {
                jd != null && jl != null && jd < 40_000 -> {
                    val delay = tr.nextJamDelaySec?.let { if (it >= 60) " +${(it / 60).roundToInt()}分" else "" } ?: ""
                    "${Formats.distance(jd)}先 渋滞${Formats.distance(jl)}$delay"
                }
                tr?.message != null -> tr.message
                else -> null
            }
            if (trafficLine != null) {
                textNormal.color = if (jd != null) cRed else cAmber
                textNormal.textSize = fitSize(textNormal, trafficLine, r - l, dp(15f))
                drawTextCentered(c, trafficLine, l, y, textNormal, Paint.Align.LEFT)
                y += dp(24f)
            }
            // 航続可能距離（車両接続時）
            s.vehicle?.rangeRemainingM?.let { rng ->
                textNormal.color = cSub
                val label = String.format(Locale.JAPAN, "航続可能 %.0fkm", rng / 1000)
                textNormal.textSize = fitSize(textNormal, label, r - l, dp(15f))
                drawTextCentered(c, label, l, y, textNormal, Paint.Align.LEFT)
                y += dp(24f)
            }
            // 方位（コンパス風）
            val hd = s.headingDeg
            if (hd != null && b - y > dp(90f)) {
                val rad = min((b - y) * 0.4f, (r - l) * 0.25f)
                val cx = l + rad + dp(4f)
                val cy = y + rad + dp(8f)
                stroke.color = cBarEdge
                stroke.strokeWidth = dp(2f)
                c.drawCircle(cx, cy, rad, stroke)
                textNormal.textSize = rad * 0.3f
                textNormal.color = cSub
                drawTextCentered(c, "N", cx, cy - rad * 0.72f, textNormal, Paint.Align.CENTER)
                c.save()
                c.rotate(hd.toFloat(), cx, cy)
                path.reset()
                path.moveTo(cx, cy - rad * 0.85f)
                path.lineTo(cx + rad * 0.25f, cy + rad * 0.3f)
                path.lineTo(cx, cy + rad * 0.12f)
                path.lineTo(cx - rad * 0.25f, cy + rad * 0.3f)
                path.close()
                fill.color = 0xFF1E88E5.toInt()
                c.drawPath(path, fill)
                c.restore()
                text.textSize = rad * 0.4f
                text.color = cText
                drawTextCentered(c, "${hd.roundToInt()}°", cx + rad + dp(12f), cy, text, Paint.Align.LEFT)
            }
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

    /** 収まらなければ末尾を「…」にする */
    private fun ellipsize(p: Paint, s: String, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var n = s.length
        while (n > 1 && p.measureText(s.substring(0, n) + "…") > maxWidth) n--
        return s.substring(0, n) + "…"
    }

    /** maxWidth に収まる文字サイズ（最大 size、最小 size×0.5） */
    private fun fitSize(p: Paint, s: String, maxWidth: Float, size: Float): Float {
        if (maxWidth <= 0) return size * 0.5f
        p.textSize = size
        val w = p.measureText(s)
        if (w <= maxWidth) return size
        return max(size * 0.5f, size * maxWidth / w)
    }

    /** タップ位置の施設・ボタン */
    fun hitAt(x: Float, y: Float): Hit? = hits.firstOrNull { it.rect.contains(x, y) }
}
