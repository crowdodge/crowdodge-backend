# crowdodge バックエンド アーキテクチャ設計

> 対象: `crowdodge`（チーム「なせばなる」）混雑回避パーソナライズ型予定管理アプリのバックエンド
> ステータス: ドラフト v0.2 / 2026-06-03
> 前提: DDD + イベントストーミング + テーブル定義書（`テーブル定義書テンプレート.xlsx`）をソースに用語・モデルを整合させる

---

## 0. この文書の位置づけ

- 機能一覧・ワンシート企画書・イベントストーミング図・**テーブル定義書**をもとに、Kotlin バックエンドの方針を定める。
- §1〜§4・§10〜§12（技術選定・レイヤー・エラー・トランザクション・永続化）はチームで検討・合意済み。
- §5 用語集 / §6 ドメインモデルは**テーブル定義書の物理名と整合**させた確定版。イベントストーミングの集約/コマンド/イベントとの最終突合のみ残（→ §15）。

---

## 1. 技術スタック（合意済み）

| 用途 | 採用 | 備考 |
|---|---|---|
| 言語 | Kotlin (JVM) / JDK 21 (LTS) 想定 | ※JDKは§15で最終確認 |
| Web フレームワーク | **Ktor** (Netty エンジン) | コルーチンネイティブ |
| ビルド | **Gradle (Kotlin DSL)** + Version Catalog | マルチモジュール |
| DB | **PostgreSQL** + **PostGIS** | `point` 型（自宅・目的地座標）を使うため PostGIS 採用 |
| DB通信 | **R2DBC**（ノンブロッキング） | Ktor コルーチンと一貫 |
| 永続化 | **Exposed (R2DBC)** | `exposed-r2dbc` + `r2dbc-postgresql`。DSL + `suspendTransaction`。ドメインは ORM 非依存（手動マッピング） |
| 接続プール | **r2dbc-pool** | HikariCP は使わない |
| マイグレーション | **Flyway**（JDBC接続で起動時実行） | Flyway は R2DBC 非対応のため JDBC ドライバを併用 |
| DI | **Koin** | BCごとに module 定義、`app` が束ねる |
| エラーハンドリング | **Arrow** (arrow-core) | 内部=`Raise`／境界=`Either`（§10） |
| テスト | **Kotest（runner-junit5）+ MockK** + **Testcontainers**（`kotest-extensions-testcontainers`） | JUnit Platform 上で実行。infra は実 PostgreSQL で結合。spec スタイルは指定なし（自由） |
| JSON | **kotlinx.serialization** | Ktor 公式統合 |
| HTTP クライアント | **Ktor Client** | GCal / Gemini 連携 |
| 通知配信 | **FCM**（Firebase Cloud Messaging） | プッシュ通知。`user_devices.fcm_token` |
| 課金 | **RevenueCat**（将来実装） | `user_subscriptions.rc_original_transaction_id` |
| ログ | **Logback** + slf4j | 構造化ログ |
| 認証 | Ktor Authentication (OAuth2 + JWT/Session) | Google サインイン → 自前セッション |

### 1.1 日時
- 一時点は `kotlin.time.Instant`、日付は `kotlinx.datetime.LocalDate` で表す。
- Google Calendar 投影の一時点は PostgreSQL の `timestamptz` に保存する。
- 業務日付の基準 timezone は `Asia/Tokyo`。
- timezone 変換は `shared/kernel` の `AppTime` に集約する。
- Google Calendar の終日予定は `LocalDate` の半開区間 `[startDate, endDate)` として扱う。
- 終日予定を `Instant` に変換する場合は、`AppTime.businessTimeZone` の日付境界を使う。

---

## 2. アーキテクチャ全体方針

### 2.1 マクロ: モジュラーモノリス
- **単一 Ktor アプリ**だが内部は **1 BC = 1 Gradle モジュール**。BC 間の直接実装依存は禁止。連携は公開イベント / 公開ポート経由のみ。

