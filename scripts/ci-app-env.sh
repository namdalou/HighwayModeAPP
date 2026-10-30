#!/usr/bin/env bash
# GitHub Actions 用: アプリ ID・版番号・アップロード鍵を用意して $GITHUB_ENV に書く。
#   入力（環境変数）: UPLOAD_KEYSTORE_BASE64（任意）, APP_ID_VAR（任意）, OWNER（リポジトリ所有者）
#   出力: APP_ID, VERSION_CODE, SIGNED=true|false, UPLOAD_KEYSTORE_FILE（鍵があるとき）
set -euo pipefail

if [ -n "${UPLOAD_KEYSTORE_BASE64:-}" ]; then
  KS="$RUNNER_TEMP/upload.jks"
  printf '%s' "$UPLOAD_KEYSTORE_BASE64" | base64 -d > "$KS"
  echo "UPLOAD_KEYSTORE_FILE=$KS" >> "$GITHUB_ENV"
  echo "SIGNED=true" >> "$GITHUB_ENV"
  echo "アップロード鍵で署名します"
else
  echo "SIGNED=false" >> "$GITHUB_ENV"
  echo "::notice::アップロード鍵（UPLOAD_KEYSTORE_BASE64）が未登録のため、デバッグ鍵の APK だけを作ります（Google Play には上げられません）"
fi

if [ -n "${APP_ID_VAR:-}" ]; then
  APP_ID="$APP_ID_VAR"
else
  # 所有者名をパッケージ名に使える形へ（小文字・英数字と _ だけ・英字で始める）
  O=$(printf '%s' "${OWNER:-user}" | tr 'A-Z' 'a-z' | sed -e 's/[^a-z0-9]/_/g' -e 's/^\([0-9_]\)/u\1/')
  APP_ID="io.github.$O.highwaymode"
fi
if ! printf '%s' "$APP_ID" | grep -Eq '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'; then
  echo "::error::アプリ ID が不正です: $APP_ID（例: io.github.yourname.highwaymode）"
  exit 1
fi
case "$APP_ID" in
  com.example.*) echo "::error::Google Play は com.example で始まるアプリ ID を受け付けません: $APP_ID"; exit 1 ;;
esac

# 版番号: 2026-01-01 からの経過分（どのワークフローから作っても必ず増える）
VERSION_CODE=$(( ($(date -u +%s) - 1767225600) / 60 ))

echo "APP_ID=$APP_ID" >> "$GITHUB_ENV"
echo "VERSION_CODE=$VERSION_CODE" >> "$GITHUB_ENV"
echo "アプリ ID: $APP_ID / 版番号: $VERSION_CODE"
