# crowdodge-backend

混雑回避パーソナライズ型予定管理アプリ **crowdodge**（チーム「なせばなる」）のバックエンド。

- 設計方針: [`docs/architecture.md`](docs/architecture.md)（DDD + モジュラーモノリス）
- 技術スタック: Kotlin (JDK 21) / Ktor / Gradle (Kotlin DSL) / PostgreSQL + PostGIS / Exposed (R2DBC) / Flyway / Koin / Arrow

## リポジトリ構成

```
crowdodge-backend/        # リポジトリルート（docker-compose / deploy 等はここに配置）
├── docs/                 # 設計ドキュメント（architecture.md ほか）
└── server/               # Gradle プロジェクト本体（基盤・§14 step1）
    ├── app/              # Ktor 起動・全モジュール配線・HTTP サーバ・マイグレーション(Flyway)
    ├── shared/
    │   ├── kernel/       # 共通 VO / DomainEvent 基底 / TransactionRunner ポート
    │   └── infra/        # R2DBC 基盤 / EventBus / Problem(RFC9457)
    └── contexts/         # 各 BC（user / event / destination / congestion / notification）※後続追加
```

## 必要環境

- JDK 21（toolchain で自動選択。各依存は `gradle/libs.versions.toml` で一元管理）
- PostgreSQL + PostGIS（アプリ実行時。DB 接続情報は環境変数で上書き可）

## ビルド / 実行

Gradle プロジェクトは `server/` 配下にある。

```bash
cd server

# ビルド（kernel のユニットテストを含む）
./gradlew build

# マイグレーション（アプリ起動とは独立した専用コマンド。デプロイ前 / init container で実行）
./gradlew :app:flywayMigrate

# アプリ起動（要 PostgreSQL。アプリ起動ではマイグレーションを実行しない）
./gradlew :app:run
```

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

```
GET /health  ->  200 {"status":"UP","service":"crowdodge-backend"}
```

## ロードマップ

実装は `docs/architecture.md` §14 に従う。本リポジトリは **step1（基盤）** 時点。
