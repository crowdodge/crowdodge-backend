# 技術スタック

## 更新対象

- 言語、フレームワーク、DB、ライブラリ、外部サービスを変更した場合に更新する。
- バージョン管理は `server/gradle/libs.versions.toml` を正とする。

## 採用技術

| 用途 | 採用 | 状態 |
|---|---|---|
| 言語 | Kotlin 2.4.0 / JDK 21 | 導入済み |
| Web フレームワーク | Ktor 3.5.0 / Netty | 導入済み |
| ビルド | Gradle Kotlin DSL / Version Catalog | 導入済み |
| DB | PostgreSQL + PostGIS | 導入済み |
| DB通信 | R2DBC | 導入済み |
| 永続化 | Exposed 1.3.0 R2DBC | 導入済み |
| 接続プール | r2dbc-pool 1.0.2.RELEASE | 導入済み |
| マイグレーション | Flyway 12.8.1。Flyway 実行時の接続は JDBC | 導入済み |
| DI | Koin 4.2.1 | 導入済み |
| エラーハンドリング | Arrow Core 2.2.3 | 導入済み |
| テスト | Kotest 6.1.11 / Testcontainers 2.0.5 | 導入済み |
| テストダブル | MockK 1.14.11 | Version Catalog 定義のみ |
| JSON | kotlinx.serialization 1.11.0 | 導入済み |
| HTTP クライアント | Ktor Client | 未導入 |
| 通知 | Firebase Cloud Messaging | 未導入 |
| 課金 | RevenueCat | 未導入 |
| ログ | Logback 1.5.34 / slf4j | 導入済み |
| 認証 | Google OAuth2 + 自前セッションまたは JWT | 未実装 |

## 日時

- 一時点は `kotlin.time.Instant` で表す。
- 日付は `kotlinx.datetime.LocalDate` で表す。
- DB の一時点は `timestamptz` に保存する。
- 業務日付の基準タイムゾーンは `Asia/Tokyo` とする。
- タイムゾーン変換は `shared/kernel` の `AppTime` に集約する。
- Google カレンダーの終日予定は `LocalDate` の半開区間 `[startDate, endDate)` として扱う。
- 終日予定を `Instant` に変換する場合は、業務タイムゾーンの日付境界を使う。
