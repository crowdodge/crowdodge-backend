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
  └─ CalendarSelectionChanged

event
  ├─ EventScheduled
  ├─ EventRescheduled
  ├─ EventRemindTimingChanged
  └─ EventCancelled
```

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
| event | user | カレンダー所有者の判定 |
| destination | event | 予定内容と場所情報の参照 |
| congestion | destination | 目的地とルート情報の参照 |
| notification | user | FCMトークンの取得 |
| notification | event | 通知対象予定の参照 |
