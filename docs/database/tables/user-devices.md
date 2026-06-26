# user_devices

## 更新対象

- FCMトークン、デバイス識別、失効処理を変更した場合に更新する。

## 責務

通知送信先のデバイスを保持する。

## 状態

- Exposed定義あり。
- `V2__user.sql` でマイグレーション反映済み。
- FCM送信処理は未実装。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `device_uuid` | `uuid` | PK, NOT NULL | デバイスID |
| `user_uuid` | `uuid` | FK, NOT NULL | `users.user_uuid` |
| `fcm_token` | `text` | NOT NULL, UNIQUE | FCMトークン |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |
