# Google Calendar Watch Renewal Job

Google Calendar の選択状態と Event BC の同期状態を 1 日 1 回整合する Cloud Run Job。
通常の Cloud Run service や migration job とは別にデプロイする。

## Runtime

- Entry point: `com.crowdodge.app.calendar.GoogleCalendarWatchRenewalMainKt`
- Gradle task: `./gradlew :app:renewGoogleCalendarWatches`
- Dockerfile: `app/src/main/docker/google-calendar-watch-renewal.Dockerfile`
- Calendar 単位の失敗はログに記録し、Job は exit code `0` で終了する。
- 設定不備、DB 接続不能、選択一覧取得不能など Job 全体を開始できない失敗は exit code 非 `0` で終了する。

## Environment

既存の Cloud Run service と同じ DB / Google / JWT 設定を渡す。

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`
- `DB_SSL_MODE`
- `DB_PGBOUNCER`
- `GOOGLE_CALENDAR_API_BASE_URL`
- `GOOGLE_CALENDAR_WEBHOOK_URL`
- `GOOGLE_CALENDAR_CHANNEL_TOKEN`
- `GOOGLE_CALENDAR_FULL_SYNC_WINDOW_DAYS`
- `GOOGLE_OAUTH_TOKEN_URL`
- `GOOGLE_OAUTH_JWKS_URL`
- `GOOGLE_OAUTH_CLIENT_ID`
- `GOOGLE_OAUTH_CLIENT_SECRET`
- `GOOGLE_TOKEN_ENCRYPTION_KEY`
- `APP_JWT_SECRET`
- `APP_JWT_ISSUER`
- `APP_JWT_AUDIENCE`
- `APP_JWT_ACCESS_TTL_SECONDS`
- `APP_JWT_REFRESH_TTL_SECONDS`

## Deploy

```bash
gcloud run jobs deploy google-calendar-watch-renewal \
  --project="$PROJECT_ID" \
  --region="$REGION" \
  --image="$IMAGE" \
  --service-account="$JOB_SERVICE_ACCOUNT"
```

`$JOB_SERVICE_ACCOUNT` には Cloud SQL / Secret Manager / Google API など、既存 Cloud Run service と同等の実行時権限を付与する。

## Scheduler

Scheduler service account には対象 Job の実行権限だけを付与する。

```bash
gcloud run jobs add-iam-policy-binding google-calendar-watch-renewal \
  --project="$PROJECT_ID" \
  --region="$REGION" \
  --member="serviceAccount:${SCHEDULER_SERVICE_ACCOUNT}" \
  --role="roles/run.invoker"

gcloud scheduler jobs create http google-calendar-watch-renewal-daily \
  --project="$PROJECT_ID" \
  --location="$REGION" \
  --schedule="0 3 * * *" \
  --time-zone="Asia/Tokyo" \
  --uri="https://run.googleapis.com/v2/projects/${PROJECT_ID}/locations/${REGION}/jobs/google-calendar-watch-renewal:run" \
  --http-method=POST \
  --oauth-service-account-email="$SCHEDULER_SERVICE_ACCOUNT"
```
