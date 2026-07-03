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

- 通知ジョブは未実装。
- `notification_schedules` の Exposed 定義は存在するが、マイグレーションには未反映。
- 通知キューは `notificate_time` が到来した `pending` 行を対象にする。
- 実行時は対象行を `processing` に確保する。
- FCM 送信成功時は `completed` に更新する。
- FCM 送信失敗時は `failed` に更新する。
- 予定削除などで不要になった通知は `canceled` に更新する。
- 現行の Exposed 定義とマイグレーションには `notificate_time` 列がなく、実装漏れとして扱う。
- 取得ロック、リトライ、失効処理は未実装。

## スケジューラ

- スケジューラ方式は未決とする。
- 候補は DBポーリング、Quartz、外部ジョブ基盤とする。
