# crowdodge-backend

混雑回避パーソナライズ型予定管理アプリ **crowdodge**（チーム「なせばなる」）のバックエンド。

- 技術仕様: [`docs/README.md`](docs/README.md)（DDD + モジュラーモノリス）
- 技術スタック: Kotlin (JDK 21) / Ktor / Gradle (Kotlin DSL) / PostgreSQL + PostGIS / Exposed (R2DBC) / Flyway / Koin / Arrow

## リポジトリ構成

```
crowdodge-backend/        # リポジトリルート（compose.yml / Taskfile 等はここに配置）
├── docs/                 # 技術仕様ドキュメント
└── server/               # Gradle プロジェクト本体
    ├── app/              # Ktor 起動・全モジュール配線・HTTP サーバ・マイグレーション(Flyway)
    ├── shared/
    │   ├── kernel/       # 共通 VO / DomainEvent / TransactionRunner ポート
    │   └── infra/        # R2DBC 基盤 / DB readiness probe / Problem(RFC9457) / Domain Event配送
    └── contexts/         # 各 BC（user / event / destination / congestion / notification）
```

## 必要環境

- JDK 21（toolchain で自動選択。各依存は `gradle/libs.versions.toml` で一元管理）
- Docker（開発用DBの Docker Compose v2 と Testcontainers で使用）
- PostgreSQL + PostGIS（アプリ実行時。開発用DBは `compose.yml` で起動可。DB 接続情報は環境変数で上書き可）

## ビルド / 実行

Gradle プロジェクトは `server/` 配下にある。

```bash
cd server

# ビルド（全モジュール）
./gradlew build

# マイグレーション（アプリ起動とは独立した専用コマンド。デプロイ前 / init container で実行）
./gradlew :app:flywayMigrate

# アプリ起動（要 PostgreSQL。アプリ起動ではマイグレーションを実行しない）
./gradlew :app:run
```

### 開発用 DB（Docker Compose）

ローカル開発用の PostgreSQL + PostGIS をリポジトリルートの `compose.yml` で起動できる（要 **Docker Compose v2**。`docker-compose` v1 は非対応）。アプリ本体はホストの Gradle で起動し、DB だけをコンテナ化する（テストは Testcontainers が自前で起動）。

```bash
# DB 起動（リポジトリルートで）
docker compose up -d db

# マイグレーション適用 → アプリ起動（server/ で）
cd server
./gradlew :app:flywayMigrate
./gradlew :app:run
```

- 接続情報は `.env`（`.env.example` をコピーして作成）または環境変数で上書きできる。既定はすべて `crowdodge`。
- **注意**: `.env` は compose が読み込んで DB コンテナに注入する。**ホスト起動するアプリ（`:app:run`）は `.env` を自動では読まない**ため、既定値から変えた場合はアプリ側にも渡すこと（例: `set -a; source .env; set +a`）。
- リセット（baseline 変更時など Flyway チェックサム不一致の解消）: `docker compose down -v` で volume を破棄してから再起動・再マイグレーションする。

### Lint / 整形（detekt）

静的解析と整形は detekt（`detekt-formatting` で ktlint ルールを内包）に一元化している。
リポジトリルートで go-task から実行できる（素の Gradle でも可）。

```bash
task detekt   # 静的解析＋整形チェック（= cd server && ./gradlew detekt）
task format   # 整形を自動修正（= ./gradlew detekt --auto-correct）
```

レポートは SARIF のみ出力し、各モジュール分を `server/build/reports/detekt/merged.sarif` に統合する。

### アプリ設定

`server/app/src/main/resources/application.conf` の既定値を環境変数で上書きできる。

| 変数 | 用途 | 既定値 |
|---|---|---|
| `PORT` | HTTP ポート | `8080` |
| `DB_HOST` | DB ホスト | `localhost` |
| `DB_PORT` | DB ポート | `5432` |
| `DB_NAME` | DB 名 | `crowdodge` |
| `DB_USER` / `DB_PASSWORD` | DB 認証情報（URL に埋め込まず分離して注入） | `crowdodge` / `crowdodge` |
| `DB_SSL_MODE` | アプリ DB 接続の SSL mode | `disable` |
| `DB_PGBOUNCER` | PgBouncer transaction pooler 向け設定 | `false` |
| `MIGRATION_DB_HOST` | Flyway 用 DB ホスト | `localhost` |
| `MIGRATION_DB_PORT` | Flyway 用 DB ポート | `5432` |
| `MIGRATION_DB_NAME` | Flyway 用 DB 名 | `crowdodge` |
| `MIGRATION_DB_USER` / `MIGRATION_DB_PASSWORD` | Flyway 用 DB 認証情報 | `crowdodge` / `crowdodge` |
| `MIGRATION_DB_SSL_MODE` | Flyway 用 DB 接続の SSL mode | `disable` |
| `GOOGLE_CALENDAR_API_BASE_URL` | Google Calendar API base URL | `https://www.googleapis.com` |
| `GOOGLE_CALENDAR_FULL_SYNC_WINDOW_DAYS` | Google Calendar の同期対象日数 | `90` |
| `GOOGLE_OAUTH_TOKEN_URL` | Google OAuth token endpoint | `https://oauth2.googleapis.com/token` |
| `GOOGLE_OAUTH_JWKS_URL` | Google JWKS endpoint | `https://www.googleapis.com/oauth2/v3/certs` |
| `GOOGLE_OAUTH_CLIENT_ID` | Google OAuth client ID | 空 |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Google OAuth client secret | 空 |
| `GOOGLE_TOKEN_ENCRYPTION_KEY` | Google access token / refresh token 暗号化キー | 空 |
| `APP_JWT_SECRET` | アプリ access token 署名 secret | 空 |
| `APP_JWT_ISSUER` | アプリ access token issuer | `crowdodge-api` |
| `APP_JWT_AUDIENCE` | アプリ access token audience | `crowdodge-app` |
| `APP_JWT_ACCESS_TTL_SECONDS` | アプリ access token TTL 秒 | `900` |
| `APP_JWT_REFRESH_TTL_SECONDS` | アプリ refresh token TTL 秒 | `2592000` |

## ヘルスチェック

liveness と readiness を分離している。

```
GET /health  ->  200 {"status":"UP","service":"crowdodge-backend"}   # liveness（プロセス生存のみ。DB 非依存）
GET /ready    ->  200 {"status":"READY"}                              # readiness（DB 到達OK）
             ->  503 {"status":"NOT_READY"}                           # readiness（DB 不通／タイムアウト）
```

`/ready` は R2DBC で `SELECT 1` を実行して DB 到達性を確認する（R2DBC は遅延接続のため、ここで初めて実接続を張る）。

## ロードマップ

実装順序と現行の実装状況は [`docs/operations/roadmap.md`](docs/operations/roadmap.md) に従う。
