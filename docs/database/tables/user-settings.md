# user_settings

## 更新対象

- 自宅座標、既定リマインド間隔を変更した場合に更新する。

## 責務

ユーザー単位の設定を保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `user_uuid` | `uuid` | PK, FK, NOT NULL | `users.user_uuid` |
| `home` | `geography(Point,4326)` | NOT NULL | 自宅座標 |
| `remind_timing` | `bigint` | NOT NULL | 既定リマインド間隔。ナノ秒 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |
