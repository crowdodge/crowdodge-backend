# event_congestions

## 更新対象

- 混雑予測結果に含まれる混雑期間、時刻範囲、エリア、説明文を変更した場合に更新する。

## 責務

混雑予測結果に含まれる個別の混雑期間を保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `event_congestion_uuid` | `uuid` | PK, NOT NULL | 混雑期間ID |
| `event_uuid` | `uuid` | NOT NULL | 予測対象の予定UUID |
| `event_congestion_forecast_uuid` | `uuid` | FK, NOT NULL | `event_congestion_forecasts.event_congestion_forecast_uuid` |
| `congestion_start_time` | `timestamptz` | NOT NULL | 混雑開始日時 |
| `congestion_end_time` | `timestamptz` | NOT NULL | 混雑終了日時 |
| `area` | `text` | NOT NULL | 混雑が予測されるエリア |
| `description` | `text` | NOT NULL | 混雑説明 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- 混雑予測結果の削除時は、紐づく混雑期間をCASCADEで削除する。
- `event_uuid`はevent BCへの値参照とし、物理外部キーを張らない。
