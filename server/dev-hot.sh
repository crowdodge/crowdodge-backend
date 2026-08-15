#!/bin/bash

# watch and serve
# watch（gradle --continuousによる継続的ビルド・監視）: ソースコードの変更を検知してGradleがコンパイル（.class）し直す
# serve（Ktor Auto-reloadによるサーバのDevelopment Mode起動）: .classファイルの変更を監視してサーバを起動し直す（厳密にはクラスローダがクラスを再ロードするため、プロセスの再起動はなし）

set -e

# コンテナ内での実行のため、以下の処理で環境変数を読み込む
set -a
source ../.env
set +a

CLEANED=0
SHUTDOWN_REQUEST=0

echo "Creating cache..."
./gradlew --no-daemon classes # これがないとバックグラウンドとフォアグラウンドで衝突する可能性がある

# shellcheck disable=SC2329
cleanup() {
  [ "$CLEANED" -eq 1 ] && return
  CLEANED=1
  echo "Shutting down..."

  kill "$GRADLE_WATCH_PID" 2>/dev/null || true
  kill "$GRADLE_SV_PID" 2>/dev/null || true

  wait "$GRADLE_WATCH_PID" 2>/dev/null || true
  wait "$GRADLE_SV_PID" 2>/dev/null || true

  echo "Shutdown complete."
}
trap cleanup EXIT

on_shutdown_signal() {
  SHUTDOWN_REQUEST=1
}
trap on_shutdown_signal INT TERM

echo "Starting source code watcher..."
# ./gradlew --continuous classes --info &
./gradlew --no-daemon --continuous --info build -x test -x detekt & # -tオプションで終了させずに、バックグラウンドで動作させる
GRADLE_WATCH_PID=$!

echo "Starting Ktor erver..."
# NOTE: マルチモジュールの場合:ep_module:をrunにつける。
./gradlew --no-daemon :app:run &
GRADLE_SV_PID=$!

wait -n "$GRADLE_WATCH_PID" "$GRADLE_SV_PID"

if [ "$SHUTDOWN_REQUEST" -eq 1 ]; then
  echo "Shutdown request."
  exit 0
else
  echo "One of the processes has exited."
  exit 1
fi
