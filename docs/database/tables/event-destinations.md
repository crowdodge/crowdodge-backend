# event_destinations

## 更新対象

- 目的地グループ、ルート情報、テーブル制約を変更した場合に更新する。

## 責務

予定の目的地とルート情報を保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `event_destination_uuid` | `uuid` | PK, NOT NULL | 目的地グループID |
| `recurring_event_id` | `text` | NULL, UNIQUE | シリーズ共有用の相関ID |
| `destination` | `text` | NOT NULL | 目的地名 |
| `destination_point` | `geography(Point,4326)` | NOT NULL | 目的地座標 |
| `route_duration` | `bigint` | NOT NULL | 所要時間。ナノ秒 |
| `route_information` | `jsonb` | NOT NULL | ルート情報 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |
