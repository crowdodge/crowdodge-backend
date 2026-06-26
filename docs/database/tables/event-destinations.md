# event_destinations

## 更新対象

- 目的地グループ、ルート情報、シリーズ共有ルールを変更した場合に更新する。

## 責務

予定の目的地とルート情報を保持する。

## 状態

- Exposed定義のみ存在する。
- マイグレーション未反映。
- `GenerateMigrationMain` の対象からコメントアウトされている。
- ドメインモデル、リポジトリ、ユースケースは未実装。

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

## 制約

- 同一 `recurring_event_id` の予定は同じ目的地グループを共有する。
- 単発予定は `recurring_event_id` を NULL にする。
