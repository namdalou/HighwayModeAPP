package com.example.spectrum

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Shizuku を使って、パソコンなしで DUMP 権限(音声の番号の読み取り用)を自分に許可する。
 *
 * 流れ: Shizuku が起動しているか確認 → Spectrum に Shizuku の利用を許可してもらう
 *       → Shizuku 経由で「pm grant <自分> android.permission.DUMP」を実行
 * 一度許可すれば、Shizuku を止めても・再起動しても DUMP 権限は残る(アンインストールで消える)。
 */
object ShizukuHelper {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4810
    private const val DUMP = "android.permission.DUMP"

    private val main = Handler(Looper.getMainLooper())

    /** Shizuku アプリが入っているか */
    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Shizuku が起動していて使える状態か */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /**
     * DUMP 権限の許可を試みる。結果は (成功したか, 利用者向けのメッセージ) でメインスレッドに返す。
     */
    fun grantDump(ctx: Context, onResult: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        val done: (Boolean, String) -> Unit = { ok, msg -> main.post { onResult(ok, msg) } }

        if (SessionScanner.hasPermission(c)) {
            done(true, "すでに許可されています")
            return
        }
        if (!isInstalled(c)) {
            done(false, "Shizuku がインストールされていません")
            return
        }
        if (!isRunning()) {
            done(false, "Shizuku が起動していません。Shizuku アプリを開いて「開始」してください")
            return
        }
        try {
            if (Shizuku.isPreV11()) {
                done(false, "この Shizuku は古いため使えません。Shizuku を更新してください")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                runGrant(c, done)
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                done(false, "Shizuku で Spectrum が「拒否」されています。Shizuku アプリの「承認済みのアプリ」で Spectrum を許可してください")
                return
            }
            // Spectrum に Shizuku の利用を許可してもらう(Shizuku の確認ダイアログが出る)
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        runGrant(c, done)
                    } else {
                        done(false, "Shizuku の利用が許可されませんでした")
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            done(false, "Shizuku との通信に失敗しました: ${e.javaClass.simpleName}")
        }
    }

    /** Shizuku 経由で pm grant を実行する(時間がかかるので別スレッドで) */
    private fun runGrant(c: Context, done: (Boolean, String) -> Unit) {
        Thread {
            val (code, err) = exec(arrayOf("pm", "grant", c.packageName, DUMP))
            val granted = SessionScanner.hasPermission(c)
            when {
                granted -> done(true, "音声の読み取りを許可しました")
                code == null -> done(false, "実行できませんでした: $err")
                else -> done(false, "許可に失敗しました(コード $code): ${err.take(200)}")
            }
        }.start()
    }

    /**
     * Shizuku の権限でコマンドを実行する。戻り値は (終了コード, エラー出力)。
     * Shizuku.newProcess は公開されていないため、リフレクションで呼び出す。
     */
    private fun exec(cmd: Array<String>): Pair<Int?, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, cmd, null, null) as Process
        val err = p.errorStream.bufferedReader().readText()
        p.inputStream.bufferedReader().readText()
        Pair(p.waitFor(), err)
    } catch (e: Throwable) {
        Pair(null, (e.cause ?: e).toString())
    }

    /** Shizuku の権限でコマンドを実行して、標準出力を返す(失敗したら null) */
    fun execOutput(cmd: Array<String>): String? = try {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            null
        } else {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, cmd, null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }
    } catch (_: Throwable) {
        null
    }

    /** Shizuku アプリを開く(入っていなければ Play ストアのページ) */
    fun openShizuku(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: Intent(
            Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (_: Exception) { }
    }
}
