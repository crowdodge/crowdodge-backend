# Google認証とアプリセッション

## 更新対象

- Google OAuth 認証フロー、ID token 検証、資格情報の保存方式、アプリセッションを変更した場合に更新する。
- 関連テーブルを変更した場合は `../database/tables/user-google-credentials.md` と `../database/tables/user-auth-refresh-tokens.md` も更新する。

## 実装状況

- `POST /auth/google`、`POST /auth/refresh`、`POST /auth/logout`、`GET /auth/me` は app に配線済み。
- 認可コード交換、ID token 検証、資格情報の暗号化保存、アプリセッション発行・更新・失効は実装済み。

## Google OAuth 認証フロー

- クライアントは PKCE で取得した認可コード、redirect URI、code verifier を `POST /auth/google` へ送る。
- サーバは認可コードを Google token endpoint で交換し、access token、refresh token、ID token を取得する。
- 必須 scope は `https://www.googleapis.com/auth/calendar.events` と `https://www.googleapis.com/auth/calendar.calendarlist.readonly` とする。付与 scope に含まれない場合はエラーとする。

## ID token 検証

- ID token は JWT として RS256 署名を Google JWKS で検証する。
- JWKS は応答の Cache-Control に従ってキャッシュする。
- issuer、audience（OAuth client ID）、email verification を検証する。

## 資格情報の保存

- Google 資格情報は Google アカウント単位で `user_google_credentials` に保持する。primary key は `user_uuid`、`google_subject` は unique とする。
- access token と refresh token は AES-256-GCM で暗号化して保存する。
- 暗号文は `v1.<base64url(nonce)>.<base64url(ciphertext)>` 形式とし、nonce は 12 バイトのセキュア乱数とする。
- 付与済み scope はスペース区切りで保持する。

## 登録とログイン

`POST /auth/google` は次の順序で処理する。

1. 認可コードを交換し、ID token を検証する。
2. Google subject で既存ユーザーを特定する。未登録の場合は新規登録する。新規登録時に `user_calendars` へカレンダーは登録しない。
3. `user_google_credentials` を upsert する。再認証時は付与 scope を更新する。
4. アプリ refresh token を発行し、hash を `user_auth_refresh_tokens` へ保存する。
5. アプリ access token、refresh token、有効期限を返す。

## アプリセッション

- アプリ access token は JWT とし、HS256 で署名する。subject は user UUID、既定 TTL は15分とする。
- アプリ refresh token は 32 バイトのセキュア乱数を Base64url で表現する。平文は保存せず SHA-256 hash を `user_auth_refresh_tokens` に保持する。既定 TTL は30日とする。
- `POST /auth/refresh` は提示された refresh token の hash で使用可能なトークンを検索し、`revoked_at` を記録して消費したうえで新しい access token と refresh token を発行する。
- `POST /auth/logout` は refresh token を失効させる。

## エンドポイント

| エンドポイント | メソッド | 認証 | 用途 |
|---|---|---|---|
| `/auth/google` | POST | 不要 | 認可コード交換、ユーザー登録/ログイン、セッション発行 |
| `/auth/refresh` | POST | 不要 | refresh token によるアクセストークン更新 |
| `/auth/logout` | POST | 不要 | refresh token の失効 |
| `/auth/me` | GET | JWT 必須 | 現在のユーザー情報取得 |
