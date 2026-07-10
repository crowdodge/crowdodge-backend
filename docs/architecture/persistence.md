# 永続化方針

## 更新対象

- DB接続、マイグレーション、Exposed の配置を変更した場合に更新する。

## DB接続

- 実行時DB接続は R2DBC を使う。
- 接続プールは `r2dbc-pool` を使う。
- 実行時クエリは Exposed R2DBC の `suspendTransaction` で実行する。

## マイグレーション

- マイグレーションは Flyway で管理する。
- Flyway は JDBC 接続で実行する。
- マイグレーションファイルは `server/app/src/main/resources/db/migration/` に置く。

## Exposed

- Exposed の `Table` 定義は `infrastructure/persistence` に閉じる。
- ドメインモデルは Exposed に依存しない。
- ドメインとDB行の変換は `infrastructure` のリポジトリまたはデータソースで行う。
