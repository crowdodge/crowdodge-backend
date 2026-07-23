# user_subscriptions

## 更新対象

- 課金プラン、購読状態、RevenueCat 連携を変更した場合に更新する。

## 責務

ユーザーのサブスクリプション状態を保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `user_uuid` | `uuid` | PK, FK, NOT NULL | `users.user_uuid` |
| `plan_name` | `text` | NOT NULL | プラン名 |
| `status` | `text` | NOT NULL | 購読状態 |
| `expires_at` | `timestamp` | NOT NULL | 有効期限 |
| `rc_original_transaction_id` | `text` | NOT NULL | RevenueCat 取引ID |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 値

- `plan_name`: `Free` / `Premium`。
- `status`: RevenueCat の購読状態に対応する値。
