---
name: lib-research
description: crowdodge が採用するライブラリ(Ktor / Exposed R2DBC / Arrow / Koin / Flyway / kotlinx 等)の正確な API・仕様・使い方を、gradle/libs.versions.toml で固定されたバージョンに対して調査する。「Ktor のこの書き方合ってる?」「Exposed R2DBC で upsert は?」「Arrow 2.x の Raise の使い方」「この API はこのバージョンにある?」といった仕様確認の依頼で使う。バージョン違いの誤情報を避けたいときに。
---

# ライブラリ仕様調査 (lib-research)

採用ライブラリの API/仕様を**固定バージョンに対して正確に**調べる役割。記憶や一般論で書かず、バージョン差異(特に Exposed R2DBC・Arrow 2.x・Kotlin 2.2)に注意する。

## 大原則: まずバージョンを確定する

調査の前に必ず [server/gradle/libs.versions.toml](../../../server/gradle/libs.versions.toml) で対象バージョンを確認する。**バージョンを言わずに調べない。** 現在の主要バージョン(変わりうるので毎回 toml を見る):

| ライブラリ | version | 注意点 |
|---|---|---|
| Kotlin | 2.2.20 | `context parameters`(旧 `context(...)`)、`Raise` 記述スタイルが世代で変わる(§15-7) |
| Ktor | 3.4.1 | 3.x は 2.x と API/プラグイン構成が異なる。Netty エンジン |
| Exposed | 1.3.0 | **`exposed-r2dbc`** を使用。`suspendTransaction { }`。JDBC 系 Exposed の記事と API が違う点に注意 |
| Arrow | 2.2.1 | **2.x**。`Raise`/`either { }`/`ensure`。1.x の記事と差異あり |
| Koin | 4.1.1 | `koin-ktor` 連携 |
| Flyway | 11.13.2 | JDBC 接続で起動時実行(R2DBC 非対応) |
| kotlinx.serialization | 1.8.1 | Ktor 公式統合 |
| Testcontainers | 1.21.3 | infra テスト(実 PostgreSQL) |
| MockK | 1.14.5 | application テスト |
| Konsist | 0.17.3 | 依存方向検査 |

## 調査の進め方

1. **toml でバージョン確定** → そのバージョンの公式ドキュメント/リリースノート/API リファレンスを当たる。
2. **WebSearch / WebFetch で一次情報を取る** — 公式 docs・GitHub の該当タグ・KDoc を優先。Stack Overflow やブログは**バージョンを確認**してから採用(古い記事が多い)。
3. **バージョン整合をチェック** — その API が固定バージョンに存在するか、シグネチャは合っているか、非推奨でないか。R2DBC 版か JDBC 版かを必ず区別する。
4. **このプロジェクトの規約に当てはめる** — 例えば「application は Ktor/Exposed を import しない」(architecture.md §4)ので、調べた API がどの層に置けるかも併せて答える。
5. **最小実行例を示す** — このコードベースの書き方(`suspendTransaction`、`either { }`、Koin module、Problem Details)に寄せたスニペットで。出典 URL とバージョンを明記。

## 出力に必ず含める

- 対象ライブラリと **バージョン**(toml の値)。
- 結論(その API/書き方が正しいか、推奨形)。
- 最小サンプルコード(本プロジェクトの規約に沿う)。
- 出典(公式 URL)。不確実な点は「要検証」と明示。

## よくある落とし穴

- Exposed の **JDBC 向け記事をそのまま R2DBC に適用**してしまう(`transaction { }` vs `suspendTransaction { }`)。
- Arrow **1.x の `Either` モナド構文**を 2.x と混同する。
- Ktor **2.x のプラグイン DSL**を 3.x に持ち込む。
- Flyway を R2DBC で動かそうとする(JDBC ドライバ併用が前提)。

## 関連スキル(推奨フロー)

[design](../design/SKILL.md)(設計) → `lib-research`(ここ。採用ライブラリで実現可能か・正しい書き方かを確認) → [coding](../coding/SKILL.md)(実装)。
