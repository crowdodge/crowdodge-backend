# DB共通ルール

## 更新対象

- DB、命名規約、共通列、マイグレーション方式を変更した場合に更新する。
- 個別テーブルの変更は `tables/` の該当ファイルを更新する。

## DB

- PostgreSQL を使う。
- 座標は PostGIS の `geography(Point,4326)` を使う。
- PostGIS 拡張は `V1__baseline.sql` で有効化する。

## 共通列

- 原則として全テーブルに `created_at` と `updated_at` を持たせる。
- `created_at` と `updated_at` は `timestamptz NOT NULL` とする。
- `TimestampedTable` の値は `clientDefault { Clock.System.now() }` でアプリ側が設定する。
- DBデフォルト値は付けない。
- 関連テーブルなど更新日時が不要な場合は、個別テーブル仕様に明記する。

## 命名

- テーブル名と列名は snake_case とする。
- 主キーは原則 `<単数テーブル名>_uuid` とする。
- 外部キー列名は参照先の主キー列名と同じにする。
- コンテキストをまたぐ参照は、物理外部キーを張らず UUID 値として保持できる。

## 時間型

- 一時点は `timestamptz` を使う。
- 業務日付は `date` を使う。

## テーブル仕様

- テーブルごとの仕様は `tables/` 配下の同名ファイル（snake_case を kebab-case にしたファイル名）に置く。
- テーブルを追加した場合は `tables/` に仕様ファイルを追加する。
