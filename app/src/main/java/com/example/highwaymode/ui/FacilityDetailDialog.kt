package com.example.highwaymode.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.core.facility.Amenity
import com.example.highwaymode.core.facility.FacilityType
import java.util.Locale

/** 施設の説明文（スマホのダイアログと車載画面の詳細で共用） */
object FacilityText {

    /** (項目, 内容) の一覧 */
    fun lines(f: FacilityView): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("種別" to (f.type.label + if (f.typeInferred) "（道路形状から推定）" else ""))
        out.add("距離" to "${Formats.distance(f.distanceM)}（${Formats.duration(f.travelSec)}）")
        if (f.type == FacilityType.CAMERA) {
            f.maxspeedKmh?.let { out.add("取締速度" to "${it}km/h") }
            f.cameraType?.let { out.add("種類" to it) }
            out.add("情報元" to if (f.userDefined) "利用者の登録" else "OpenStreetMap")
            return out
        }
        if (f.noExit) out.add("出口" to "この方向には出口がありません（反対方向だけに出口のある IC）")
        out.add((if (f.noExit) "通過予定" else "到着予定") to if (f.closedBefore) "手前に通行止めの情報あり（参考 ${Formats.clock(f.etaEpochMs)}）" else Formats.clock(f.etaEpochMs))
        if (f.jamBeforeM >= 200) out.add("手前の渋滞" to Formats.distance(f.jamBeforeM))
        f.trafficNote?.let { out.add("交通情報" to it) }
        f.exitRef?.let { out.add("出口番号" to it) }
        f.destination?.let { out.add((if (f.type == FacilityType.JCT) "接続" else "方面") to it) }
        f.directionNote?.let { out.add("上下線" to it) }
        if (f.etcOnly) {
            out.add("ETC専用" to ("はい" + (f.etcReason?.let { "（$it）" } ?: "")))
        } else if (f.type.isExit) {
            out.add("ETC専用" to "情報なし（一般レーンあり、または未登録）")
        }
        if (f.type.isRestArea || f.amenities.isNotEmpty()) {
            val described = HashSet<Amenity>()
            val items = ArrayList<String>()
            for (p in f.pois) {
                items.add(p.describe())
                described.add(p.amenity)
            }
            for (a in f.amenities) if (a !in described) items.add(a.label)
            if (items.isNotEmpty()) out.add("設備" to items.joinToString("、"))
        }
        return out
    }
}

/** 施設をタップしたときの詳細表示 */
object FacilityDetailDialog {

    fun show(activity: Activity, f: FacilityView) {
        val sb = StringBuilder()
        for ((k, v) in FacilityText.lines(f)) {
            if (k == "設備") {
                sb.append("\n設備：\n")
                for (item in v.split("、")) sb.append("・").append(item).append('\n')
            } else sb.append(k).append("：").append(v).append('\n')
        }
        sb.append('\n')
        sb.append(String.format(Locale.US, "位置：%.5f, %.5f\n", f.lat, f.lon))
        if (f.type == FacilityType.CAMERA) {
            sb.append("\n※ 速度取締機の位置は OpenStreetMap・利用者の登録によるもので、すべてを網羅していません。制限速度を守って走行してください。")
        } else {
            sb.append("\n※ 施設・設備は OpenStreetMap の登録内容です。営業時間や工事規制などの最新情報は道路会社の案内をご確認ください。")
        }

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(f.displayName)
            .setMessage(sb.toString())
            .setPositiveButton("閉じる", null)
            .setNeutralButton("地図で開く") { _, _ ->
                val label = Uri.encode(f.displayName)
                val uri = Uri.parse("geo:${f.lat},${f.lon}?q=${f.lat},${f.lon}($label)")
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    // 地図アプリが無い
                }
            }
            .show()
    }
}
