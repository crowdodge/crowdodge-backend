# user_items

## 更新対象

- アイテム種別、所持数、主キー名を変更した場合に更新する。

## 責務

ユーザーごとのアイテム所持数を保持する。

## 状態

- Exposed定義あり。
- `V2__user.sql` でマイグレーション反映済み。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `user_item_id_uuid` | `uuid` | NOT NULL | ユーザーアイテムID |
| `user_uuid` | `uuid` | FK, NOT NULL | `users.user_uuid` |
| `item_type` | `text` | NOT NULL | アイテム種別 |
| `item_count` | `integer` | NOT NULL | 所持数 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- `UNIQUE(user_uuid, item_type)`。

## 注意

- 現行の Exposed 定義では主キーが明示されていない。
- `user_item_id_uuid` は NOT NULL だが PRIMARY KEY ではない。
