# Google Calendar同期のトランザクション境界

## 目的

Google Calendarのwatch整合JobとWebhook処理において、ExposedのDB操作を必ず
`TransactionRunner`配下で実行する。外部API呼び出し中はDBトランザクションを保持せず、
同時に成功・失敗する必要があるDB更新だけを同じトランザクションへまとめる。

## 境界

### カレンダー追加

1. Google APIでwatchを作成する。
2. `event_calendar_syncs`への状態保存を1つの書き込みトランザクションで行う。
3. User BCの選択保存はUser BC側のトランザクションで行う。
4. Event、`sync_token`、`materialized_until`の保存を同期処理側の1つの書き込みトランザクションで行う。

状態保存に失敗した場合は作成済みwatchを停止する。User BCの選択保存に失敗した場合は、
作成済みwatchの停止と該当する同期状態の削除で補償する。

### カレンダー解除と孤立状態削除

1. 同期状態を読み取り専用トランザクションで取得する。
2. Google APIでwatchを停止する。
3. 対象Eventの全削除と`event_calendar_syncs`の削除を同じ書き込みトランザクションで行う。

DB削除の途中で失敗した場合は削除全体をロールバックし、次回の整合処理で再試行できる状態を残す。

### watch更新

1. 同期状態一覧を読み取り専用トランザクションで取得する。
2. Google APIで新しいwatchを作成する。
3. Google APIからEventを取得する。
4. Event、`sync_token`、`materialized_until`を同期処理側の同じ書き込みトランザクションで保存する。
5. watch情報を別の書き込みトランザクションで置換する。
6. 置換成功時は古いwatch、失敗時は新しいwatchを停止する。

sync tokenはカレンダー単位でありwatch channelには依存しない。watch情報の置換に失敗しても、
同期済みEventは有効で、古いwatchを継続利用できるため、この2つの保存は分離する。

### Webhook

1. channel IDによる同期状態検索を読み取り専用トランザクションで行う。
2. tokenを検証する。
3. Google APIから差分Eventを取得する。
4. Eventと`sync_token`を同期処理側の同じ書き込みトランザクションで保存する。

## 実装

- `GoogleCalendarSyncLifecycleService`へ`TransactionRunner`を注入する。
- `HandleGoogleCalendarWebhookUseCase`へ`TransactionRunner`を注入する。
- `EventModule`から既存の`TransactionRunner`を渡す。
- `ExposedEventCalendarSyncDataSource`はトランザクションを開始せず、現在の責務を維持する。
- Google API呼び出しがトランザクション外であることもテストする。

## 検証

- 新規テストは、Jobの状態一覧取得、解除時のEvent・同期状態削除、Webhookの状態検索の3点に絞る。
- トランザクション外のPort呼び出しを拒否するテスト用Runnerで、今回の回帰を再現する。
- 既存テストでwatch作成・更新・補償処理の振る舞いを引き続き検証する。
- Event BCテスト、Appテスト、detekt、ビルドを実行する。
