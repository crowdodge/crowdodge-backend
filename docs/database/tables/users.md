# users

## 更新対象

- ユーザー基本情報、Google認証ID、メール制約を変更した場合に更新する。

## 責務

ユーザーのアカウント基本情報を保持する。

## 状態

- Exposed定義あり。
- `V2__user.sql` でマイグレーション反映済み。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `user_uuid` | `uuid` | PK, NOT NULL | ユーザーID |
| `google_id` | `varchar(255)` | NOT NULL | Google ID |
| `email` | `text` | NOT NULL, UNIQUE | メールアドレス |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |
