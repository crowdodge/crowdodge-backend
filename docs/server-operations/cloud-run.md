# Cloud Run

## 更新対象

- Cloud Run Service / Job、起動契機、スケジュール、並行実行数、実行サービスアカウント、環境変数を変更した場合に更新する。

## リソース

| リソース | 種別 | 役割 | 起動契機 |
|---|---|---|---|
| API service | Cloud Run Service | OpenAPIで定義するHTTP APIと外部Webhookの提供 | HTTPリクエスト |
| Google Calendar watch renewal | Cloud Run Job | 選択済みGoogle Calendarと同期状態を整合し、watchを更新する | Cloud Scheduler、毎日 03:00 JST |
| Notification dispatch | Cloud Run Job | 到来した通知を送信する | Cloud Scheduler、5分ごと |
| Schema migration | 1回実行 | API serviceのデプロイ前にDBスキーマを更新する | デプロイパイプライン |

## 環境変数

| 対象リソース | 区分 | 変数 |
|---|---|---|
| API service / Google Calendar watch renewal | DB接続 | `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`、`DB_SSL_MODE`、`DB_PGBOUNCER` |
| API service / Google Calendar watch renewal | Google Calendar | `GOOGLE_CALENDAR_API_BASE_URL`、`GOOGLE_CALENDAR_WEBHOOK_URL`、`GOOGLE_CALENDAR_CHANNEL_TOKEN`、`GOOGLE_CALENDAR_FULL_SYNC_WINDOW_DAYS` |
| API service / Google Calendar watch renewal | Google OAuth | `GOOGLE_OAUTH_TOKEN_URL`、`GOOGLE_OAUTH_JWKS_URL`、`GOOGLE_OAUTH_CLIENT_ID`、`GOOGLE_OAUTH_CLIENT_SECRET` |
| API service / Google Calendar watch renewal | Google資格情報暗号化 | `GOOGLE_TOKEN_ENCRYPTION_KEY` |
| API service / Google Calendar watch renewal | アプリJWT | `APP_JWT_SECRET`、`APP_JWT_ISSUER`、`APP_JWT_AUDIENCE`、`APP_JWT_ACCESS_TTL_SECONDS`、`APP_JWT_REFRESH_TTL_SECONDS` |
| Notification dispatch | DB接続 | `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`、`DB_SSL_MODE`、`DB_PGBOUNCER` |
| Notification dispatch | Gemini | `GEMINI_API_BASE_URL`、`GEMINI_API_KEY`、`GEMINI_MAX_CONCURRENCY` |
| Schema migration | migration DB接続 | `MIGRATION_DB_HOST`、`MIGRATION_DB_PORT`、`MIGRATION_DB_NAME`、`MIGRATION_DB_USER`、`MIGRATION_DB_PASSWORD`、`MIGRATION_DB_SSL_MODE` |

## API service

- 実行サービスアカウントには、DB接続情報を取得する権限とGoogle OAuth・Google Calendar APIを利用する権限を付与する。
- 外部Webhookを受けるため、Google Calendar watchの通知先として到達可能でなければならない。

## Google Calendar watch renewal

- 同一実行内でカレンダー単位の失敗があっても、他のカレンダーの整合を継続する。
- 設定不備、DB接続不能、選択一覧取得不能など、Job全体を開始できない場合は失敗として終了する。
- Jobの実行サービスアカウントには、Cloud SQL、Secret Manager、Google Calendar APIを利用する権限を付与する。
- Schedulerのサービスアカウントには、このJobの実行権限だけを付与する。

## Notification dispatch

- 1回の実行内で複数タスクを並行させない。task数とparallelismはともに1とする。
- 1回のtask内では、混雑生成を`GEMINI_MAX_CONCURRENCY`以下の並行数で実行する。
- 個別のFCM送信失敗があっても、dispatch処理とDB保存を完了できた場合はJobとして成功とする。
- 混雑アラートで混雑情報を一時的に取得できない場合は、通知を`pending`へ戻し、Job全体の失敗にはしない。
- リマインダーで混雑情報を取得できない場合は、混雑情報なしで送信する。
- 混雑アラートで混雑情報を恒久的に取得できない場合、または混雑なしの場合は通知を`canceled`にする。
- 起動不能、DB障害、Port契約違反、検証済み生成結果のドメイン不変条件違反など、dispatch処理を完了できない場合はJobとして失敗する。
- Job失敗時は、確保済み通知を`pending`へ戻す復旧処理を行ってから終了コード1で終了する。復旧処理自体の失敗は元の障害へ付加して記録する。
- FCM送信はDBトランザクション外で行うため、送信後にJobが失敗した場合は同じ通知を再送する可能性がある。
- Jobの実行サービスアカウントには、Cloud SQL、Secret Manager、Firebase Cloud Messagingを利用する権限を付与する。
- Schedulerのサービスアカウントには、このJobの実行権限だけを付与する。

## Schema migration

- API serviceのデプロイ前に、同じイメージを1回実行してFlyway migrationを適用する。
- migrationはアプリケーション起動時には実行しない。
- migrationを実行する主体には、migration用DB接続情報を取得する権限とDBへ接続する権限を付与する。
