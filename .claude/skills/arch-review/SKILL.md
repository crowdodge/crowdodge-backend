---
name: arch-review
description: crowdodge バックエンドの差分/コードを、docs/architecture.md の規約(4層の依存方向・Arrow のエラーハンドリング・トランザクション境界・命名規約・BC 連携)に特化してレビューする。汎用の /code-review とは別に、このプロジェクト固有のアーキテクチャ規約逸脱を検出したいときに使う。「規約に沿ってる?」「レビューして」「依存方向おかしくない?」など。
---

# アーキ規約レビュー (arch-review)

crowdodge の**プロジェクト固有規約**(`docs/architecture.md`)への適合をレビューする役割。バグ全般や汎用品質は組込みの `/code-review` に任せ、ここは**アーキテクチャ規約の逸脱**に集中する。

## レビュー対象を取得

```bash
cd server
git diff develop...HEAD          # ブランチの差分
./gradlew :konsist:test          # 依存方向の機械検査(まず通す)
```

Konsist が拾えるのは構造的な依存違反のみ。**以下は人(このSkill)が読んで判断する。**

## チェックリスト(architecture.md 準拠)

### 1. 層と依存方向(§2, §4)
- [ ] `domain` が Kotlin 標準 + `shared/kernel` + `arrow-core` 以外に依存していないか。
- [ ] **`application` が Ktor / Exposed / Koin を import していないか**(最頻出の違反)。
- [ ] `presentation` と `infrastructure` が互いに依存していないか。
- [ ] 依存が内向き一方向(逆流は DIP で逆転)になっているか。

### 2. BC 連携(§4, §7)
- [ ] **BC 間のモジュール直接依存がないか**。連携はドメインイベント or 公開クエリポート(ACL)のみ。
- [ ] 上流のドメインイベント名が業務的過去形(`EventScheduled` 等)で、基底が `DomainEvent` か(素の `Event` を使っていないか)。

### 3. エラーハンドリング(§10)
- [ ] 想定内のドメイン失敗が**型**(`Raise`/`Either`)で表現され、例外で握られていないか。`kotlin.Result` をドメインに使っていないか。
- [ ] **`either { }` 内で `catch(Throwable)` の握りつぶしがないか**(`raise` の脱出を飲む重大バグ)。回復は `recover`/`catch`。
- [ ] 公開戻り値だけ `Either` に確定し、内部は `Raise` を使っているか。
- [ ] presentation で `Either` の左を `when` で**網羅**して Problem(RFC9457)へ変換しているか(抜けは 500 化の温床)。

### 4. トランザクション(§11)
- [ ] tx 境界が application のユースケース単位(原則 1 集約)になっているか。
- [ ] **純粋ロジック(Raise 検証)が tx の外**にあるか(失敗時に DB に触れない)。
- [ ] **外部 API(GCal/Gemini/FCM)を tx 内で呼んでいないか**。
- [ ] リポジトリが自前で `suspendTransaction` を開かず、UseCase の tx に参加しているか。
- [ ] BC 跨ぎで分散 tx をしていないか(結果整合性 + イベント)。

### 5. 永続化・命名(§12)
- [ ] Exposed の `Table` 定義が `infrastructure/persistence` に閉じているか(ドメインに漏れていないか)。
- [ ] `exposed-r2dbc` の `suspendTransaction` を使い、JDBC 用 API を実行時クエリに使っていないか。
- [ ] テーブル/列が snake_case、主キー `<単数>_uuid`、外部キー列名が親 PK と一致しているか。

### 6. 集約・不変条件(§6)
- [ ] 不変条件が集約ルートで保証されているか(例: `Event` の例外の `originalDate` 整合・start<end)。
- [ ] 発生回(`Occurrence`)を**永続化していないか**(rrule 展開で算出する設計)。

## 出力

- 違反を **重大度(規約違反 / 要改善 / 指摘)** で分類し、`file:line` と該当する architecture.md の節番号を添える。
- 修正方針を一行で(「コードでなく層配置を直す」等)。直すなら [coding](../coding/SKILL.md) へ。
- 規約自体に無理がある/未決(§15)に当たる場合は [design](../design/SKILL.md) で憲法側の更新を検討。
