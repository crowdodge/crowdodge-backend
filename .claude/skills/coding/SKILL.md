---
name: coding
description: crowdodge バックエンドの Kotlin 実装を、docs/architecture.md の規約(4層依存・Arrow Raise/Either・TransactionRunner・Exposed R2DBC・命名規約・テスト方針)に厳密に従って書く。「実装して」「このユースケースを作って」「リポジトリを追加して」など、実際にコードを書く/直す依頼で使う(テスト作成は testing に委ねる)。書いたら Konsist と build で必ず検証する。
---

# コーディング (coding)

crowdodge バックエンドの実装役。**`docs/architecture.md` の規約を守って Kotlin を書く**。書きっぱなしにせず Konsist と build で検証する。

## 着手前

1. 対象が未設計なら先に [design](../design/SKILL.md) で層配置・集約・エラー型を確定する。
2. 使うライブラリ API に不安があれば [lib-research](../lib-research/SKILL.md) で固定バージョンの正しい書き方を確認する。
3. 既存コードの書き方に合わせる。`server/shared/kernel`・`server/shared/infra`・`server/app` の既存実装をまず読む。

## 守るべき規約(architecture.md より)

### 層と依存方向(§2, §4)
- `presentation`・`infrastructure` → `application` → `domain`(内向き一方向)。
- **`domain`**: 純粋 Kotlin。Kotlin 標準 + `shared/kernel` + `arrow-core` のみ。集約・VO・sealed ドメインエラー・`DomainEvent`・リポジトリ interface。
- **`application`**: UseCase(`suspend`)。**Ktor / Exposed / Koin を import しない**。被駆動ポート interface を定義。公開戻り値は `Either<Error, Result>`。
- **`presentation`**: Ktor Route、DTO↔コマンド変換、`Either`→Problem(RFC 9457)。
- **`infrastructure`**: Exposed(R2DBC) リポジトリ、外部 API クライアント、EventBus アダプタ。
- BC 間のモジュール直接依存は禁止。連携はドメインイベント or 公開クエリポート(ACL)。

### エラーハンドリング(§10)
- 想定内のドメイン失敗は **型**(Arrow `Raise<E>` / 公開境界で `either { }` → `Either<E, A>`)。想定外/インフラ障害は**例外** → Ktor `StatusPages`。`kotlin.Result` はドメインに使わない。
- domain/application 内部は `Raise<E>`(`raise`/`ensure`)、公開戻り値だけ `either { }` で確定。
- ⚠ `either { }` 内で `catch(Throwable)` の握りつぶし禁止(`raise` の脱出を飲む)。回復は `recover`/`catch`。
- 入力一括検証は `Raise<NonEmptyList<E>>` + `zipOrAccumulate`/`mapOrAccumulate`。
- presentation で `when` 網羅して `Problem` へ変換(左の全ケースを潰す)。

### トランザクション(§11)
- 境界 = application のユースケース単位(1 UseCase = 1 tx、原則 1 集約)。
- application は Exposed を import しないので **`shared/kernel` の `TransactionRunner` ポートで逆転**。実装は infrastructure で `suspendTransaction`。
- **純粋ロジック(Raise 検証)は tx の外**。失敗なら DB に触れず即 return。
- 外部 API 呼び出し(GCal/Gemini/FCM)は **tx 内に入れない**。BC 跨ぎは分散 tx せず結果整合性。

### 永続化・命名(§12)
- 実行時クエリは `exposed-r2dbc` の `suspendTransaction { }`。プールは `r2dbc-pool`(HikariCP 不使用)。
- Exposed の `Table` 定義は `infrastructure/persistence` に閉じる。ドメインは Exposed 非依存(DSL + 手動マッピング)。
- マイグレーションは Flyway(JDBC、`db/migration/Vxxx__*.sql`)。
- 命名: テーブル/列は snake_case。主キー `<単数テーブル名>_uuid`、外部キー列名は親 PK と一致。

### テスト(§13)
- domain = 純ユニット。application = MockK でポートをモック。infrastructure = Testcontainers(実 PostgreSQL)。
- `Either` は `isRight()`/`isLeft()` で分岐確認。

## パッケージ配置

```
com.crowdodge.<bc>
├── presentation/   # Route, DTO, Error→Problem
├── application/    # command/(UseCase), query/, port/(被駆動ポート interface)
├── domain/         # model/, error/(sealed), event/(DomainEvent), repository/(interface)
├── infrastructure/ # persistence/(Table+R2DBCリポジトリ), external/, messaging/
└── di/<Bc>Module.kt
```

## 実装後に必ず検証する

```bash
cd server
./gradlew :konsist:test    # 依存方向の自動検査(§13)。違反したら層配置を直す
./gradlew build            # ビルド + ユニットテスト
```

- Konsist 違反(application が Ktor/Exposed を import 等)は設計ミス。コードでなく配置を直す。
- 新しい用語/モデルを足したら [design](../design/SKILL.md) に戻って `docs/architecture.md` 更新も検討。

## 関連スキル(推奨フロー)

[design](../design/SKILL.md)(設計) → [lib-research](../lib-research/SKILL.md)(仕様確認) → `coding`(ここ。実装 + Konsist/build 検証) → [testing](../testing/SKILL.md)(テスト作成) / [arch-review](../arch-review/SKILL.md)(規約レビュー)。
