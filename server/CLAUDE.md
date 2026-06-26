# crowdodge バックエンド（server）

Kotlin / Ktor / Exposed R2DBC / Arrow による DDD モジュラーモノリス（4層アーキテクチャ）。

**技術仕様の入口は [docs/README.md](../docs/README.md)**（リポジトリ直下）。規約の詳細はここに重複させず、docs 配下の該当ファイルと各 skill に従う。

## 作業の進め方（skill）

| やること | skill |
|---|---|
| 設計（BC / 集約 / 層配置 / エラー型の確定） | `design` |
| 採用ライブラリの仕様確認（固定バージョン） | `lib-research` |
| 実装（書いたら Konsist + build で検証） | `coding` |
| テスト（層別戦略） | `testing` |
| プロジェクト規約のレビュー | `arch-review` |

推奨フロー: `design` → `lib-research` → `coding` → `testing` / `arch-review`。

## ビルド・テスト

```bash
./gradlew :konsist:test   # 4層の依存方向を自動検査（実装後に必ず通す）
./gradlew build           # ビルド + テスト
```

Konsist 違反（例: application が Ktor/Exposed を import）はコードでなく**層配置**を直す。
