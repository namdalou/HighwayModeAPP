package com.example.highwaymode.data

import android.content.Context

/**
 * この端末・このインストールだけの状態（v2.20）。
 * 自動バックアップ（res/xml の規則）にも設定の書き出しにも入れない（入れ直したとき・機種変更のときは最初から）。
 */
class LocalState(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("local_state", Context.MODE_PRIVATE)

    /**
     * 最初の説明（このアプリが使う情報と権限）に同意した版。0 = まだ。
     * 説明を変えたら [com.example.highwaymode.MainActivity] の DISCLOSURE_VERSION を上げる（もう一度出す）
     */
    var disclosureVersion: Int
        get() = sp.getInt("disclosure_version", 0)
        set(v) = sp.edit().putInt("disclosure_version", v).apply()

    /** Google Play のアプリ内アップデートで、更新を勧めた時刻（1 日に 1 回まで） */
    var updateAskedMs: Long
        get() = sp.getLong("update_asked_ms", 0L)
        set(v) = sp.edit().putLong("update_asked_ms", v).apply()
}
