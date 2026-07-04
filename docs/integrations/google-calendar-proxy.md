# Calendar Events API プロキシ

## 更新対象

- プロキシのルート、転送範囲、query allowlist、認証、トークン更新を変更した場合に更新する。
- Google 認証と資格情報は [Google認証とアプリセッション](google-auth.md) に従う。

## 実装状況

- `/google-calendar` 配下のルートは app に配線済み。
- リクエスト転送、query allowlist 検証、401 時のトークン更新と再試行は実装済み。

## 概要

- クライアントが選択済みの Google カレンダーへ予定の読み取りと書き込みを行うための中継とする。
- アプリ JWT で認証した利用者の Google 資格情報を使い、Google Calendar Events API へ転送する。
- リクエストとレスポンスの内容を解釈・保存しない。プロキシ経由の書き込み内容は webhook 同期が `events` へ反映する。

## ルート

すべて `app-jwt` 認証を必須とする。

| メソッド | パス | 対象カレンダー |
|---|---|---|
| GET / POST | `/google-calendar/calendars/{calendarId}/events` | `{calendarId}` |
| GET / PATCH / DELETE | `/google-calendar/calendars/{calendarId}/events/{eventId}` | `{calendarId}` |

`calendarId` は利用者の `user_calendars` に存在するものだけを許可する。
未選択の `calendarId` は `403 Forbidden` を返す。

転送先は `{GOOGLE_CALENDAR_API_BASE_URL}/calendar/v3/calendars/{calendarId}/events[/{eventId}]` とする。

## query parameter

- 次の allowlist に含まれる query parameter だけを転送する。

```text
timeMin, timeMax, pageToken, maxResults, singleEvents, orderBy, showDeleted,
timeZone, q, eventTypes, iCalUID, privateExtendedProperty,
sharedExtendedProperty, updatedMin, syncToken
```

- allowlist 外の query parameter を含む場合は `400 Bad Request` を返す。

## トークン

- 資格情報は `user_google_credentials` からユーザー単位で取得する。
- access token の失効まで1分以内の場合は、転送前に refresh token で更新する。
- Google から `401 Unauthorized` を受けた場合は1回だけ refresh し、再試行する。
- 資格情報がない、または更新に必要な refresh token がない場合は `401` を返す。
- refresh token が無効な場合は再認証が必要な `401` を返す。
- token endpoint の timeout は `504`、その他の更新失敗は `502` を返す。

## レスポンス

- Google の成功レスポンスの status、Content-Type、body、ETag を返す。
- レスポンス body は最大 1 MiB とし、超過した場合は `502 Bad Gateway` を返す。
- 接続・送信・応答のタイムアウトは各10秒とし、超過した場合は `504 Gateway Timeout` を返す。
- Google から `403 Forbidden`、`404 Not Found`、`409 Conflict`、`410 Gone`、`429 Too Many Requests` を受けた場合は同じstatusの Problem Details を返す。
- Google再認可が必要な場合は `401 Unauthorized` とcode `GOOGLE_REAUTH_REQUIRED` の Problem Details を返す。
- その他の転送失敗は `502 Bad Gateway` を返す。
