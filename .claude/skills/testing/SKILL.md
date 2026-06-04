---
name: testing
description: crowdodge バックエンドのテストを、層ごとに異なる戦略(domain=純ユニット / application=MockK でポートをモック / infrastructure=Testcontainers で実 PostgreSQL)で書く。Arrow の Either は isRight()/isLeft() で検証。「テストを書いて」「このユースケースのテスト」「リポジトリの結合テスト」などテスト作成/補強の依頼で使う。
---

# テスト (testing)

crowdodge バックエンドのテスト役。**層ごとにテスト戦略が違う**(architecture.md §13)。対象の層を見極めてから書く。

## 層別の戦略(§13)

| 層 | 種別 | 道具 | 方針 |
|---|---|---|---|
| `domain` | 純ユニット | JUnit5 のみ | 集約の不変条件・VO・`Raise` 検証ロジック。依存なしで高速に |
| `application` | ユニット | **MockK** | 被駆動ポート(リポジトリ / `DomainEventPublisher` / `TransactionRunner`)をモックし、UseCase の分岐を検証 |
| `infrastructure` | 結合 | **Testcontainers**(実 PostgreSQL) | Exposed R2DBC リポジトリ・マッピングを実 DB で。`exposed-r2dbc` の挙動差を早期検出 |
| `presentation` | 結合 | `ktor-server-test-host` | Route / DTO 変換 / Either→Problem(RFC9457)の応答を検証 |

採用バージョン: JUnit5(`junit-jupiter`)/ MockK 1.14.5 / Testcontainers 1.21.3 / `kotlin-test-junit5`。詳細は [server/gradle/libs.versions.toml](../../../server/gradle/libs.versions.toml)、不明点は [lib-research](../lib-research/SKILL.md)。

## 書き方の指針

### Either / Raise の検証(§10)
- 公開 UseCase の戻り値 `Either<E, A>` は **`isRight()` / `isLeft()`** で分岐確認し、`fold` か `shouldBe` で中身まで検証する。
- 成功ケースだけでなく、**sealed なドメインエラーの各ケース**(`InvalidTitle`/`EndBeforeStart`/`DuplicateException`/`NotFound` 等)を網羅。
- 入力一括検証(`NonEmptyList`)は、複数違反がまとめて返ることを確認。

### application(MockK)
- `TransactionRunner` はモックして `inTransaction`/`readOnly` のブロックをそのまま実行させる(tx 境界はモックでも block を評価)。
- リポジトリ・`DomainEventPublisher` の呼び出し回数/引数を `verify` で確認(例: 成功時に `EventScheduled` が発行されるか)。
- **純粋検証は tx の外**という設計(§11)を、tx 失敗前に DB 未到達であることで確認できる。

### infrastructure(Testcontainers)
- 実 PostgreSQL + PostGIS コンテナを起動(`point` 型や `interval` を使うため PostGIS イメージ)。
- Flyway を当ててスキーマを用意してから `suspendTransaction` でリポジトリを叩く。
- ドメイン ⇔ テーブルの手動マッピング(往復で等価)を検証。

## 実行

```bash
cd server
./gradlew test            # 全モジュールのテスト
./gradlew :app:test       # モジュール単位
./gradlew build           # テスト込みのフルビルド
```

- Testcontainers は Docker が必要。CI/ローカルで Docker 未起動なら infra テストはスキップ条件を明示する(暗黙に通った扱いにしない)。

## 関連スキル

実装は [coding](../coding/SKILL.md)、規約レビューは [arch-review](../arch-review/SKILL.md)。
