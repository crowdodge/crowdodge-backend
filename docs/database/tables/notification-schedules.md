# notification_schedules

## 更新対象

- 通知キュー、通知時刻、ステータス、通知種別を変更した場合に更新する。
- ジョブ処理は [非同期処理と通知ジョブ](../../operations/async-processing.md) を参照する。

## 責務

通知ジョブのキューを保持する。

## 状態

- Exposed 定義・マイグレーション（`V5__change.sql`）反映済み。
- `GenerateMigrationMain` の対象に登録済み。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `notification_schedules_uuid` | `uuid` | PK, NOT NULL | 通知スケジュールID |
| `user_uuid` | `uuid` | NOT NULL | `users.user_uuid` |
| `event_uuid` | `uuid` | NOT NULL | `events.event_uuid` |
| `notificate_time` | `timestamptz` | NOT NULL | 通知予定時刻 |
| `kind` | `text` | NOT NULL | 通知種別 |
| `status` | `text` | NOT NULL | 通知状態 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 値

- `kind`: `Reminder` / `CongestionAlert`。
- `status`: `pending` / `processing` / `completed` / `failed` / `canceled`。
