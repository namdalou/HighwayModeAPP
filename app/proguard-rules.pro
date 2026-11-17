# リリースビルドの R8（縮小・難読化・最適化）の規則。
# Car App Library（Android Auto）・Google Play のアプリ内レビュー／アップデートの規則は、ライブラリ側に同梱されているため追加不要

# Spectrum: Shizuku.newProcess は非公開のためリフレクションで呼び出している（名前を変えない・消さない）
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }

# v2.20: 公開版では、動作の記録（ログ）のうち詳細・情報の記録を出さない（警告・エラーは残す。問い合わせのときに使う）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# クラッシュの記録（Play Console）の行番号を読めるようにする（ファイル名は隠す）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
