# コンテキスト間連携

## 更新対象

- コンテキスト間イベント、公開ポート、処理順序を変更した場合に更新する。

## 連携ルール

- コンテキスト間の実装直接依存は禁止する。
- 連携はドメインイベントまたは公開ポートで行う。
- コンテキストをまたぐ更新は結果整合性で扱う。
- 外部API境界には ACL を置く。
- `DomainEventPublisher` はcommit後のin-process配送として実装済み。
- `appModule` は `DomainEventPublisher` を配線する。

## 実装済みイベント型

以下は型定義として実装済み。

```text
user
  ├─ UserRegistered
  ├─ CalendarSelectionChanged
  └─ CalendarInitialSyncRequested

event
  ├─ EventScheduled
  ├─ EventRescheduled
  ├─ EventRemindTimingChanged
  └─ EventCancelled
```

`CalendarInitialSyncRequested` はカレンダー選択確定時に追加対象ごとに発行する。
app 層の handler が user BC の `UserCalendarUuid` を event BC の同名値へ変換し、初回同期を実行する。

## 未実装の想定連鎖

```text
event
  └─ destination
      └─ congestion
          └─ notification
              └─ FCM
```

## 参照関係

| 参照元 | 参照先 | 用途 |
|---|---|---|
| app | user / event | カレンダー選択更新、watch登録、初回同期、解除処理の協調 |
| event | user | カレンダーIDと有効なaccess tokenの取得 |
| destination | event | 予定内容と場所情報の参照 |
| congestion | destination | 目的地とルート情報の参照 |
| notification | user | FCMトークンの取得 |
| notification | event | 通知対象予定の参照 |
