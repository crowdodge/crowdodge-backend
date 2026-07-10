# レイヤーと依存ルール

## 更新対象

- パッケージ構成、依存方向、レイヤー責務を変更した場合に更新する。
- 自動検査を変更した場合は [テスト方針](testing.md) も更新する。

## レイヤー

```text
presentation   infrastructure
      │             │
      └──────┬──────┘
             ▼
       application
             │
             ▼
          domain
```

| レイヤー | 責務 |
|---|---|
| `domain` | 集約、エンティティ、VO、ドメインエラー、ドメインイベント、リポジトリ interface |
| `application` | ユースケース、トランザクション境界、被駆動ポート、公開戻り値 |
| `presentation` | Ktor ルート、DTO変換、HTTPエラー変換 |
| `infrastructure` | Exposed R2DBC、DBアダプタ、外部API、FCM、Domain Event配送 |

## 依存ルール

- `domain` は Kotlin 標準、`shared/kernel`、Arrow Core のみへ依存できる。
- `application` は `domain` と `shared/kernel` のみへ依存できる。
- `application` は Ktor、Exposed、Koin を import しない。
- `presentation` と `infrastructure` は `application` と `domain` へ依存できる。
- `presentation` と `infrastructure` は互いに依存しない。
- コンテキスト間の実装直接依存は禁止する。
- コンテキスト間連携は公開ドメインイベントまたは公開ポートで行う。
- `readmodel` モジュールは BC 横断の読み取り専用クエリを提供する。contexts の `infrastructure.persistence` の Table 定義と application の公開ポートのみ import でき、書き込みは行わない。contexts は readmodel に依存しない。

## 現行実装

- `infrastructure` の実装済み範囲は R2DBC、Exposed テーブル、DBリポジトリ、readiness probe、Domain Event配送が中心。
- Google OAuth、Google Calendar、FCMの外部APIクライアントは実装済み。
- `appModule` は DB接続、`TransactionRunner`、`ReadinessProbe`、`DomainEventPublisher`、BC間ACLを配線している。
