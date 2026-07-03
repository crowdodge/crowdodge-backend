# 実装ロードマップ

## 更新対象

- 実装順序、マイルストーン、対象機能を変更した場合に更新する。

1. 基盤
   - 状態: 実装済み
   - マルチモジュール構成
   - Ktor 起動
   - R2DBC / r2dbc-pool
   - Flyway
   - Koin
   - Problem Details
   - ヘルスチェック
2. user
   - 状態: 一部実装済み
   - 実装済み: ドメインモデル、リポジトリ、テーブル、マイグレーション、Google サインイン、認証 presentation API
   - 未実装: Google Calendar API プロキシ、カレンダー一覧取得、選択更新
3. event
   - 状態: 一部実装済み
   - 実装済み: 予定投影モデル、同期ユースケース、リポジトリ、テーブル、マイグレーション
   - 未実装: Google Calendar API クライアント、watch登録ジョブ、presentation API
4. destination
   - 状態: 未実装
   - Exposed テーブル定義のみ存在する
   - マイグレーション未反映
5. congestion
   - 状態: 未実装
   - Exposed テーブル定義のみ存在する
   - マイグレーション未反映
6. notification
   - 状態: 未実装
   - Exposed テーブル定義のみ存在する
   - マイグレーション未反映
   - `notificate_time` は確定仕様だが、現行の Exposed 定義とマイグレーションへの反映が漏れている
7. 課金
   - 状態: 未実装
   - RevenueCat 連携
   - Entitlement
