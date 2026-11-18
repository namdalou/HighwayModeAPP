package com.example.highwaymode

import android.app.Application

/**
 * アプリの起動時の処理（v2.21）。スマホの画面・Android Auto・常駐サービスのどれから起動しても最初に通る。
 * ここでは軽い準備だけをする（重い処理を置くと、どの画面の起動も遅くなる）:
 *  - 落ちたときの記録（[CrashLog]。端末の中だけ）
 */
class HighwayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
