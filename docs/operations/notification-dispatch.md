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

## Deploy

Job は通常の Cloud Run service とは別にデプロイする。1 回の実行で複数タスクが並行しないよう、task 数と parallelism はともに 1 にする。

```bash
gcloud run jobs deploy notification-dispatch \
  --project="$PROJECT_ID" \
  --region="$REGION" \
  --image="$IMAGE" \
  --service-account="$JOB_SERVICE_ACCOUNT" \
  --tasks=1 \
  --parallelism=1
```

`$JOB_SERVICE_ACCOUNT` には Cloud SQL / Secret Manager への実行時権限と、Firebase Cloud Messaging を送信できる権限を付与する。

## Scheduler

Scheduler 用サービスアカウントには、この Job の実行権限だけを付与する。5 分間隔で Cloud Run Job を起動する。

```bash
gcloud run jobs add-iam-policy-binding notification-dispatch \
  --project="$PROJECT_ID" \
  --region="$REGION" \
  --member="serviceAccount:${SCHEDULER_SERVICE_ACCOUNT}" \
  --role="roles/run.invoker"

gcloud scheduler jobs create http notification-dispatch-every-five-minutes \
  --project="$PROJECT_ID" \
  --location="$REGION" \
  --schedule="*/5 * * * *" \
  --time-zone="Asia/Tokyo" \
  --uri="https://run.googleapis.com/v2/projects/${PROJECT_ID}/locations/${REGION}/jobs/notification-dispatch:run" \
  --http-method=POST \
  --oauth-service-account-email="$SCHEDULER_SERVICE_ACCOUNT"
```

## 通知内容

- タイトル: 予定タイトル（なければ「予定のお知らせ」）
- 本文: 予定日時（終日予定は「MM/DD 終日」、JST）+ 混雑情報（あれば）
- `CongestionAlert` は混雑情報が取得できない間は送信せず `canceled` にする。
