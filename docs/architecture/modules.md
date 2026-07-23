# モジュール構成

## 更新対象

- Gradle モジュール、モジュールの責務、依存方向、ルート構成、コンテキスト内パッケージ構成を追加、削除、移動、変更した場合に更新する。

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
├── readmodel/
└── konsist/
```

## readmodel モジュール

- BC横断の読み取り専用クエリを提供する。

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

## shared モジュール

| モジュール | 役割 |
|---|---|
| `shared/kernel` | 共通VO、`DomainEvent`、共通エラー、`TransactionRunner` |
| `shared/infra` | R2DBC基盤、Problem Details、DB readiness probe、Domain Event配送、Gemini Interactions API クライアント基盤 |
