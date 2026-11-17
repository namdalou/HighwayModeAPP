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
 *
 * v2.19: 更新・入れ直しのあとも使えるように、許可したこと（使いたいこと）を設定に覚えておき（[KEY_DUMP_WANTED]。
 * 設定の書き出し・自動バックアップにも入る）、DUMP 権限が外れていたら起動時に取り直す（[restoreIfNeeded]）。
 * Shizuku の許可が残っていれば（同じアプリの更新）黙って取り直し、残っていなければ（入れ直し・機種変更）利用者に案内する。
 */
object ShizukuHelper {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4810
    private const val DUMP = "android.permission.DUMP"

    /** 設定のファイル（スペアナの設定と同じ。書き出し・バックアップの対象） */
    private const val PREFS = "spectrum"
    /** 音声の読み取り（DUMP 権限）を使う（一度許可した）。入れ直したあとに取り直すかどうかに使う */
    const val KEY_DUMP_WANTED = "dump_wanted"
    /** 「許可が外れた」の案内を出したアプリの版とインストールの日時（同じ版では 1 回だけ。端末ごとの情報なので書き出さない） */
    const val KEY_DUMP_NOTICE = "dump_notice_shown_for"

    private val main = Handler(Looper.getMainLooper())

    /** 取り直しの最中（二重に始めない） */
    @Volatile
    private var restoring = false

    /** 音声の読み取りを使う設定か（一度許可した） */
    fun isWanted(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DUMP_WANTED, false)

    fun setWanted(ctx: Context, on: Boolean) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_DUMP_WANTED, false) != on) p.edit().putBoolean(KEY_DUMP_WANTED, on).apply()
    }

    /**
     * 起動時（スマホの画面・車の画面）に呼ぶ。DUMP 権限が外れていたら取り直す。
     *  - 許可されている → 覚えておく（[KEY_DUMP_WANTED]）だけ
     *  - Shizuku が動いていて、このアプリの Shizuku の利用が許可されたまま → 黙って取り直す（更新のあと）
     *  - それ以外で、以前に許可していた → [onNeedUser]（利用者に案内する。入れ直し・機種変更・Shizuku が止まっている）
     * Shizuku の接続（binder）は起動から少し遅れて届くので、0.5 秒・3 秒・8 秒後に確かめる。
     */
    fun restoreIfNeeded(ctx: Context, onNeedUser: (() -> Unit)? = null) {
        val c = ctx.applicationContext
        if (SessionScanner.hasPermission(c)) {
            setWanted(c, true)
            return
        }
        if (!isInstalled(c)) {
            if (isWanted(c)) main.post { onNeedUser?.invoke() }
            return
        }
        if (restoring) return
        restoring = true
        val delays = longArrayOf(500L, 2500L, 5000L)
        var tries = 0
        val attempt = object : Runnable {
            override fun run() {
                if (SessionScanner.hasPermission(c)) {
                    restoring = false
                    setWanted(c, true)
                    return
                }
                val allowed = isRunning() && try {
                    !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
                if (allowed) {
                    // pm grant は別スレッドで動き、結果もそのスレッドで返る。案内（ダイアログ）はメインスレッドで出す
                    runGrant(c) { ok, _ ->
                        main.post {
                            restoring = false
                            if (ok) setWanted(c, true) else if (isWanted(c)) onNeedUser?.invoke()
                        }
                    }
                    return
                }
                tries++
                if (tries < delays.size) {
                    main.postDelayed(this, delays[tries])
                    return
                }
                restoring = false
                if (isWanted(c)) onNeedUser?.invoke()
            }
        }
        main.postDelayed(attempt, delays[0])
    }

    /**
     * 「許可が外れた」の案内を、この版（と、このインストール）でまだ出していなければ true（出したことにする）。
     * インストールした日時も含めるのは、自動バックアップで戻った設定に、前の端末で出した記録が残っていても、もう一度出すため
     */
    fun takeNotice(ctx: Context, versionCode: Int): Boolean {
        val c = ctx.applicationContext
        val installed = try {
            c.packageManager.getPackageInfo(c.packageName, 0).firstInstallTime
        } catch (_: Exception) {
            0L
        }
        val key = "$versionCode/$installed"
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_DUMP_NOTICE, null) == key) return false
        p.edit().putString(KEY_DUMP_NOTICE, key).apply()
        return true
    }

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
            setWanted(c, true)
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
                done(false, "Shizuku で「Spectrum ハイウェイ」が拒否されています。Shizuku アプリの「承認済みのアプリ」で許可してください")
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
            if (granted) setWanted(c, true)
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
