# 通知送信ジョブ

## 更新対象

- 通知送信 Job の実行方式、エントリポイント、環境変数を変更した場合に更新する。

## 概要

期限到来した `notification_schedules` の `pending` 行を FCM でスマートフォンへ送信する 1 回実行の Job。
Cloud Scheduler が 5 分間隔で Cloud Run Job を起動する。

## エントリポイント

- Main: `app/src/main/kotlin/com/crowdodge/app/notification/NotificationDispatchMain.kt`
- DI module: `app/src/main/kotlin/com/crowdodge/app/notification/NotificationDispatchModule.kt`
- Gradle: `./gradlew :app:dispatchNotifications`
- Dockerfile: `app/src/main/docker/notification-dispatch.Dockerfile`
- exit code: 成功 0（送信失敗があっても Job としては 0）/ 起動・DB 障害 1

## 必要な設定

- DB 接続: `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` / `DB_SSL_MODE` / `DB_PGBOUNCER`（application.conf の `crowdodge.database`）
- FCM: Application Default Credentials（Cloud Run はサービスアカウント、ローカルは `GOOGLE_APPLICATION_CREDENTIALS`）
- Cloud Run の実行サービスアカウントには、FCM 送信に必要な Firebase Admin SDK / Google API 呼び出し権限を付与する。
- 専用 module のため、Google OAuth / Google Calendar webhook / channel token / JWT / token encryption の設定は不要。

## 通知内容

- タイトル: 予定タイトル（なければ「予定のお知らせ」）
- 本文: 予定日時（終日予定は「MM/DD 終日」、JST）+ 混雑情報（あれば）
- `CongestionAlert` は混雑情報が取得できない間は送信せず `canceled` にする。
