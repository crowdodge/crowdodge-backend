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
- 送信失敗があっても、起動とDB処理を完了できた場合はJobとして成功とする。
- 起動不能またはDB障害はJobとして失敗とする。
- Jobの実行サービスアカウントには、Cloud SQLとSecret Managerを利用する権限を付与する。
- Schedulerのサービスアカウントには、このJobの実行権限だけを付与する。

## Schema migration

- API serviceのデプロイ前に、同じイメージを1回実行してFlyway migrationを適用する。
- migrationはアプリケーション起動時には実行しない。
- migrationを実行する主体には、migration用DB接続情報を取得する権限とDBへ接続する権限を付与する。
