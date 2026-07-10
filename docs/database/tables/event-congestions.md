# event_congestions

## 更新対象

- 混雑予測の保持単位、時刻範囲、説明文を変更した場合に更新する。

## 責務

予定に対する混雑予測を保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `event_congestion_uuid` | `uuid` | PK, NOT NULL | 混雑予測ID |
| `event_uuid` | `uuid` | NOT NULL | `events.event_uuid` |
| `congestion_start_time` | `timestamp` | NOT NULL | 混雑開始日時 |
| `congestion_end_time` | `timestamp` | NOT NULL | 混雑終了日時 |
| `description` | `text` | NOT NULL | 混雑説明 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- 繰り返し予定単位で混雑予測を扱う。
