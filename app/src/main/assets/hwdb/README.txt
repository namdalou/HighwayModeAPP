このフォルダに全国の地図データ japan-highways.hwdb を置いてビルドすると、アプリに同梱されます
（無圧縮で格納され、端末にコピーせず APK から直接読み込みます）。

作り方:
  ・GitHub Actions: .github/workflows/build-data.yml（毎週自動作成・データ同梱 APK も作成）
  ・PC: scripts/build-hwdb.sh（osmium-tool と JDK 17 が必要）
  ・PC（osmium なし）: ./gradlew :datatool:run --args="overpass --regions all --out app/src/main/assets/hwdb/japan-highways.hwdb"
