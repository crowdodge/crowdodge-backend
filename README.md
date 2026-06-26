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
    │   ├── kernel/       # 共通 VO / DomainEvent 基底 / TransactionRunner ポート
    │   └── infra/        # R2DBC 基盤 / DB readiness probe / Problem(RFC9457)
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

### DB 接続設定

`server/app/src/main/resources/application.conf` の既定値を環境変数で上書きできる。

| 変数 | 用途 | 既定値 |
|---|---|---|
| `PORT` | HTTP ポート | `8080` |
| `DB_HOST` | DB ホスト | `localhost` |
| `DB_PORT` | DB ポート | `5432` |
| `DB_NAME` | DB 名 | `crowdodge` |
| `DB_USER` / `DB_PASSWORD` | DB 認証情報（URL に埋め込まず分離して注入） | `crowdodge` / `crowdodge` |

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
