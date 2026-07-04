# Google カレンダー同期

## 更新対象

- Google Calendar API の同期、watch、同期窓、識別子を変更した場合に更新する。
- 同期状態を変更した場合は `../database/tables/event-calendar-syncs.md` も更新する。
- 予定投影を変更した場合は `../database/tables/events.md` も更新する。
- Google 認証と資格情報は [Google認証とアプリセッション](google-auth.md) に従う。

## 実装状況

- 実装済み: Google Calendar選択API、Google Calendar API クライアント、watch登録、初回同期、差分同期、`syncToken` 失効時のフル同期、ローリング窓内への予定投影、Webhook受信ルート、watch期限前更新ジョブ。
- 未実装: 予定ドメインイベントの下流購読者。

## 基本方針

- カレンダーの Source of Truth は Google Calendar とする。
- カレンダー同期は読み取り専用とし、Google Calendar への予定書き込みは行わない。
- Google カレンダーと OAuth 認証情報は user BC が所有する。
- 予定と同期進捗は event BC が所有する。
- event BC は app 層の `UserCalendarConnectionAdapter` を ACL として使用し、user BC のテーブルを直接参照しない。
- 繰り返しルールと例外展開は Google が所有し、サーバは保持しない。
- サーバは `singleEvents=true` で展開された予定インスタンスを `events` に投影する。
- サーバは `materialized_until` までのローリング窓に含まれる予定だけを保持する。

## 識別子

| 識別子 | 発行元 | 用途 |
|---|---|---|
| `google_event_id` | Google | 予定インスタンスの突合キー |
| `recurring_event_id` | Google | 繰り返しシリーズの識別子。単発予定は NULL |
| `original_start` | Google | 発生回の元の開始時刻 |
| `user_calendar_uuid` | user BC | 同期対象カレンダーの識別子 |

## BC間連携

event BC は `CalendarConnectionProvider` からカレンダー ID と有効な access token を取得する。
app 層の `UserCalendarConnectionAdapter` は event BC の `UserCalendarUuid` を user BC の
`UserCalendarUuid` へ変換し、`ResolveGoogleCalendarConnectionUseCase` を呼び出す。

user BC は保存済み scope を検証し、access token の失効まで1分以内なら refresh token を使って更新する。
更新した access token と有効期限は `user_google_credentials` へ保存する。

## カレンダー選択

すべてのルートは `app-jwt` 認証を必須とする。

- `GET /users/me/google-calendars` は Google Calendar List と `user_calendars` を突合し、カレンダーID、名前、色、primary、access role、選択状態を返す。
- `PUT /users/me/google-calendars` は `calendarIds` で選択全体を一括置換し、成功時は `204 No Content` を返す。
- 選択数は0件から3件までとし、重複を許可しない。
- `accessRole` が `owner` または `writer` のカレンダーだけを選択できる。

選択更新は次の順序で処理する。

1. Google Calendar List と現在の選択から追加、維持、解除を計算する。
2. 追加対象のwatchと同期状態を作成する。
3. すべての追加準備に成功した場合だけ `user_calendars` を一括置換する。
4. 追加対象ごとに `CalendarInitialSyncRequested` をcommit後配送し、初回同期する。
5. 解除対象のwatch停止、保存予定削除、同期状態削除をベストエフォートで実行する。

追加準備または選択確定に失敗した場合、作成済みwatchと同期状態を補償削除し、以前の選択を維持する。

## 同期状態

- 同期状態は `event_calendar_syncs` に保持する。
- `sync_token` は Google Calendar 増分同期の継続トークンとして扱う。
- `materialized_until` はサーバ投影の将来端として扱う。
- `watch_channel_id` は webhook 受信時に `user_calendar_uuid` を逆引きするために使う。

## 同期処理

`GoogleCalendarEventSynchronizer` は初回同期、差分同期、明示された期間のフル同期を同じ予定反映処理で扱う。

1. `event_calendar_syncs` から `sync_token` と `materialized_until` を読む。
2. user BC からカレンダー ID と有効な access token を取得する。
3. Google Calendar API をトランザクション外で呼ぶ。
4. 同期状態をロックし、取得開始時の `sync_token` と `materialized_until` が現在値と一致することを確認する。
5. 予定反映、ドメインイベント発行、`sync_token` と `materialized_until` の更新を同一トランザクションで確定する。
6. 同期状態が変化していた場合は取得結果を破棄し、最新状態から再実行する。

`materialized_until` が NULL、または同期状態が存在しない場合は Google API を呼ばず何もしない。

## 初回同期

選択追加時は `CalendarInitialSyncRequestedHandler` が初回同期を実行する。
`syncToken` がない場合は `singleEvents=true`、`showDeleted=true`、`maxResults=2500`、
`timeMin=同期処理開始時刻`、`timeMax=materialized_until` で `events.list` を呼ぶ。
`nextPageToken` がある間は全ページを取得し、最終ページの `nextSyncToken` を保存する。

## 増分同期

- 保存済み `syncToken` を `events.list` へ渡す。
- Google の制約により、`syncToken` と `timeMin` / `timeMax` は併用しない。
- `showDeleted=true` としてキャンセルされた予定を取得する。
- Google が `410 Gone` を返した場合、同じ処理内で初回同期へフォールバックする。

## ローリング窓

