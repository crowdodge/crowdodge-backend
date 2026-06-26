# event_calendar_syncs

## 更新対象

- Google カレンダー同期状態、watch チャンネル、ローリング窓を変更した場合に更新する。
- 同期手順は [Google カレンダー同期](../../integrations/google-calendar-sync.md) を参照する。

## 責務

Google カレンダー取り込みの同期状態と watch 状態を保持する。

## 状態

- Exposed定義あり。
- `V3__change.sql` でマイグレーション反映済み。
- watch登録・更新ジョブは未実装。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `user_calendar_uuid` | `uuid` | PK, NOT NULL | `user_calendars.user_calendar_uuid` |
| `sync_token` | `text` | NULL | Google Calendar 増分同期トークン |
| `materialized_until` | `timestamptz` | NULL | 投影済みローリング窓の将来端 |
| `watch_channel_id` | `text` | NULL, UNIQUE | Google Push チャンネルID |
| `watch_resource_id` | `text` | NULL | watch 対象リソースID |
| `watch_channel_token` | `text` | NULL | webhook 検証トークン |
| `watch_expiration` | `timestamptz` | NULL, INDEX | watch 失効時刻 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- `watch_expiration` は再登録対象の抽出に使う。
- コンテキスト間の物理外部キーは張らず、`user_calendar_uuid` を値として保持する。
