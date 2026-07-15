# event_congestion_forecasts

## 更新対象

- 混雑予測の保持単位、生成入力ハッシュ、再利用条件を変更した場合に更新する。

## 責務

予定単位の混雑予測結果と、その生成条件を保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `event_congestion_forecast_uuid` | `uuid` | PK, NOT NULL | 混雑予測結果ID |
| `event_uuid` | `uuid` | UNIQUE, NOT NULL | 予測対象の予定UUID |
| `generation_input_hash` | `varchar(64)` | NOT NULL | 生成入力のSHA-256ハッシュ |
| `generated_at` | `timestamptz` | NOT NULL | 混雑予測の生成日時 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- 1つの予定につき混雑予測結果は最大1件とする。
- `generation_input_hash`と`generated_at`の複合インデックスを持つ。
- `event_uuid`はevent BCへの値参照とし、物理外部キーを張らない。

## 再利用条件

- 現在の生成入力から計算したSHA-256ハッシュが`generation_input_hash`と一致する。
- `generated_at`が現在時刻から7日以内である。
- 生成後の再取得で入力ハッシュが変化していた場合は保存しない。
