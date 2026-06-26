# Google カレンダー同期

## 更新対象

- Google Calendar API の同期、watch、同期窓、識別子を変更した場合に更新する。
- 関連テーブルを変更した場合は `../database/tables/events.md` と `../database/tables/event-calendar-syncs.md` も更新する。

## 実装状況

- 実装済み: 同期ユースケース、同期ポート、watch状態参照ポート、同期進捗DBアダプタ、予定投影リポジトリ。
- 未実装: Google Calendar API クライアント本体、watch登録・更新ジョブ、Googleへの予定書き込み、FCM同期ヒント送信。

## 基本方針

- カレンダーの Source of Truth は Google カレンダーとする。
- 繰り返しルールと例外展開は Google が所有する。
- サーバは繰り返しルールを保持しない。
- サーバは `singleEvents=true` で展開された予定インスタンスを `events` に投影する。
- サーバは `materialized_until` までのローリング窓に含まれる予定だけを保持する。

## 識別子

| 識別子 | 発行元 | 用途 |
|---|---|---|
| `google_event_id` | Google | 予定インスタンスの突合キー |
| `recurring_event_id` | Google | 繰り返しシリーズの識別子。単発予定は NULL |
| `original_start` | Google | 発生回の元の開始時刻 |
| `user_calendar_uuid` | user BC | 同期対象カレンダーの識別子 |

## 同期状態

- 同期状態は `event_calendar_syncs` に保持する。
- `sync_token` は Google Calendar 増分同期の継続トークンとして扱う。
- `materialized_until` はサーバ投影の将来端として扱う。
- `watch_channel_id` は webhook 受信時に `user_calendar_uuid` を逆引きするために使う。

## webhook 起点の同期

`SyncCalendarUseCase.handle(channelId)` は次の順序で処理する。

1. `CalendarWatchPort.findByChannelId(channelId)` で同期対象の `user_calendar_uuid` を取得する。
2. 対象が見つからない場合は何もしない。
3. `CalendarSyncProgressPort` から `sync_token` と `materialized_until` を読む。
4. `materialized_until` が NULL の場合は Google API を呼ばず何もしない。
5. `CalendarSyncGateway.fetchUpdatedEvents(userCalendarUuid, syncToken)` をトランザクション外で呼ぶ。
6. 取得結果をトランザクション内で `events` に反映し、ドメインイベントを発行する。
7. 反映成功後、別トランザクションで `sync_token` を更新する。

## 増分同期

- 通常同期では `sync_token` を Google Calendar API へ渡す。
- `sync_token` が失効した場合、ACL実装はフル同期へフォールバックし、`CalendarSyncResult.isFullSync = true` を返す。
- 増分同期では Google API の仕様上 `syncToken` と `timeMin` / `timeMax` を併用できない。
- 窓外予定の除外は `SyncCalendarUseCase` が行う。

## ローリング窓

- 窓の開始は同期処理時点の現在時刻とする。
- 窓の終了は `event_calendar_syncs.materialized_until` とする。
- 予定が窓に含まれる条件は `schedule.start() < windowEnd && schedule.end() > windowStart` とする。
- 窓外の incoming 予定は保存しない。
- 既存予定が窓外へ移動した場合は削除する。

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

## 冪等性

- `events` は `(user_calendar_uuid, google_event_id)` を競合キーとして upsert する。
- upsert時、既存の `event_uuid` と `created_at` は保持する。
- 変更がない incoming 予定は upsert せず、イベントも発行しない。
- `sync_token` は予定反映が成功した後にだけ前進させる。
- `sync_token` 更新に失敗した場合、次回同じ変更を再取得し得るが、無変化判定により重複イベントを抑制する。

## watch

- webhook 受信時は `X-Goog-Channel-ID` 相当の `channelId` で `event_calendar_syncs.watch_channel_id` を逆引きする。
- `watch_resource_id`、`watch_channel_token`、`watch_expiration` は保存列のみ存在する。
- watch登録、検証、再登録ジョブは未実装。

## 書き込み

- Google Calendar への予定書き込みは未実装。
- 書き込み時の競合解決、`etag` 保存、Googleから戻る webhook の無限ループ防止は未確定。
