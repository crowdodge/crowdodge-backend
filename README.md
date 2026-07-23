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

### 開発用 Dev Container

> [!WARNING]
> 破壊的変更。
> 「devcontainer方式」を導入したことによって、既存の「DBのみdockerでappはホストで起動する方式」の起動コマンドが変更された。
>
> docker composeは対象の設定ファイルを明示しない場合に、[`compose.yaml` > `compose.yml` > `docker-compose.yaml` > `docker-compose.yml`]の順で認識する。
> 「devcontainer方式」が`compose.yaml + compose.dev.yaml`で起動する形式のため、設定ファイルを省略すると追加した`compose.yaml`の方が既存の`compose.yml`より優先して読み込まれる。
> そのため既存の起動コマンドが壊れている。
> 対処法は[開発用 DB（Docker Compose）](#開発用-dbdocker-compose)を修正して明記している。

#### IntelliJ

devcontainerを利用して実行環境を隔離し、簡単に起動できる。
DBとAPP両方コンテナ化。

必要なものは

- Docker Desktop
- IntelliJ IDEA Ultimate
  - Dockerプラグイン
  - Dev Containerプラグイン

1. devcontainer.jsonを開き、左上のガターアイコンを押下
2. `Dev Containerを作成してソースをマウント`を選択

コンテナ内では

- 初起動時はあらかじめ設定している推奨プラグインを提案されるためインストール
- ./server/build.gradle.ktsを右クリして`Gradleプロジェクトのリンク`を選択

マイグレーションは以下をdevcontainer内で。

```bash
# 実行ボタンまたは
./gradlew build
./gradlew :app:run

# マイグレーション適用 → ./server/内で、
./gradlew :app:flywayMigrate
./gradlew :app:run
```

- 接続情報は `.env`（`.env.example` をコピーして作成）または環境変数で上書きできる。既定はすべて `crowdodge`。
- **注意**: `.env` は compose が読み込んで DB コンテナに注入する。
- **コンテナ起動するアプリ（`:app:run`）も、`DB_NAME`/`DB_USER`/`DB_PASSWORD`は `.env` の値をそのまま受け取る。ただし `DB_HOST`/`DB_PORT` はコンテナ間通信用の値（`db`/`5432`固定）に自動で上書きされる**（`compose.dev.yaml` の `environment` で設定済み）ため、`.env` の値をそのまま気にする必要はない。
- リセット（baseline 変更時など Flyway チェックサム不一致の解消）: `docker compose -f compose.yaml -f compose.dev.yaml down -v` で volume を破棄してから再起動・再マイグレーションする。

#### VSCode

devcontainerを利用して実行環境を隔離し、簡単に起動できる。
DBとAPP両方コンテナ化。

必要なものは

- Docker Desktop
- VSCode
  - Docker拡張
  - Dev Container拡張

1. コマンドパレットに`> devcincont`などと入力
2. `Reopen in Container`を選択して接続
3. crowdodge.code-workspaceを開き、`Open Workspace`

または、

1. コマンドパレットに`> devcincont`などと入力
2. `Open Workspace in Container`を選択して接続

マイグレーションは以下をdevcontainer内で。

```bash
# マイグレーション適用 → ./server/内で、
./gradlew :app:flywayMigrate
./gradlew :app:run
```

- 接続情報は `.env`（`.env.example` をコピーして作成）または環境変数で上書きできる。既定はすべて `crowdodge`。
- **注意**: `.env` は compose が読み込んで DB コンテナに注入する。
- **コンテナ起動するアプリ（`:app:run`）も、`DB_NAME`/`DB_USER`/`DB_PASSWORD`は `.env` の値をそのまま受け取る。ただし `DB_HOST`/`DB_PORT` はコンテナ間通信用の値（`db`/`5432`固定）に自動で上書きされる**（`compose.dev.yaml` の `environment` で設定済み）ため、`.env` の値をそのまま気にする必要はない。
- リセット（baseline 変更時など Flyway チェックサム不一致の解消）: `docker compose -f compose.yaml -f compose.dev.yaml down -v` で volume を破棄してから再起動・再マイグレーションする。

### 開発用 DB（Docker Compose）

ローカル開発用の PostgreSQL + PostGIS をリポジトリルートの `compose.yml` で起動できる（要 **Docker Compose v2**。`docker-compose` v1 は非対応）。アプリ本体はホストの Gradle で起動し、DB だけをコンテナ化する（テストは Testcontainers が自前で起動）。

```bash
# DB 起動（リポジトリルートで）
# `docker compose up -d db`は、devcontainer方式を追加時に使えなくなったため、以下。
docker compose -f compose.yml up -d db

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

| 変数                      | 用途                                        | 既定値                    |
| ------------------------- | ------------------------------------------- | ------------------------- |
| `PORT`                    | HTTP ポート                                 | `8080`                    |
| `DB_HOST`                 | DB ホスト                                   | `localhost`               |
| `DB_PORT`                 | DB ポート                                   | `5432`                    |
| `DB_NAME`                 | DB 名                                       | `crowdodge`               |
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

実装は `docs/architecture.md` §14 に従う。本リポジトリは **step1（基盤）** 時点。
