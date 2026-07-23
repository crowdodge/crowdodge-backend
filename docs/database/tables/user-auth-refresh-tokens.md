# user_auth_refresh_tokens

## 更新対象

- アプリrefresh tokenの保存、失効、有効期限を変更した場合に更新する。

## 責務

アプリ認証で発行したrefresh tokenのhash、有効期限、失効状態を保持する。

## 列

| 列 | 型 | 制約 | 説明 |
|---|---|---|---|
| `refresh_token_uuid` | `uuid` | PK, NOT NULL | refresh token ID |
| `user_uuid` | `uuid` | FK, INDEX, NOT NULL | `users.user_uuid` |
| `token_hash` | `varchar(64)` | UNIQUE, NOT NULL | refresh tokenのSHA-256 hash |
| `expires_at` | `timestamptz` | NOT NULL | 有効期限 |
| `revoked_at` | `timestamptz` | NULL許可 | 失効日時 |
| `created_at` | `timestamptz` | NOT NULL | 作成日時 |
| `updated_at` | `timestamptz` | NOT NULL | 更新日時 |

## 制約

- refresh tokenの平文は保存しない。
- refresh時は使用済みtokenを失効し、新しいtokenへローテーションする。
- signout時は対象tokenを失効する。
- ユーザー削除時にrefresh tokenも削除する。
