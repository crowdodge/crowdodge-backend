# crowdodge バックエンド技術仕様

このディレクトリは、バックエンド実装で参照する技術仕様を扱う。

## 更新ルール

- 仕様を変更した場合は、変更対象の実装と同じ単位のドキュメントを更新する。
- 複数領域に影響する変更は、この README の参照先を起点に関連ファイルを確認する。
- 背景説明、検討過程、感想は記載しない。決定済みの仕様、制約、ルールのみを書く。
- テーブルを追加・変更した場合は、`database/tables/` の該当ファイルを更新する。
- 外部API連携を変更した場合は、`integrations/` の該当ファイルを更新する。

## ドキュメント一覧

### アーキテクチャ

- [技術スタック](architecture/tech-stack.md)
- [モジュール構成](architecture/modules.md)
- [レイヤーと依存ルール](architecture/layers.md)
- [エラーハンドリング](architecture/error-handling.md)
- [トランザクション管理](architecture/transactions.md)
- [永続化方針](architecture/persistence.md)
- [テスト方針](architecture/testing.md)

### ドメイン

- [ユビキタス言語](domain/ubiquitous-language.md)
- [境界づけられたコンテキスト](domain/bounded-contexts.md)
- [コンテキスト間連携](domain/context-map.md)

### 外部連携

- [Google カレンダー同期](integrations/google-calendar-sync.md)
- [外部サービス](integrations/external-services.md)

### 運用・非同期処理

- [非同期処理と通知ジョブ](operations/async-processing.md)
- [実装ロードマップ](operations/roadmap.md)
- [未決事項](operations/open-issues.md)

### データベース

- [DB共通ルール](database/overview.md)
- [テーブル一覧](database/tables/README.md)
