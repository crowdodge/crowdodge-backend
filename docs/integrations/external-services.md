# 外部サービス

## 更新対象

- 外部サービス、認証方式、責務、ACLを変更した場合に更新する。

## Google Calendar

- 用途は予定の読み取り、予定の書き込み、変更検知とする。
- Google Calendar API クライアント本体は未実装。
- OAuth2 認証処理は未実装。
- 詳細仕様は [Google カレンダー同期](google-calendar-sync.md) に従う。

## Gemini API

- 用途は大規模イベント情報の取得と混雑推定とする。
- 外部レスポンスは ACL で固定スキーマへ変換する。
- ドメイン内では `CongestionSource` として扱う。
- Gemini API クライアントは未実装。
- 結果のキャッシュまたは永続化方針は未決とする。

## FCM

- 用途はプッシュ通知配信とする。
- 送信先は `user_devices.fcm_token` で管理する。
- FCM 送信処理は未実装。
- 無効な FCM トークンの失効処理は未実装。

## RevenueCat

- 用途はサブスクリプション状態の取得とする。
- RevenueCat 連携は未実装。
- 取引識別子の保存先として `user_subscriptions.rc_original_transaction_id` を定義済み。
