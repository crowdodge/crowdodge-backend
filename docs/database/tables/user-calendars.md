# user_calendars

## 更新対象

- カレンダーID、選択数、選択可能なアクセス権、テーブル制約を変更した場合に更新する。

## 責務

ユーザーが混雑回避の対象に選択した Google カレンダーを保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `user_calendar_uuid` | `uuid` | PK, NOT NULL | ユーザーカレンダーID |
| `user_uuid` | `uuid` | FK, NOT NULL | `users.user_uuid` |
| `google_calendar_id` | `text` | NOT NULL | Google カレンダーID |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- `UNIQUE(user_uuid, google_calendar_id)`。
- 同一ユーザーが同一カレンダーを重複登録できない。
- 共有カレンダーを複数ユーザーが登録することは許可する。
- ユーザーが選択できるカレンダーは0件から3件までとする。
- Google Calendar List の `accessRole` が `owner` または `writer` のカレンダーだけを選択できる。
