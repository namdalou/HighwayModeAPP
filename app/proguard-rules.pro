# Car App Library（Android Auto）のルールはライブラリ側に同梱されているため追加不要

# Spectrum: Shizuku.newProcess は非公開のためリフレクションで呼び出している（名前を変えない・消さない）
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
