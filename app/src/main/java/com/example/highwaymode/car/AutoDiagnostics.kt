package com.example.highwaymode.car

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * 「Android Auto に表示されない」ときの診断。
 *
 * Android Auto はアプリ一覧（ランチャーのカスタマイズを含む）に出す前に、アプリを検証する。
 * Android Auto 17.7 以降（2026 年秋〜）は、Car App Library のアプリ（このアプリのような
 * テンプレート型のナビアプリ）を「提供元不明のアプリ」の設定では通さなくなり、
 * Google Play からインストールしたもの（内部テスト配布を含む）だけを表示する。
 * マニフェストが正しくても、APK を直接インストールした場合は表示されない。
 *
 * ここでは端末側で分かること（インストール元・Android Auto の版・宣言の有無）を調べて、
 * どの対処が必要かを表示する。
 */
object AutoDiagnostics {

    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private const val PLAY_STORE = "com.android.vending"
    private const val CAR_APP_SERVICE_ACTION = "androidx.car.app.CarAppService"
    private const val CAR_APP_META = "com.google.android.gms.car.application"

    class Report(
        val packageName: String,
        val appVersion: String,
        val installer: String?,
        val initiator: String?,
        val fromPlay: Boolean,
        val autoVersion: String?,
        val autoVersionCode: Long,
        val carServices: Int,
        val hasCarMeta: Boolean,
        val android: String
    ) {
        /** Android Auto 17.7 以降か（版名の先頭の数字で判定） */
        val autoIsNewValidator: Boolean
            get() {
                val v = autoVersion ?: return false
                val parts = v.split('.', ' ', '-')
                val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
                val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                return major > 17 || (major == 17 && minor >= 7)
            }

        fun asText(): String = buildString {
            append("アプリ: ").append(packageName).append(" ").append(appVersion).append('\n')
            append("インストール元: ").append(installerLabel(installer, initiator)).append('\n')
            append("Android Auto: ").append(autoVersion ?: "見つかりません").append('\n')
            append("Car App サービス: ").append(if (carServices > 0) "宣言あり" else "見つかりません")
            append(" / 車載アプリ宣言: ").append(if (hasCarMeta) "あり" else "なし").append('\n')
            append("Android: ").append(android)
        }
    }

    fun collect(ctx: Context): Report {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val appVersion = try {
            pm.getPackageInfo(pkg, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        var installer: String? = null
        var initiator: String? = null
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val src = pm.getInstallSourceInfo(pkg)
                installer = src.installingPackageName
                initiator = src.initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                installer = pm.getInstallerPackageName(pkg)
            }
        } catch (e: Exception) {
            // 取得できない端末もある
        }
        var autoVersion: String? = null
        var autoCode = 0L
        try {
            val pi = pm.getPackageInfo(ANDROID_AUTO_PACKAGE, 0)
            autoVersion = pi.versionName
            autoCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // Android Auto 未インストール（または見えない）
        } catch (e: Exception) {
        }
        val services = try {
            pm.queryIntentServices(Intent(CAR_APP_SERVICE_ACTION).setPackage(pkg), 0).size
        } catch (e: Exception) {
            0
        }
        val hasMeta = try {
            pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData?.containsKey(CAR_APP_META) == true
        } catch (e: Exception) {
            false
        }
        return Report(
            packageName = pkg,
            appVersion = appVersion,
            installer = installer,
            initiator = initiator,
            fromPlay = installer == PLAY_STORE,
            autoVersion = autoVersion,
            autoVersionCode = autoCode,
            carServices = services,
            hasCarMeta = hasMeta,
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    fun installerLabel(installer: String?, initiator: String?): String = when (installer) {
        PLAY_STORE -> "Google Play"
        null -> "APK を直接インストール（adb など）"
        "com.google.android.packageinstaller", "com.android.packageinstaller" -> "APK ファイルから（パッケージインストーラ）"
        else -> installer
    } + if (initiator != null && initiator != installer && installer != PLAY_STORE) "（$initiator 経由）" else ""

    /** 診断結果に応じた説明 */
    fun advice(r: Report): String = buildString {
        when {
            r.autoVersion == null -> {
                append("Android Auto アプリが見つかりません。Google Play で「Android Auto」をインストール・更新してください。")
            }
            r.carServices == 0 || !r.hasCarMeta -> {
                append("このビルドには Android Auto 用の宣言が含まれていません。正しくビルドされていない可能性があります（同梱の README の手順で作り直してください）。")
            }
            r.fromPlay -> {
                append("Google Play からインストールされているので、Android Auto の一覧に出せる状態です。\n\n")
                append("表示されないときは:\n")
                append("・スマホの Android Auto の設定 →「ランチャーのカスタマイズ」で「ハイウェイモード」をオンにする\n")
                append("・一度 Android Auto の接続を切ってつなぎ直す（車・ケーブル・ワイヤレス）\n")
                append("・Android Auto と Google Play 開発者サービスを最新にする")
            }
            else -> {
                append("Google Play 以外からインストールされています。")
                if (r.autoIsNewValidator) append("この Android Auto（${r.autoVersion}）は")
                else append("Android Auto 17.7 以降は")
                append("、Google Play 以外から入れたナビ型（Car App Library）のアプリを、「提供元不明のアプリ」をオンにしても一覧に出しません（Android Auto 側の仕様で、アプリの作りでは回避できません）。\n\n")
                append("対処（どちらか）:\n")
                append("① Google Play Console の「内部テスト」で自分あてに配布し、Play ストアからインストールし直す（審査なし・数分で配信。README の手順を参照）\n")
                append("② パソコンの Desktop Head Unit（Google 公式の Android Auto エミュレーター）で動作確認する\n\n")
                append("①の場合は、いま入っているこのアプリを先にアンインストールしてください。")
            }
        }
    }

    /**
     * Play 以外から入れていて、Android Auto が新しい検証をする版のときだけ、最初に一度案内する。
     * （インストール元が変わったら、また一度だけ出す）
     */
    fun maybeShowOnce(a: Activity, prefs: com.example.highwaymode.data.Prefs) {
        val r = try {
            collect(a)
        } catch (e: Exception) {
            return
        }
        if (r.fromPlay || r.autoVersion == null || !r.autoIsNewValidator) return
        val key = "${r.installer}/${r.appVersion}"
        if (prefs.autoHintShownFor == key) return
        prefs.autoHintShownFor = key
        show(a)
    }

    fun show(a: Activity) {
        val r = collect(a)
        val msg = r.asText() + "\n\n" + advice(r)
        val b = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Android Auto の状態")
            .setMessage(msg)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("情報をコピー") { _, _ ->
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Android Auto の状態", msg))
                Toast.makeText(a, "コピーしました", Toast.LENGTH_SHORT).show()
            }
        if (r.autoVersion == null) {
            b.setNegativeButton("Play ストアで開く") { _, _ ->
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ANDROID_AUTO_PACKAGE")))
                } catch (e: Exception) {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$ANDROID_AUTO_PACKAGE")))
                }
            }
        }
        b.show()
    }
}