### 2.2 ミクロ: 各 BC は4層（依存は内向き一方向）

```
            presentation                     infrastructure
        (Ktor route / DTO /              (Exposed-R2DBC / GCal / Gemini /
         Error→Problem)                   FCM / EventBus 実装)
                │                                 │
                └──────────┐         ┌────────────┘
                           ▼         ▼
                       application (UseCase / port interface)
                                 │
                                 ▼
                            domain (集約 / VO / error / event / repository if)
```

- **domain**: 純粋 Kotlin。集約・VO・ドメインエラー・ドメインイベント・リポジトリ interface。Arrow の `Raise`/`Either` 使用可。
- **application**: ユースケース（Interactor、`suspend`）。トランザクション境界。被駆動ポート定義。公開戻り値 `Either<Error, Result>`。
- **presentation**: Ktor ルーティング、DTO↔コマンド変換、`Either`→Problem Details。
- **infrastructure**: Exposed(R2DBC) リポジトリ・外部APIクライアント・FCM・EventBus アダプタ。
- **依存ルール**: `presentation`・`infrastructure` → `application` → `domain` のみ（逆流禁止、DIPで逆転）。

---

## 3. Gradle モジュール構成

```
crowdodge-backend/
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
├── app/                             # Ktor 起動・全モジュール配線・HTTPサーバ
│   └── .../app/{Application.kt, plugins/, db/(R2DBC接続+Flyway(JDBC)), di/AppModule.kt}
├── shared/
│   ├── kernel/                      # UserId, Location, TimeRange, DomainEvent 基底, 共通エラー型, TransactionRunner
│   └── infra/                       # R2DBC基盤, メッセージング基盤, OAuth基盤, Problem(RFC9457)
└── contexts/
    ├── user/          # ユーザー/設定/カレンダー/デバイス/課金
    ├── event/         # 予定（旧 schedule）
    ├── destination/   # 目的地推定
    ├── congestion/    # 混雑情報
    └── notification/  # 通知
```

各 `contexts/<bc>/` 内（4層）:
```
com.crowdodge.<bc>
├── presentation/   # Ktor Route, DTO, Error→Problem
├── application/    # command/(UseCase), query/, port/(被駆動ポート interface)
├── domain/         # model/(集約・エンティティ・VO), error/(sealed), event/(DomainEvent), repository/(interface)
├── infrastructure/ # persistence/(Exposed Table+R2DBCリポジトリ), external/(GCal/Gemini/FCM), messaging/(EventBus)
└── di/<Bc>Module.kt
```

---

## 4. 依存ルール

- `domain`: 依存なし（Kotlin標準 + shared/kernel + arrow-core のみ）。
- `application` → `domain` のみ。**Ktor/Exposed/Koin を import しない**。
- `presentation`・`infrastructure` → `application`・`domain`。両者は互いに依存しない。
- **BC 間のモジュール直接依存は禁止**。連携は (1) 公開ドメインイベント（EventBus）/ (2) 公開クエリポート（ACL経由）。
- `app` だけが全 BC を知り Koin で配線。
- 自動検査: Konsist/ArchUnit で上記を테스트化（§13）。

---

## 5. 用語集（ユビキタス言語）と物理テーブル対応

テーブル定義書の物理名を**正**として、コード上の名前を確定。

