# 永続化方針

## 更新対象

- DB接続、マイグレーション、テーブル命名、Exposed の配置を変更した場合に更新する。
- テーブル定義を変更した場合は [DB共通ルール](../database/overview.md) と `../database/tables/` も更新する。

## DB接続

- 実行時DB接続は R2DBC を使う。
- 接続プールは `r2dbc-pool` を使う。
- 実行時クエリは Exposed R2DBC の `suspendTransaction` で実行する。

## マイグレーション

- マイグレーションは Flyway で管理する。
- Flyway は JDBC 接続で実行する。
- マイグレーションファイルは `server/app/src/main/resources/db/migration/` に置く。
- アプリ起動時に自動マイグレーションは実行しない。
- デプロイ前または init container で `:app:flywayMigrate` を実行する。

## Exposed

- Exposed の `Table` 定義は `infrastructure/persistence` に閉じる。
- ドメインモデルは Exposed に依存しない。
- ドメインとDB行の変換は `infrastructure` のリポジトリまたはデータソースで行う。

## 命名

- テーブル名と列名は snake_case とする。
- 主キーは原則 `<単数テーブル名>_uuid` とする。
- 外部キー列名は参照先の主キー名と合わせる。
- 境界づけられたコンテキストの境界はテーブル接頭辞で表す。
- DBスキーマは分割しない。
