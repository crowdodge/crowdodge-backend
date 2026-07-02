# ユビキタス言語

## 更新対象

- ドメイン概念、コード名、テーブル名、イベント名を変更した場合に更新する。
- DB物理名を変更した場合は `../database/tables/` の該当ファイルも更新する。

| 概念 | コード名 | 種別 | 主な物理表現 |
|---|---|---|---|
| ユーザー | `User` | 集約ルート | `users.user_uuid` |
| ユーザー設定 | `UserSetting` | エンティティ | `user_settings` |
| 表示カレンダー | `UserCalendar` | エンティティ | `user_calendars.google_calendar_id` |
| 通知デバイス | `UserDevice` | エンティティ | `user_devices.fcm_token` |
| 課金状態 | `UserSubscription` | 未実装 | `user_subscriptions` Exposed定義のみ |
| ユーザーの予定 | `Event` | 集約ルート | `events.event_uuid` |
| Google予定ID | `googleEventId` | 外部識別子 | `events.google_event_id` |
| 繰り返しシリーズID | `recurringEventId` | 外部識別子 | `events.recurring_event_id` |
| 元の開始時刻 | `originalStart` | 外部識別子 | `events.original_start` |
| 予定日時 | `Schedule` | sealed class | `start_time` / `end_time` または `start_date` / `end_date` |
| 時刻指定予定 | `Schedule.Timed` | 値 | `start_time` / `end_time` |
| 終日予定 | `Schedule.AllDay` | 値 | `start_date` / `end_date` |
| リマインド間隔 | `RemindTiming` | VO | `remind_timing` |
| 予定内容 | `EventContent` | 値 | `title` / `description` / `location` / 予定日時 / リマインド間隔 |
| 目的地グループ | `EventDestination` | 未実装 | `event_destinations` Exposed定義のみ |
| 予定と目的地の紐付け | `EventDestinationLink` | 未実装 | `event_destination_links` Exposed定義のみ |
| 混雑予測 | `EventCongestion` | 未実装 | `event_congestions` Exposed定義のみ |
| 外部の混雑原因 | `CongestionSource` | 未実装 | 永続化なし |
| 通知スケジュール | `NotificationSchedule` | 未実装 | `notification_schedules` Exposed定義のみ |
| 通知ステータス | `NotificationStatus` | 未実装 | `status`。現行定義コメントは `canceled` 表記 |
| 通知種別 | `NotificationKind` | 未実装 | `kind` |
| ドメインイベント基底 | `DomainEvent` | interface | commit後in-process配送 |

## 命名ルール

- `Event` はユーザーの予定を表す。
- 混雑原因となる外部イベントは `CongestionSource` と呼ぶ。
- ドメインイベントの基底名は `DomainEvent` とする。
- ドメインイベント名は業務上の過去形にする。

## 主なドメインイベント

| イベント | 発行元 | 用途 |
|---|---|---|
| `UserRegistered` | user | ユーザー登録後の後続処理 |
| `CalendarSelectionChanged` | user | 同期対象カレンダーの変更通知 |
| `EventScheduled` | event | 予定追加後の目的地推定 |
| `EventRescheduled` | event | 予定変更後の目的地・混雑再評価 |
| `EventCancelled` | event | 予定削除後の通知キャンセル |
| `EventRemindTimingChanged` | event | 通知スケジュール再作成 |

## 未実装イベント

| イベント | 想定発行元 | 状態 |
|---|---|---|
| `EventDestinationEstimated` | destination | 未実装 |
| `EventCongestionPredicted` | congestion | 未実装 |
| `NotificationSent` | notification | 未実装 |
