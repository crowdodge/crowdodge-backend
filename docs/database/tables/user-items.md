# user_items

## 更新対象

- アイテムID、アイテム種別、所持数、テーブル制約を変更した場合に更新する。

## 責務

ユーザーごとのアイテム所持数を保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `user_item_id_uuid` | `uuid` | NOT NULL、PRIMARY KEYではない | ユーザーアイテムID |
| `user_uuid` | `uuid` | FK, NOT NULL | `users.user_uuid` |
| `item_type` | `text` | NOT NULL | アイテム種別 |
| `item_count` | `integer` | NOT NULL | 所持数 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- `UNIQUE(user_uuid, item_type)`。
