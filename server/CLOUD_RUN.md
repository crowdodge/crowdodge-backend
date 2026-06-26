# Cloud Run デプロイ手順

このサーバは Cloud Run の `PORT` 環境変数で待ち受けポートを決めます。Supabase 接続情報は Secret Manager などから環境変数として渡してください。

## 必須環境変数

- `DB_HOST`: アプリ通常起動用。Supabase の shared transaction-mode pooler の host。
- `DB_PORT`: アプリ通常起動用。transaction-mode pooler は通常 `6543`。
- `DB_NAME`: アプリ通常起動用。Supabase の既定 DB 名は通常 `postgres`。
- `DB_USER`: アプリ通常起動用。
- `DB_PASSWORD`: アプリ通常起動用。
- `DB_SSL_MODE`: アプリ通常起動用。Supabase では通常 `require`。
- `DB_PGBOUNCER`: アプリ通常起動用。transaction-mode pooler を使う場合は `true`。
- `MIGRATION_DB_HOST`: Flyway 用。Supabase の shared session-mode pooler の host。
- `MIGRATION_DB_PORT`: Flyway 用。session-mode pooler は通常 `5432`。
- `MIGRATION_DB_NAME`: Flyway 用。
- `MIGRATION_DB_USER`: Flyway 用。
- `MIGRATION_DB_PASSWORD`: Flyway 用。
- `MIGRATION_DB_SSL_MODE`: Flyway 用。Supabase では通常 `require`。

Supabase では通常 SSL が必要です。`DB_SSL_MODE=require` / `MIGRATION_DB_SSL_MODE=require` を指定してください。

例:

```sh
DB_HOST="aws-1-ap-northeast-1.pooler.supabase.com"
DB_PORT="6543"
DB_NAME="postgres"
DB_USER="postgres.xxxxx"
DB_PASSWORD="password"
DB_SSL_MODE="require"
DB_PGBOUNCER="true"

MIGRATION_DB_HOST="aws-1-ap-northeast-1.pooler.supabase.com"
MIGRATION_DB_PORT="5432"
MIGRATION_DB_NAME="postgres"
MIGRATION_DB_USER="postgres.xxxxx"
MIGRATION_DB_PASSWORD="password"
MIGRATION_DB_SSL_MODE="require"
```

未指定時の既定値は通常用・マイグレーション用ともに `localhost:5432/crowdodge` です。

## コンテナビルド

```sh
docker build -t crowdodge-backend .
```

## マイグレーション

アプリ起動時には Flyway を実行しません。Cloud Run デプロイ前に、同じイメージでマイグレーション用 main を実行してください。

```sh
java -cp "/app/lib/*" com.crowdodge.app.migration.MigrateMainKt
```

Cloud Run Jobs を使う場合も、同じ `MIGRATION_DB_*` を渡して上記コマンドを実行します。

## Cloud Run

ヘルスチェックには `/health`、DB 到達性を含めた readiness には `/ready` を使えます。
