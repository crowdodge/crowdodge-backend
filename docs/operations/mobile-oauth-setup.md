# モバイル OAuth セットアップ

## 更新対象

- Google OAuth クライアント構成、Cloud Run の OAuth 関連環境変数、モバイル SDK の認可方式を変更した場合に更新する。
- 認証フロー自体の仕様は [../integrations/google-auth.md](../integrations/google-auth.md) を参照。

## 構成

Android / iOS クライアントは Google Sign-In SDK の serverAuthCode 方式で認可する。GCP には 3 つの OAuth クライアントを置くが、バックエンドが使うのは Web クライアント 1 つだけとする。

| OAuth クライアント | 用途 | バックエンドでの利用 |
|---|---|---|
| ウェブ アプリケーション | serverAuthCode / ID token の宛先（audience） | `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` に設定 |
| Android | アプリ実在性の検証（パッケージ名 + SHA-1） | なし |
| iOS | アプリ実在性の検証（bundle ID） | なし |

SDK に serverClientId として Web クライアント ID を渡すため、Android 発・iOS 発のどちらの serverAuthCode も Web クライアント宛に発行される。バックエンドの token 交換と ID token の audience 検証は Web クライアント 1 組で行う。

## GCP セットアップ手順

APIs & Services → Credentials → Create Credentials → OAuth client ID で以下を作成する。

1. **ウェブ アプリケーション**: 承認済みリダイレクト URI は不要（serverAuthCode 交換では使用しない）。発行された client_id と client_secret を Cloud Run の環境変数に設定する。
2. **Android**: パッケージ名と APK 署名の SHA-1 を登録する。デバッグ用・リリース用で SHA-1 が異なるため両方登録する。
3. **iOS**: bundle ID を登録する。

## Cloud Run 環境変数

| 変数 | 値 |
|---|---|
| `GOOGLE_OAUTH_CLIENT_ID` | Web クライアントの client_id |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Web クライアントの client_secret |

クライアントを差し替えた場合、保存済みの Google refresh token は旧クライアント発行のため `invalid_grant` となる。この場合は `GOOGLE_REAUTH_REQUIRED` として再認可フローに乗るため、追加対応は不要。

## モバイル SDK 設定

- Android（Credential Manager）: `GetGoogleIdOption.Builder().setServerClientId(WEB_CLIENT_ID)`
- iOS（GoogleSignIn SDK）: `GIDConfiguration(clientID: iosClientId, serverClientID: WEB_CLIENT_ID)`

取得した serverAuthCode を `POST /auth/google` の `authorizationCode` として送る。`redirectUri` / `codeVerifier` は送らない。
