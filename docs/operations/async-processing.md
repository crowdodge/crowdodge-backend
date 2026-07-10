# 非同期処理と通知ジョブ

## 更新対象

- イベントバス、ジョブスケジューラ、通知キューの仕様を変更した場合に更新する。

## ドメインイベント

- コンテキスト間連携はドメインイベントで行う。
- `application` は `DomainEventPublisher` ポートにだけ依存する。
- `DomainEventPublisher` はExposed R2DBCのtransactionへcommit後コールバックを登録する。
- commit成功後にin-processで対応する `DomainEventHandler` を実行する。
- rollback時は配送しない。
- Handler失敗はログへ記録し、確定済みDB更新は戻さない。
- `appModule` で `TransactionalInProcessDomainEventPublisher` を配線する。

## 通知ジョブ

- `notification_schedules` の `notificate_time` が到来した `pending` 行を対象にする。
- 実行時は対象行を `processing` に確保する（Job は同時実行 1 のため行ロック排他はしない）。
- FCM 送信成功時は `completed`、失敗時は `failed` に更新する。リトライはしない。
- 予定削除などで不要になった通知、および混雑情報が取得できない `CongestionAlert` は `canceled` に更新する。
- 実行の詳細は [通知送信ジョブ](notification-dispatch.md) を参照する。

## スケジューラ

- Cloud Scheduler が 5 分間隔で Cloud Run Job（`NotificationDispatchMain`）を起動する。
- 通知は最大約 5 分遅延する。
