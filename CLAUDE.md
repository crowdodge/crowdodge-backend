# crowdodge-backend

crowdodge のバックエンドリポジトリ。

## リポジトリ構成

| パス | 内容 |
|---|---|
| [server/](server/) | Kotlin バックエンド本体（Gradle マルチモジュール）。詳細は [server/CLAUDE.md](server/CLAUDE.md) |
| [docs/architecture.md](docs/architecture.md) | 設計・実装規約の唯一の正（設計憲法） |
| [.github/workflows/](.github/workflows/) | issue アサイン時の着手・Project 追加などフロー自動化 |

## Git / GitHub フロー

- ブランチ: `feature/<issue番号>-<説明>`。PR は **develop** 向け。
- コミット: Conventional Commits prefix（`feat`/`fix`/`chore`/`docs`）+ **日本語本文**。
- PR 本文に対応 issue（`#番号`）をリンクする。
- issue アサイン時のブランチ作成・着手コミットは GitHub Actions が行う。Claude は既存ブランチでの実装〜コミット〜PR を担う。
