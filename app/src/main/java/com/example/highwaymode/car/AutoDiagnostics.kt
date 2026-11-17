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
 * Google Play からインストールしたもの（テスト版の配布を含む）だけを表示する。
 * マニフェストが正しくても、APK を直接インストールした場合は表示されない。
 *
 * ここでは端末側で分かること（インストール元・Android Auto の版・宣言の有無）を調べて、
 * どの対処が必要かを表示する。
 *  - [maybeShowOnce]: Google Play 以外から入れていて Android Auto が新しい版のとき、起動時に一度だけ、利用者向けの短い案内を出す
 *  - [show]: 開発者向け設定の「Android Auto の状態」。調べた内容（インストール元・版など）と対処を出す
 * v2.19: 公開に向けて、案内から開発向けの内容（内部テスト・README・Desktop Head Unit・ビルド）を除いた。
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
                append("このアプリの Android Auto 用の情報を読み込めませんでした。Google Play からアプリを入れ直してください。")
            }
            r.fromPlay -> {
                append("Google Play からインストールされているので、Android Auto で使えます。\n\n")
                append("車の画面に出ないときは:\n")
                append("・スマホの Android Auto の設定 →「ランチャーのカスタマイズ」で「Spectrum ハイウェイ」をオンにする\n")
                append("・一度 Android Auto の接続を切ってつなぎ直す（車・ケーブル・ワイヤレス）\n")
                append("・Android Auto と Google Play 開発者サービスを最新にする")
            }
            else -> append(notFromPlayAdvice(r))
        }
    }

    /** Google Play 以外から入れたときの案内（利用者向け） */
    private fun notFromPlayAdvice(r: Report): String = buildString {
        append("このアプリは Google Play 以外からインストールされているため、")
        append(if (r.autoIsNewValidator) "この Android Auto（${r.autoVersion}）" else "Android Auto 17.7 以降")
        append("では車の画面に表示されません（Android Auto の仕様です）。スマホの画面ではこのまま使えます。\n\n")
        append("車の画面で使うには、いまのアプリをアンインストールしてから、Google Play ストアからインストールしてください。")
        append("アンインストールの前に、設定 →「設定の引き継ぎ」→「設定を書き出す」で保存しておくと、入れ直したあとに「設定を読み込む」で戻せます。")
    }

    /**
     * Play 以外から入れていて、Android Auto が新しい検証をする版のときだけ、最初に一度案内する（利用者向けの短い案内）。
     * （インストール元・アプリの版が変わったら、また一度だけ出す）
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
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Android Auto で使うには")
            .setMessage(notFromPlayAdvice(r))
            .setPositiveButton("閉じる", null)
            .setNeutralButton("Google Play を開く") { _, _ -> openStorePage(a, a.packageName) }
            .show()
    }

    /** Google Play のアプリのページを開く */
    private fun openStorePage(a: Activity, pkg: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: Exception) {
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (e2: Exception) {
                Toast.makeText(a, "Google Play を開けませんでした", Toast.LENGTH_SHORT).show()
            }
        }
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
            b.setNegativeButton("Play ストアで開く") { _, _ -> openStorePage(a, ANDROID_AUTO_PACKAGE) }
        }
        b.show()
    }
}