| 概念 | コード名 | 種別 | 物理テーブル/列 |
|---|---|---|---|
| ユーザーの予定 | **`Event`** | 集約ルート | `events` / `event_uuid` |
| 繰り返しの例外 | **`EventException`** | Event集約内の内部エンティティ | `event_exceptions` |
| 繰り返しルール | **`Recurrence`**（null=単発） | VO | `rrule` / `recurrence_end` |
| 日時範囲 | **`TimeRange`** | VO | `start_time` / `end_time` |
| リマインド間隔 | **`RemindTiming`**（null=設定/親 参照） | VO | `remind_timing` (interval) |
| 発生回 | **`Occurrence`**（非永続・期間展開） | VO(派生) | — |
| 発生回の識別子 | **`OccurrenceId`** = (`EventId`, `occurrence_start`) | VO | `occurrence_start`（混雑/通知） |
| 目的地（予定に1つ） | **`EventDestination`** | 集約 | `event_destinations` |
| 混雑予測（発生回ごと） | **`EventCongestionPrediction`** | 集約 | `event_congestion_predictions` |
| 外部の混雑原因イベント | **`CongestionSource`**（永続化なし） | VO | — |
| 通知スケジュール | **`NotificationSchedule`** | 集約 | `notification_schedules` |
| 通知ステータス | **`NotificationStatus`** = `pending`/`processing`/`completed`/`failed`/`cancelled` | VO(enum) | `status` |
| 通知種別 | **`NotificationKind`** = `Reminder`(必須)/`CongestionAlert`(任意) | VO(enum) | `is_critical` を置換 |
| ユーザー | **`User`** | 集約ルート | `users` / `user_uuid` |
| ユーザー設定 | **`UserSettings`**（`home`(point), `remind_timing`） | エンティティ | `user_settings` |
| 表示カレンダー | **`UserCalendar`** | エンティティ | `user_calendars` / `google_calendar_id` |
| 通知デバイス | **`UserDevice`**（FCM） | エンティティ | `user_devices` / `fcm_token` |
| 課金（将来実装） | **`UserSubscription`**（RevenueCat） | 集約 | `user_subscriptions` |
| プラン | **`Plan`** = `Free`/`Premium` | VO(enum) | `plan_name` |
| ドメインイベント基底 | **`DomainEvent`** | — | — |

**命名の約束:**
- `Event` は**ユーザーの予定専用**。混雑の原因となる外部イベント（ライブ等）は **`CongestionSource`** と呼ぶ。
- ドメインイベントの基底は**必ず `DomainEvent`**（素の `Event` は使わない）。具体名は業務的な過去形：`EventScheduled` / `EventRescheduled` / `EventCancelled`。

---

## 6. ドメインモデル（集約）

### 6.1 user BC
- **`User`**（`UserId`, googleId, email）
- **`UserSettings`**（`home: Location`, `remindTiming: RemindTiming`）
- **`UserCalendar`**（表示する Google カレンダーID群）
- **`UserDevice`**（FCM トークン）
- **`UserSubscription`**（プラン・status・expires・RevenueCat取引ID）＝**将来実装**
- イベント: `UserRegistered`, `CalendarSelectionChanged` ほか

### 6.2 event BC（予定）
- **`Event`**（集約ルート）
  - `EventId`, `googleEventId?`, `title`, `description?`, `timeRange: TimeRange`, `recurrence: Recurrence?`, `remindTiming: RemindTiming?`
  - `exceptions: List<EventException>`（内部エンティティ。`originalDate`, 変更/中止）
  - 不変条件はルートで保証（例外の `originalDate` は rrule の発生回に対応・重複禁止・start<end）
  - リポジトリは **`EventRepository` のみ**（例外もまとめて永続化）
- **`Occurrence`（発生回）** は**永続化せず**、`rrule` を**期間を区切って**展開し例外を適用して算出（無限繰り返し対策）。識別子は **`OccurrenceId = (EventId, occurrence_start)`**（datetime粒度。変更後でなく**元の発生開始時刻＝RECURRENCE-ID** で識別 → 編集しても安定）。例外は `event_exceptions.original_start` が同概念。展開は `Event` が担い、下流BCは `OccurrenceId` を値で保持。
- ドメインイベント: `EventScheduled` / `EventRescheduled` / `EventCancelled`
- Google Calendar 同期: downstream（`syncToken` 増分取り込み）/ upstream（変更反映、無限ループ防止）

