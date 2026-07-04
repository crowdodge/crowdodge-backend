# 境界づけられたコンテキスト

## 更新対象

- コンテキストの責務、集約、イベント、ポリシーを変更した場合に更新する。

## user

### 実装状況

- ドメインモデル、エラー型、イベント、リポジトリ interface、Exposed リポジトリは実装済み。
- GoogleカレンダーのOAuth認証情報取得とaccess token更新は実装済み。
- Google認証、セッション発行・更新・失効、`/auth/*` のpresentationは実装済み。
- 定期整合用のGoogle Calendar List取得は実装済み。
- Google Calendar APIプロキシは実装済み。
- 利用者向けカレンダー一覧API、0件から3件の選択更新、追加時のwatch登録と初回同期、解除時の同期削除は実装済み。

### 責務

- ユーザーアカウントを管理する。
- Google カレンダーの選択状態を管理する。
- GoogleカレンダーのOAuth認証情報を管理する。
- FCM 通知対象デバイスを管理する。
- 課金状態のテーブル定義を持つ。課金ドメインモデルと課金連携は未実装。

### 集約・エンティティ

- `User`
- `UserSetting`
- `UserCalendar`
- `UserDevice`

### テーブル定義のみ

- `UserItemsTable`
- `UserSubscriptionsTable`

## event

### 実装状況

- ドメインモデル、エラー型、イベント、同期サービス、同期ポート、Exposed リポジトリは実装済み。
- Google Calendar APIクライアント、watch登録、初回同期、差分同期、`syncToken`失効時のフル同期、watch期限前更新は実装済み。
- Google Calendar webhook 受信routeは実装済み。その他のpresentation層の業務APIは未実装。

### 責務

- Google カレンダーの予定をサーバ内の投影として保持する。
- 予定の追加、更新、削除をドメインイベントとして公開する。
- 繰り返しルールそのものは保持しない。
- `singleEvents=true` で展開された予定インスタンスだけを扱う。

### 集約・値

- `Event`
- `EventContent`
- `Schedule`
- `Schedule.Timed`
- `Schedule.AllDay`
- `RemindTiming`

### 予定時刻

- 時刻指定予定は `start_time` / `end_time` を使う。
- 終日予定は `start_date` / `end_date` を使う。
- `end_date` は排他境界とする。
- 時刻指定予定と終日予定の列は同時に使わない。

### 差分イベント

- 既存にない予定を取り込んだ場合は `EventScheduled` を発行する。
- 時刻、タイトル、概要、場所が変化した場合は `EventRescheduled` を発行する。
- リマインド間隔が変化した場合は `EventRemindTimingChanged` を発行する。
- 時刻が変化した場合は `EventRemindTimingChanged` も発行する。
- キャンセルまたは窓外退避で削除した場合は `EventCancelled` を発行する。
- 変化がない場合はDB更新もイベント発行もしない。

## destination

### 実装状況

- Exposed のテーブル定義のみ存在する。
- ドメインモデル、ユースケース、リポジトリ、マイグレーションは未実装。

### 責務

- 予定から目的地とルート情報を推定する。
- 同一繰り返しシリーズの予定は、原則として1つの目的地グループを共有する。

### 想定集約・関連

- `EventDestination`
- `EventDestinationLink`

### 共有ルール

- `recurring_event_id` がある予定は、同じ `recurring_event_id` の目的地グループに紐付ける。
- 該当グループがない場合は新規作成する。
- 単発予定は専用の目的地グループを作成する。
- 特定の発生回だけ場所が変わった場合は、シリーズ共有から外して専用グループに紐付ける。

## congestion

### 実装状況

- Exposed のテーブル定義のみ存在する。
- ドメインモデル、ユースケース、リポジトリ、マイグレーションは未実装。

### 責務

- 目的地、日時、外部イベント情報を使って混雑を予測する。
- 混雑予測は予定単位で保持する。

### 想定集約・値

- `EventCongestion`
- `CongestionSource`

## notification

### 実装状況

- Exposed のテーブル定義のみ存在する。
- ドメインモデル、ユースケース、リポジトリ、マイグレーションは未実装。
- `notificate_time` は確定仕様だが、現行の Exposed 定義とマイグレーションへの反映が漏れている。

### 責務

- 予定前リマインドと混雑アラートの通知スケジュールを管理する。
- 到来した通知を FCM で送信する。

### 想定集約

- `NotificationSchedule`

### ステータス

- `pending`
- `processing`
- `completed`
- `failed`
- `canceled`

### 種別

- `Reminder`: 予定前リマインド。
- `CongestionAlert`: 混雑アラート。
