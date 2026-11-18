package com.example.highwaymode

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 不具合の記録（v2.21。端末の中だけに保存し、開発者向け設定で見る・コピーする）。
 *
 * 内部アプリ共有で使うので、落ちたときの記録が Google Play（Android Vitals）に届かないことがある。
 * 車の画面で「予期せぬエラーが発生したため終了します」と出たときなどに、何が起きたかをあとから確かめられるようにする。
 *  - アプリが落ちた（どこでも受け止めなかった例外）: 時刻・版・端末・スレッド・例外の内容を files/crash_last.txt に書いてから、
 *    いつもどおり Android に任せる（落ちること自体は変えない）
 *  - 落ちずに済んだ失敗（車の画面の描画・画面を作る処理・押したときの処理など）: 最近の [MAX_NOTES] 件を覚えておき、
 *    落ちたときの記録に添える（開発者向け設定でも見られる）
 * 外部には送らない。バックアップ・設定の書き出しにも入れない（backup_rules・data_extraction_rules に含めていない）。
 */
object CrashLog {

    private const val FILE = "crash_last.txt"
    private const val MAX_NOTES = 30
    /** 同じ失敗が続くとき、覚え直すまでの間隔（毎フレームの失敗で記録を埋めないように） */
    private const val SAME_NOTE_MS = 10_000L
    private const val MAX_STACK_CHARS = 12_000

    @Volatile private var installed = false
    @Volatile private var dir: File? = null
    @Volatile private var version = ""
    private val notes = ArrayDeque<String>()
    private var lastKey = ""
    private var lastAt = 0L

    /** 落ちたときの記録を始める（何度呼んでもよい。アプリの起動時） */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        dir = ctx.applicationContext.filesDir
        version = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write(t, e)
            } catch (_: Throwable) {
                // 記録できなくても、落ちる処理は続ける
            }
            if (prev != null) {
                prev.uncaughtException(t, e)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** 落ちずに済んだ失敗を覚える（どのスレッドからでも。同じ失敗は 10 秒に 1 回まで） */
    fun note(where: String, e: Throwable?) {
        val msg = if (e == null) "" else "${e.javaClass.simpleName}: ${e.message?.take(160) ?: ""}" + (e.stackTrace.firstOrNull()?.let { " @ $it" } ?: "")
        val key = "$where|$msg"
        val now = System.currentTimeMillis()
        synchronized(notes) {
            if (key == lastKey && now - lastAt < SAME_NOTE_MS) return
            lastKey = key
            lastAt = now
            notes.addLast("${stamp(now)} $where${if (msg.isEmpty()) "" else "  $msg"}")
            while (notes.size > MAX_NOTES) notes.removeFirst()
        }
    }

    /** 最近の失敗（新しい順） */
    fun recentNotes(): List<String> = synchronized(notes) { notes.toList().asReversed() }

    /** 最後に落ちたときの記録（無ければ null） */
    fun lastCrash(ctx: Context): String? = try {
        File(ctx.filesDir, FILE).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        null
    }

    /** 記録を消す（落ちたときの記録と、覚えている失敗） */
    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, FILE).delete()
        } catch (_: Exception) {
        }
        synchronized(notes) {
            notes.clear()
            lastKey = ""
        }
    }

    /** 開発者向け設定に出す文（落ちたときの記録＋最近の失敗） */
    fun report(ctx: Context): String = buildString {
        val crash = lastCrash(ctx)
        append(crash ?: "落ちたときの記録はありません。\n")
        val recent = recentNotes()
        append("\n落ちずに済んだ失敗（このアプリを起動してから。新しい順）:\n")
        if (recent.isEmpty()) append("（なし）\n") else recent.forEach { append("・").append(it).append('\n') }
    }

    private fun write(t: Thread, e: Throwable) {
        val d = dir ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().let { if (it.length > MAX_STACK_CHARS) it.take(MAX_STACK_CHARS) + "\n…（省略）" else it }
        val text = buildString {
            append("落ちた時刻: ").append(stamp(System.currentTimeMillis())).append('\n')
            append("アプリ: ").append(version).append('\n')
            append("端末: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("・Android ").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
            append("スレッド: ").append(t.name).append("\n\n")
            append(stack)
            val recent = recentNotes()
            if (recent.isNotEmpty()) {
                append("\n直前の失敗（新しい順）:\n")
                recent.forEach { append("・").append(it).append('\n') }
            }
        }
        File(d, FILE).writeText(text)
    }

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date(ms))
}