### 6.3 destination BC（目的地推定）
- **`EventDestination`**（`event_uuid` 単位＝**全発生回で同一地点**。`destination`, `destination_point`(point), `route_duration`(interval), `route_information`(json, LLM入力用)）
- ポリシー: `EventScheduled`/`Rescheduled` を購読 → タイトル・概要・場所＋過去予定から推定
- ドメインイベント: `EventDestinationEstimated`

### 6.4 congestion BC（混雑情報）
- **`EventCongestionPrediction`**（**発生回単位**。キーは `OccurrenceId`＝`event_uuid`+`occurrence_start`、`congestion_start_time`/`end_time`, `description`。`event_exception_uuid` は持たず例外は join で取得）
- **`CongestionSource`**（VO）: Gemini から取得した外部の大規模イベント。混雑の根拠として内包
- ポリシー: `EventDestinationEstimated` を購読 → Gemini で対象エリア・日付のイベント取得 → 混雑推定
- ドメインイベント: `EventCongestionPredicted`（支障あり/なし）

### 6.5 notification BC（通知）
- **`NotificationSchedule`**（**発生回単位**。キーは `OccurrenceId`＝`event_uuid`+`occurrence_start`、`notificate_time`, `kind: NotificationKind`, `status: NotificationStatus`）
  - 実質**通知ジョブのキュー**。サーバが常時ポーリングして発火（§9）
- ポリシー: `EventCongestionPredicted`(支障あり) を購読 → 即時通知 / 予定の前日・当日リマインドをスケジュール
- 配信は **FCM**（`UserDevice` のトークン）
- ドメインイベント: `NotificationSent`

---

## 7. コンテキストマップ

```
[user] ──UserId──▶ 全BC

[event] ──EventScheduled/Rescheduled──▶ [destination]
[destination] ──EventDestinationEstimated──▶ [congestion]
[congestion] ──EventCongestionPredicted──▶ [notification] ──(FCM)──▶ ユーザー
                         │
                         └──▶ [event] (予定詳細に混雑予想を表示するための参照)
[notification] ◀──スケジューラ(前日/当日)── 再予測 → [congestion] へ問い合わせ
```
- Customer/Supplier で連なる。外部（GCal/Gemini/FCM/RevenueCat）境界には **ACL** を置く。

---

## 8. 外部連携

- **Google Calendar**: OAuth2。downstream は `syncToken` 増分（準リアルタイムは Push 通知/webhook も可）。upstream は変更反映＋無限ループ防止。競合解決方針は §15。
- **Gemini API**: 大規模イベント取得 → 混雑推定。結果はキャッシュ/永続化。出力スキーマを ACL で固定し `CongestionSource` へマッピング。
- **FCM**: 通知配信。`UserDevice` のトークン管理（失効処理含む）。
- **RevenueCat**（将来）: サブスク状態の取り込み（`rc_original_transaction_id`）。

---

## 9. 非同期処理・イベントバス・通知ジョブ

- BC 間はドメインイベントで連携する（被駆動ポート `DomainEventPublisher`）。
- **配送の実装方式は未確定**（→ §15）。application はポートにのみ依存し、実装は方針決定後に infrastructure へ追加する。
- **通知ジョブ**: `notification_schedules` は `status` が `pending → processing → completed/failed/cancelled` と遷移するキュー。スケジューラが常時ポーリングし、`notificate_time` 到来分を `processing` に確保 → FCM 送信 → `completed`。予定削除等で不要化すれば `cancelled`。
- スケジューラ方式（DBポーリング / Quartz 等）は §15。

---

## 10. エラーハンドリング（合意済み）

### 10.1 ハイブリッド
- 想定内のドメイン失敗 → **型で表現**（呼び出し側に処理を強制）。
- 想定外/インフラ障害 → **例外** → Ktor `StatusPages` で集約。`kotlin.Result` はドメイン用に使わない。

