# モジュール構成

## 更新対象

- Gradle モジュールを追加、削除、移動した場合に更新する。
- 依存方向を変更する場合は [レイヤーと依存ルール](layers.md) も更新する。

## 方針

- バックエンドは単一 Ktor アプリケーションとして起動する。
- 内部構造はモジュラーモノリスとする。
- 1つの境界づけられたコンテキストを1つの Gradle モジュールに対応させる。
- `app` のみが全コンテキストを知り、Koin で配線する。

## ルート構成

```text
server/
├── app/
├── shared/
│   ├── kernel/
│   └── infra/
├── contexts/
│   ├── user/
│   ├── event/
│   ├── destination/
│   ├── congestion/
│   └── notification/
└── konsist/
```

## 各コンテキストの内部構成

```text
contexts/<context>/
└── src/main/kotlin/com/crowdodge/<context>/
    ├── presentation/
    ├── application/
    ├── domain/
    ├── infrastructure/
    └── di/
```

## 現行の注意点

- `contexts/destination` の現行パッケージ名は `com.crowdodge.distination`。
- `contexts/notification` の現行パッケージ名は `com.crowdodge.infrastrcuture.persistence`。
- 上記は現行コードの状態であり、修正する場合はコードとドキュメントを同時に更新する。

## shared モジュール

| モジュール | 役割 |
|---|---|
| `shared/kernel` | 共通VO、`DomainEvent`、共通エラー、`TransactionRunner` |
| `shared/infra` | R2DBC基盤、Problem Details、DB readiness probe、Domain Event配送 |