- 窓の開始は同期処理時点の現在時刻とする。
- 窓の終了は `event_calendar_syncs.materialized_until` とする。
- 予定が窓に含まれる条件は `schedule.start() < windowEnd && schedule.end() > windowStart` とする。
- 窓外の incoming 予定は保存しない。
- 既存予定が窓外へ移動した場合は削除する。

## Google Event変換

| Google Calendar | Crowdodge |
|---|---|
| `id` | `googleEventId` |
| `recurringEventId` | `recurringEventId` |
| `originalStartTime` | `originalStart` |
| `summary` | title |
| `description` | description |
| `location` | location |
| `start` / `end` | schedule |
| 最初の正の reminder override | remind timing |

- 日時指定と終日予定を扱う。
- 不正または未対応の時刻形式は同期失敗として扱う。
- `status=cancelled` は削除対象として扱う。

## 差分反映

| 条件 | DB反映 | 発行イベント |
|---|---|---|
| 既存にない窓内予定 | insert/upsert | `EventScheduled` |
| 時刻・タイトル・概要・場所が変化 | upsert | `EventRescheduled` |
| リマインド間隔が変化 | upsert | `EventRemindTimingChanged` |
| 時刻が変化 | upsert | `EventRemindTimingChanged` も発行 |
| 変化なし | 何もしない | なし |
| Google側キャンセル | delete | `EventCancelled` |
| 窓外退避 | delete | `EventCancelled` |
| フル同期結果に存在しない既存予定 | delete | `EventCancelled` |

## 冪等性とトランザクション

- Google API は DB トランザクション外で呼ぶ。
- `events` は `(user_calendar_uuid, google_event_id)` を競合キーとして upsert する。
- upsert 時、既存の `event_uuid` と `created_at` は保持する。
- 変更がない予定は upsert せず、ドメインイベントも発行しない。
- 予定反映、ドメインイベント発行、`sync_token` 更新、`materialized_until` 更新は同一トランザクションで行う。

## エラー

- Calendar API の connect、socket、request timeout は `GoogleCalendarTimeoutError` として扱う。
- OAuth token endpoint の `invalid_grant` は `InvalidRefreshToken` として扱う。
- OAuth token endpoint の connect、socket、request timeout は `GoogleCalendarTimeoutError` として扱い、その他の失敗は `GoogleOAuthError` として扱う。
- coroutine cancellation は外部連携エラーへ変換せず再送出する。

## 環境変数

| 変数 | 用途 | 既定値 |
|---|---|---|
| `GOOGLE_CALENDAR_API_BASE_URL` | Calendar API の base URL | `https://www.googleapis.com` |
| `GOOGLE_CALENDAR_WEBHOOK_URL` | Google Calendar watch の通知先URL | なし |
| `GOOGLE_CALENDAR_CHANNEL_TOKEN` | webhook通知のchannel token | なし |
| `GOOGLE_CALENDAR_FULL_SYNC_WINDOW_DAYS` | 初回取得と投影対象の日数 | `90` |

## watch

- watch登録は `events.watch` を呼び、`event_calendar_syncs` に channel ID、resource ID、channel token、有効期限を保存する。
- watch停止は `channels.stop` を呼ぶ。停止失敗は予定削除や同期状態削除を妨げない。
- `POST /webhooks/google-calendar` で Google Calendar webhook 通知を受ける。
- `X-Goog-Channel-ID` と `X-Goog-Resource-State` は必須とし、不足時は `400 Bad Request` を返す。
- `X-Goog-Resource-State: sync` は同期せず `204 No Content` を返す。
- `X-Goog-Resource-State: exists` の場合、`X-Goog-Channel-ID` で `event_calendar_syncs.watch_channel_id` を逆引きする。
- 保存済み `watch_channel_token` と `X-Goog-Channel-Token` が一致しない場合は同期せず `204 No Content` を返す。
- 登録済み channel で token が一致した場合は増分同期を実行し、成功時は `204 No Content`、同期失敗時は `502 Bad Gateway` を返す。
- `watch_resource_id`、`watch_channel_token`、`watch_expiration` は watch停止、token検証、期限前更新に使う。

## 定期整合・watch更新

`renewGoogleCalendarWatches` はHTTPサーバを起動せず、Google Calendar選択状態と同期状態の整合を1回実行する。

- 選択済みで同期状態がないカレンダーはwatchを登録し、初回同期する。
- `syncToken` がない選択済みカレンダーは初回同期する。
- 選択されていない同期状態はwatch停止、保存予定削除、同期状態削除を行う。
- Calendar Listでowner/writer権限を確認できなくなった選択はuser BCの選択を解除し、watch停止、保存予定削除、同期状態削除を行う。
- user BCの選択解除に失敗したカレンダーは、event BCの同期状態と保存予定を残す。
- `watch_expiration` が24時間以内のwatchは更新対象とする。
- watch更新時は新watchを登録し、現在時刻から同期対象日数後までをフル同期した後にwatch情報を置き換える。
- watch情報の置き換えに成功した場合、旧watchをベストエフォートで停止する。
- watch更新に失敗した場合、旧watch情報と同期状態を維持し、他カレンダーの処理を続ける。

運用手順は [Google Calendar Watch Renewal Job](../operations/google-calendar-watch-renewal.md) に従う。