### 10.2 Arrow（内部=`Raise` / 境界=`Either`）
- domain/application 内部は **`Raise<E>`**（`raise`/`ensure`）。公開戻り値だけ **`either { }`** で `Either<E,A>` に確定。
- 既定は fail-fast。入力一括検証は `Raise<NonEmptyList<E>>` + `zipOrAccumulate`/`mapOrAccumulate`。
- `either { }` は inline で `suspend` 併用可（R2DBC の `suspendTransaction` もそのまま）。並列は `arrow-fx-coroutines`。
  ⚠ `either { }` 内で `catch(Throwable)` の握りつぶし禁止（`raise` の脱出を飲む）。回復は `recover`/`catch`。

```kotlin
sealed interface EventError {
    data class InvalidTitle(val reason: String) : EventError
    data object EndBeforeStart : EventError
    data object DuplicateException : EventError
    data class EventNotFound(val id: EventId) : EventError
}

class ScheduleEventUseCase(
    private val tx: TransactionRunner,
    private val repo: EventRepository,
    private val events: DomainEventPublisher,
) {
    suspend fun handle(cmd: ScheduleEventCommand): Either<EventError, EventId> = either {
        val event = buildEvent(cmd)                 // Raise（純粋・tx外）
        tx.inTransaction {                          // トランザクション境界（§11）
            repo.save(event)
            events.publish(EventScheduled(event.id))// ドメインイベント発行（同一tx）
        }
        event.id
    }

    context(Raise<EventError>)
    private fun buildEvent(cmd: ScheduleEventCommand): Event {
        val title = EventTitle.ofOrNull(cmd.title) ?: raise(EventError.InvalidTitle("blank"))
        ensure(cmd.end > cmd.start) { EventError.EndBeforeStart }
        return Event.schedule(cmd.userId, title, TimeRange(cmd.start, cmd.end), cmd.recurrence)
    }
}
```

### 10.3 Web境界: Problem Details (RFC 9457)
`Either` の左→`Problem` 変換は presentation 層に置き `when` で網羅。集約検証は `NonEmptyList` を `errors`/`violations` 配列へ。外部SDK例外は infrastructure で捕捉し境界で変換。

```kotlin
post("/events") {
    when (val r = scheduleEvent.handle(call.receive<ScheduleEventRequest>().toCommand())) {
        is Either.Right -> call.respond(HttpStatusCode.Created, r.value)
        is Either.Left  -> call.respondProblem(r.value.toProblem())
    }
}
fun EventError.toProblem() = when (this) {
    is EventError.InvalidTitle -> Problem(400, "invalid-title", reason)
    EventError.EndBeforeStart  -> Problem(400, "end-before-start")
    EventError.DuplicateException -> Problem(409, "duplicate-exception")
    is EventError.EventNotFound -> Problem(404, "event-not-found")
}
```

---

## 11. トランザクション管理（合意済み）

- **境界 = application のユースケース単位**（1 ユースケース = 1 トランザクション、原則 1 集約の変更）。
- application は Exposed を import しないため、**ポートで逆転**:
  - `shared/kernel` に `interface TransactionRunner { suspend fun <T> inTransaction(block): T /* 書き込み */; suspend fun <T> readOnly(block): T /* 読み取り専用 */ }`
  - infrastructure で `suspendTransaction` を使い実装。
- **純粋ロジック（Raise検証）はトランザクションの外**。失敗なら DB に触れず即 return（ロック保持最小化）。
- リポジトリは自前で `suspendTransaction` を開かず、ユースケースが開いた現在のトランザクションに参加。
- **BC 跨ぎは分散トランザクションをしない＝結果整合性**（各 BC は自分の tx をコミットし、ドメインイベントで次をトリガ）。
- **外部API呼び出し（GCal/Gemini/FCM）はトランザクション内に入れない**。
- R2DBC 注意: トランザクションはコルーチンコンテキストに束縛。tx 内で別ディスパッチャへ飛ばすと引き継がれないため tx 内は基本シーケンシャル。

---

