package com.example.highwaymode.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.highwaymode.core.facility.Amenity

/**
 * SA/PA 設備のピクトグラム（カーナビ風の色付き四角アイコン）を Canvas で描く。
 * UI スレッド専用。
 */
object AmenityIcons {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()
    private val r = RectF()

    fun color(a: Amenity): Int = when (a) {
        Amenity.GAS -> 0xFFD32F2F.toInt()
        Amenity.EV -> 0xFF00897B.toInt()
        Amenity.RESTAURANT -> 0xFFE65100.toInt()
        Amenity.SNACK -> 0xFFF9A825.toInt()
        Amenity.CONVENIENCE -> 0xFF1565C0.toInt()
        Amenity.SHOP -> 0xFF6A1B9A.toInt()
        Amenity.TOILET -> 0xFF2E7D32.toInt()
        Amenity.INFO -> 0xFF2E7D32.toInt()
        Amenity.SHOWER -> 0xFF0277BD.toInt()
        Amenity.HOTEL -> 0xFF283593.toInt()
        Amenity.DOG_RUN -> 0xFF6D4C41.toInt()
        Amenity.ATM -> 0xFF546E7A.toInt()
    }

    fun draw(c: Canvas, a: Amenity, x: Float, y: Float, size: Float) {
        bg.color = color(a)
        r.set(x, y, x + size, y + size)
        c.drawRoundRect(r, size * 0.12f, size * 0.12f, bg)
        stroke.strokeWidth = size * 0.075f
        stroke.color = Color.WHITE
        fill.color = Color.WHITE
        fun px(u: Float) = x + u * size
        fun py(v: Float) = y + v * size

        when (a) {
            Amenity.GAS -> {
                // 給油機本体・窓・ノズル
                r.set(px(0.22f), py(0.18f), px(0.60f), py(0.86f))
                c.drawRoundRect(r, size * 0.05f, size * 0.05f, fill)
                fill.color = bg.color
                r.set(px(0.29f), py(0.27f), px(0.53f), py(0.45f))
                c.drawRect(r, fill)
                fill.color = Color.WHITE
                path.reset()
                path.moveTo(px(0.60f), py(0.36f))
                path.lineTo(px(0.74f), py(0.46f))
                path.lineTo(px(0.74f), py(0.72f))
                path.lineTo(px(0.66f), py(0.78f))
                c.drawPath(path, stroke)
            }
            Amenity.EV -> {
                // 稲妻
                path.reset()
                path.moveTo(px(0.58f), py(0.10f))
                path.lineTo(px(0.26f), py(0.56f))
                path.lineTo(px(0.47f), py(0.56f))
                path.lineTo(px(0.40f), py(0.90f))
                path.lineTo(px(0.74f), py(0.42f))
                path.lineTo(px(0.53f), py(0.42f))
                path.close()
                c.drawPath(path, fill)
            }
            Amenity.RESTAURANT -> {
                // フォーク
                c.drawLine(px(0.34f), py(0.18f), px(0.34f), py(0.86f), stroke)
                c.drawLine(px(0.24f), py(0.18f), px(0.24f), py(0.38f), stroke)
                c.drawLine(px(0.44f), py(0.18f), px(0.44f), py(0.38f), stroke)
                c.drawLine(px(0.24f), py(0.40f), px(0.44f), py(0.40f), stroke)
                // ナイフ
                path.reset()
                path.moveTo(px(0.66f), py(0.16f))
                path.quadTo(px(0.80f), py(0.30f), px(0.72f), py(0.52f))
                path.lineTo(px(0.66f), py(0.52f))
                path.close()
                c.drawPath(path, fill)
                c.drawLine(px(0.68f), py(0.50f), px(0.68f), py(0.86f), stroke)
            }
            Amenity.SNACK -> {
                // カップとストロー
                path.reset()
                path.moveTo(px(0.26f), py(0.34f))
                path.lineTo(px(0.74f), py(0.34f))
                path.lineTo(px(0.66f), py(0.86f))
                path.lineTo(px(0.34f), py(0.86f))
                path.close()
                c.drawPath(path, fill)
                c.drawLine(px(0.54f), py(0.34f), px(0.64f), py(0.12f), stroke)
            }
            Amenity.CONVENIENCE -> {
                // 店舗（ひさし・ドア）
                r.set(px(0.14f), py(0.20f), px(0.86f), py(0.36f))
                c.drawRect(r, fill)
                r.set(px(0.20f), py(0.36f), px(0.80f), py(0.86f))
                c.drawRect(r, stroke)
                r.set(px(0.42f), py(0.56f), px(0.58f), py(0.86f))
                c.drawRect(r, fill)
            }
            Amenity.SHOP -> {
                // 買い物袋
                r.set(px(0.24f), py(0.38f), px(0.76f), py(0.86f))
                c.drawRoundRect(r, size * 0.04f, size * 0.04f, fill)
                r.set(px(0.36f), py(0.18f), px(0.64f), py(0.56f))
                c.drawArc(r, 180f, 180f, false, stroke)
            }
            Amenity.TOILET -> {
                // 男性（青）・女性（赤）
                fill.color = 0xFF64B5F6.toInt()
                c.drawCircle(px(0.32f), py(0.22f), size * 0.08f, fill)
                r.set(px(0.25f), py(0.34f), px(0.39f), py(0.86f))
                c.drawRoundRect(r, size * 0.03f, size * 0.03f, fill)
                fill.color = 0xFFF48FB1.toInt()
                c.drawCircle(px(0.68f), py(0.22f), size * 0.08f, fill)
                path.reset()
                path.moveTo(px(0.68f), py(0.33f))
                path.lineTo(px(0.80f), py(0.68f))
                path.lineTo(px(0.56f), py(0.68f))
                path.close()
                c.drawPath(path, fill)
                r.set(px(0.62f), py(0.66f), px(0.74f), py(0.86f))
                c.drawRect(r, fill)
                fill.color = Color.WHITE
            }
            Amenity.INFO -> {
                c.drawCircle(px(0.5f), py(0.24f), size * 0.085f, fill)
                r.set(px(0.43f), py(0.38f), px(0.57f), py(0.84f))
                c.drawRect(r, fill)
                r.set(px(0.36f), py(0.38f), px(0.57f), py(0.46f))
                c.drawRect(r, fill)
                r.set(px(0.36f), py(0.78f), px(0.64f), py(0.86f))
                c.drawRect(r, fill)
            }
            Amenity.SHOWER -> {
                r.set(px(0.28f), py(0.18f), px(0.72f), py(0.30f))
                c.drawRoundRect(r, size * 0.05f, size * 0.05f, fill)
                val d = size * 0.055f
                c.drawCircle(px(0.36f), py(0.46f), d, fill)
                c.drawCircle(px(0.50f), py(0.50f), d, fill)
                c.drawCircle(px(0.64f), py(0.46f), d, fill)
                c.drawCircle(px(0.42f), py(0.68f), d, fill)
                c.drawCircle(px(0.58f), py(0.70f), d, fill)
            }
            Amenity.HOTEL -> {
                // ベッド
                c.drawLine(px(0.16f), py(0.30f), px(0.16f), py(0.84f), stroke)
                r.set(px(0.16f), py(0.54f), px(0.86f), py(0.68f))
                c.drawRect(r, fill)
                c.drawLine(px(0.84f), py(0.66f), px(0.84f), py(0.84f), stroke)
                c.drawCircle(px(0.30f), py(0.46f), size * 0.07f, fill)
                r.set(px(0.42f), py(0.40f), px(0.84f), py(0.54f))
                c.drawRoundRect(r, size * 0.05f, size * 0.05f, fill)
            }
            Amenity.DOG_RUN -> {
                c.drawCircle(px(0.50f), py(0.64f), size * 0.16f, fill)
                val t = size * 0.075f
                c.drawCircle(px(0.28f), py(0.44f), t, fill)
                c.drawCircle(px(0.42f), py(0.28f), t, fill)
                c.drawCircle(px(0.58f), py(0.28f), t, fill)
                c.drawCircle(px(0.72f), py(0.44f), t, fill)
            }
            Amenity.ATM -> {
                text.textSize = size * 0.62f
                c.drawText("¥", px(0.5f), py(0.74f), text)
            }
        }
    }
}
