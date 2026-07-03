# user_google_credentials

## 更新対象

- Google OAuth資格情報、暗号化方式、Googleアカウントとの対応を変更した場合に更新する。

## 責務

ユーザーに紐づくGoogleアカウントとOAuth資格情報を保持する。

## 状態

- Exposed定義あり。
- マイグレーション反映済み。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `user_uuid` | `uuid` | PK, FK, NOT NULL | `users.user_uuid` |
| `google_subject` | `varchar(255)` | UNIQUE, NOT NULL | Googleアカウントのsubject |
| `access_token` | `text` | NOT NULL | AES-256-GCMで暗号化したGoogle access token |
| `refresh_token` | `text` | NULL許可 | AES-256-GCMで暗号化したGoogle refresh token |
| `access_token_expires_at` | `timestamptz` | NOT NULL | Google access tokenの有効期限 |
| `granted_scopes` | `text` | NOT NULL | Googleから付与されたscope |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- ユーザーごとにGoogle資格情報を1件保持する。
- 同一Googleアカウントを複数ユーザーへ紐づけない。
- ユーザー削除時に資格情報も削除する。