## 12. 永続化方針・命名/正規形（Exposed R2DBC）

- 実行時クエリは `exposed-r2dbc` の **`suspendTransaction { }`**。プールは `r2dbc-pool`。
- マイグレーションは **Flyway を JDBC で起動時実行**（`db/migration/Vxxx__*.sql`）。`org.postgresql:postgresql` も依存に含める。
- BC 境界の表現は**テーブル接頭辞規約**（`user_`/`event_`/`notification_`）。スキーマ分割はしない。
- Exposed の `Table` 定義は infrastructure/persistence に閉じる。ドメインは Exposed 非依存（DSL + 手動マッピング）。
- **テーブル定義書（修正済み）の物理名をそのまま**物理スキーマへ落とす。命名規約:
  - 主キー: `<単数テーブル名>_uuid`（uuid型）。
  - 外部キー列名は親PKと一致（`user_uuid` / `event_uuid` / `event_exception_uuid`）。
  - テーブル・列は snake_case。

```kotlin
class ExposedEventRepository(private val db: R2dbcDatabase) : EventRepository {
    override suspend fun save(event: Event): Unit = suspendTransaction(db) {
        EventTable.upsert { it.fromDomain(event) }
        // exceptions も同一tx内でまとめて upsert（集約単位）
    }
}
```

---

## 13. 横断的関心事

- 依存方向の自動検査（Konsist/ArchUnit）。
- 認証: Google OAuth → 自前 JWT/セッション。`UserId` をコンテキスト注入。
- 設定: `application.conf`(HOCON) + 環境変数。Google/Gemini/FCM/RevenueCat のシークレットは env。
- テスト: **Kotest**（`io.kotest` 群で統一。runner-junit5 / assertions-core / assertions-arrow / property / extensions-testcontainers）。domain=純ユニット（`kotest-property` で VO/不変条件） / application=MockK でポートをモック / infrastructure=Testcontainers（`kotest-extensions-testcontainers`。`exposed-r2dbc` の挙動差も早期検証）。`Either` は `kotest-assertions-arrow` の `shouldBeRight()`/`shouldBeLeft()` で検証。spec スタイルは指定なし（自由）。
- ロギング: 構造化ログ + リクエストID/UserId の MDC。

---

## 14. 段階的実装ロードマップ

1. **基盤**: マルチモジュール雛形 / `app` 起動 / R2DBC+r2dbc-pool / Flyway(JDBC) / Koin / StatusPages+Problem / ヘルスチェック。`.gitignore` を Go 用 → Kotlin(Gradle) 用へ。
2. **user + 認証**: Google サインイン → セッション。UserSettings / UserCalendar / UserDevice。
3. **event（コア）**: 予定 CRUD（繰り返し+例外含む）+ GCal 同期。
4. **destination**: `EventScheduled` 購読 → 目的地推定（初期はルールベース）。
5. **congestion**: `EventDestinationEstimated` 購読 → Gemini → 混雑予測。
6. **notification**: 即時通知 + 前日/当日リマインド（スケジューラ + FCM）。
7. **マネタイズ**（UserSubscription/RevenueCat、広告非表示、ノイズ修正申請）。

---

## 15. 未決事項 / チームで確認

1. **イベントストーミングとの最終突合**（集約/コマンド/イベント/ポリシーの名称・粒度）。
2. **Entitlement（機能ゲーティング）**: 課金実装時に 1 ポートへ集約（今は作らない＝YAGNI）。
3. GCal **同期方式**（増分/Push）と競合解決ルール。
4. **目的地推定**の初期実装（ルールベース/ML/LLM）。
5. 「行動追跡」を独立 BC にするか `destination` 内に置くか。
6. **スケジューラ方式**（DBポーリング/Quartz/外部基盤）。
7. **JDK バージョン**、`context parameters`（Kotlin 2.2）対応に伴う `Raise` 記述スタイル。
8. フロントとの API 契約（REST/認証）。
