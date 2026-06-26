# テスト方針

## 更新対象

- テストフレームワーク、テスト分類、自動検査ルールを変更した場合に更新する。

## 採用

- テストランナーは Kotest を使う。
- テストダブルは対象に応じて fake / stub / MockK から選択する。
- DB結合テストは Testcontainers を使う。
- Arrow の `Either` 検証は `kotest-assertions-arrow` を使う。

## 分類

| 対象 | 方針 |
|---|---|
| `domain` | 純粋ユニットテスト。VOと不変条件を検証する |
| `application` | ポートをテストダブルで置き換え、ユースケースを検証する |
| `infrastructure` | Testcontainers の PostgreSQL で永続化挙動を検証する |
| アーキテクチャ | Konsist で依存方向を検証する |

## ルール

- spec スタイルは固定しない。
- レイヤー依存違反はテストで検出する。
- DB永続化の仕様変更には、可能な限り Testcontainers の検証を追加する。
