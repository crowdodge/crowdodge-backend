# event_destination_links

## 更新対象

- 予定と目的地グループの関連、主キー、関連制約を変更した場合に更新する。

## 責務

予定と目的地グループの紐付けを保持する。

## 状態

- Exposed定義のみ存在する。
- マイグレーション未反映。
- `GenerateMigrationMain` の対象からコメントアウトされている。
- ドメインモデル、リポジトリ、ユースケースは未実装。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `event_uuid` | `uuid` | PK, NOT NULL | `events.event_uuid` |
| `event_destination_uuid` | `uuid` | FK, NOT NULL | `event_destinations.event_destination_uuid` |
| `created_at` | `timestamp` | NOT NULL | 作成日時 |

## 制約

- 1つの予定は1つの目的地グループに所属する。
