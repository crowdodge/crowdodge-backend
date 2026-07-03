# events

## 更新対象

- Google カレンダー投影、予定時刻、リマインド間隔、同期キーを変更した場合に更新する。
- Google 同期仕様は [Google カレンダー同期](../../integrations/google-calendar-sync.md) を参照する。

## 責務

Google カレンダーから取得した予定インスタンスを、サーバ処理用の投影として保持する。

## 状態

- Exposed定義あり。
- `V3__change.sql` でマイグレーション反映済み。
- Google Calendar APIからの初回同期と差分同期は実装済み。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `event_uuid` | `uuid` | PK, NOT NULL | 予定ID |
| `user_calendar_uuid` | `uuid` | NOT NULL | 由来カレンダーID |
| `google_event_id` | `text` | NOT NULL | Google予定インスタンスID |
| `recurring_event_id` | `text` | NULL | Google繰り返しシリーズID |
| `original_start` | `timestamptz` | NULL | 発生回の元の開始時刻 |
| `title` | `text` | NULL | タイトル |
| `description` | `text` | NULL | 概要 |
| `location` | `text` | NULL | 場所 |
| `start_time` | `timestamptz` | NULL | 時刻指定予定の開始時刻 |
| `end_time` | `timestamptz` | NULL | 時刻指定予定の終了時刻 |
| `start_date` | `date` | NULL | 終日予定の開始日 |
| `end_date` | `date` | NULL | 終日予定の終了日。排他 |
| `remind_timing` | `bigint` | NULL | 予定個別のリマインド間隔。ナノ秒 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- `UNIQUE(user_calendar_uuid, google_event_id)`。
- `title`、`description`、`location` は Google が省略し得るため NULL を許可する。
- `remind_timing` が NULL の場合は `user_settings.remind_timing` を使う。
- `RemindTiming` VO は正の値のみ許可する。現行DB定義には `remind_timing > 0` の CHECK 制約はない。
- 時刻指定予定は `start_time` / `end_time` を使う。
- 終日予定は `start_date` / `end_date` を使う。
- 時刻指定予定と終日予定の列が混在した行は永続データ破損として扱う。
