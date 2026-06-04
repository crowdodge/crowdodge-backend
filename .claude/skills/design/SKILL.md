---
name: design
description: crowdodge バックエンドの設計を行う。新しい BC・集約・ユースケース・ドメインイベントを追加/変更する前に、DDD + モジュラーモノリス + 4層アーキテクチャに沿って設計を組み立て、docs/architecture.md との整合を取る。「設計して」「どの BC に置く?」「集約をどう切る?」「このモデルでいい?」といった設計判断の依頼で使う。
---

# 設計 (design)

crowdodge バックエンドの設計を、**`docs/architecture.md` を唯一の正(ソース・オブ・トゥルース)** として組み立てる役割。コードを書く前の「どこに・何を・どう置くか」を確定させる。

## 最初に必ず読む

1. [docs/architecture.md](../../../docs/architecture.md) — 設計憲法。特に以下。
   - §2 4層(domain / application / presentation / infrastructure、依存は内向き一方向)
   - §4 依存ルール(BC 間のモジュール直接依存は禁止。連携はイベント or 公開クエリポートのみ)
   - §5 用語集(ユビキタス言語 ⇔ 物理テーブル名)
   - §6 ドメインモデル(集約境界)
   - §7 コンテキストマップ(BC 間の連携方向)
2. [docs/architecture.md §15](../../../docs/architecture.md) の **未決事項** — ここに該当する論点は勝手に確定させず、選択肢を提示して合意を促す。

## 設計の進め方

1. **BC を特定する** — user / event / destination / congestion / notification のどれに属するか。§7 のコンテキストマップで上流・下流を確認。またがるなら ACL かドメインイベント連携を検討(直接依存は禁止)。
2. **用語を §5 と整合させる** — 新概念は物理テーブル名(テーブル定義書)と突き合わせ、ユビキタス言語に追加。`Event` は「ユーザーの予定」専用、外部の混雑原因は `CongestionSource`、ドメインイベント基底は必ず `DomainEvent`(素の `Event` を使わない)。
3. **集約境界を決める** — 不変条件を保証する単位 = 1 集約 = 1 リポジトリ = 1 トランザクション(§11)。例: `Event` は `EventException` を内包し `EventRepository` のみで永続化。発生回 `Occurrence` は**非永続**(rrule 展開)。
4. **層に振り分ける**:
   - domain: 集約 / VO / sealed なドメインエラー / DomainEvent / リポジトリ interface
   - application: UseCase(suspend)、被駆動ポート interface、トランザクション境界、戻り値 `Either<Error, Result>`
   - presentation: Route / DTO / Either→Problem
   - infrastructure: Exposed テーブル+リポジトリ実装 / 外部 API / EventBus アダプタ
5. **エラー型を sealed interface で設計**(§10)。想定内のドメイン失敗は型(Arrow `Raise`/`Either`)、想定外は例外。各エラーの HTTP マッピング(Problem Details, §10.3)も決める。
6. **BC 連携を設計** — 上流が発行するドメインイベント名(業務的過去形: `EventScheduled` 等)と、下流が購読するポリシーを明示。
7. **依存ルール違反がないか自己点検**(§4)。Konsist で検査される観点(application が Ktor/Exposed/Koin を import しない 等)を設計段階で潰す。

## 成果物

- 設計メモ(対象 BC / 集約・VO・エラー・イベントの一覧 / 層配置 / 連携方向 / トランザクション境界)。
- 用語やモデルが増えたら **`docs/architecture.md` への追記提案**(§5 表・§6・§7)。憲法を更新せず散らかさない。
- §15 に触れる論点は「未決」として選択肢と推奨を提示。

## アンチパターン(設計段階で止める)

- BC をまたいでモジュールを直接依存させる(→ イベント / 公開ポート)。
- 1 トランザクションで複数集約を更新する(→ 結果整合性 + ドメインイベント)。
- 発生回(Occurrence)を永続化する(→ rrule 展開 + 例外適用で算出)。
- `Event` を混雑原因イベントの意味で使う。

## 関連スキル(推奨フロー)

`design`(ここ) → 必要なら [lib-research](../lib-research/SKILL.md)(採用ライブラリで実現可能か仕様確認) → [coding](../coding/SKILL.md)(規約準拠で実装)。
