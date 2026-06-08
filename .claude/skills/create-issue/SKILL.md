---
name: create-issue
description: GitHub issue を Issue Form テンプレート（.github/ISSUE_TEMPLATE）の型を厳密に守って起票する。issue 作成・起票・「issue を立てて」と頼まれたとき、特に crowdodge リポジトリで gh issue create を使う前に必ず使用する。テンプレの必須項目（特に「ブランチ名」）を埋めないと自動ブランチ作成が壊れるため、本 skill で型を担保する。
---

# create-issue — テンプレ厳守で issue を起票する

crowdodge では issue を起票→アサインすると `start-work-on-assign` workflow が
issue 本文の `### ブランチ名` を読んでブランチと Draft PR を自動生成する。
そのため issue は **Issue Form テンプレートの表示形式（`### <ラベル>` 見出し）を厳密に守る**必要がある。
自由記述で作ると必須項目（特に `### ブランチ名`）が欠け、ブランチ名が `feature/<番号>` に退化する。

この skill は「テンプレを実行時に読み → 必須項目を埋め → 表示形式でレンダリング → 必須欠落を検証 → 起票」を手順化する。

## 手順

### 1. issue 種別を決める
- 新機能 / 改善 → **feature**（`feature_request.yml`、ラベル `enhancement`、タイトル接頭辞 `[Feature] `）
- バグ → **bug**（`bug_report.yml`、ラベル `bug`、タイトル接頭辞 `[Bug] `）

### 2. テンプレ定義を実機から取得（ドリフト防止）
ハードコードせず、必ず最新のテンプレを読んで項目を確認する。

```bash
# まずリポジトリ内、無ければ org の .github リポジトリを参照
cat .github/ISSUE_TEMPLATE/feature_request.yml 2>/dev/null \
  || gh api repos/{owner}/.github/contents/.github/ISSUE_TEMPLATE/feature_request.yml --jq '.content' | base64 -d
```

`{owner}` は対象リポジトリの owner（例 `crowdodge`）。各 `body[].attributes.label` と
`validations.required` を読み取り、**required: true の項目は必ず埋める**。

### 3. 本文を Issue Form の表示形式でレンダリング
**どの項目を出すか（ラベル・必須有無・順序）は手順2で読んだ YAML が正。ここで決めるのは並べ方だけ。**

`body[]` の各項目を、定義順に下の形式で出力する。この**並べ方は Issue Form 共通で、テンプレ内容が変わっても不変**なので skill に焼いてよい：

```markdown
### <その項目の label をそのまま>

<値>
```

- `### ` の後はテンプレの label と**完全一致**させる（末尾の「（任意）」等も含めそのまま）。
- `required: true` の項目は値を空にしない。
- チェックリスト的な項目（終了条件など、placeholder が `- [ ]` 形式）は `- [ ]` のリストで書く。
- `start-work-on-assign` は `^###\s+ブランチ名` に依存するため、ブランチ名項目は必ず `### ブランチ名` で始める見出しにする。

> 個別の項目名（概要 / 終了条件 / 何が起きた？ 等）はここに**列挙しない**。列挙すると古い定義が skill に焼き付き、手順2の「最新を参照」と矛盾してドリフトの原因になる。項目は必ず YAML から取る。

### 4. ブランチ名の規約
- kebab-case（小文字 / 数字 / `-`）。日本語・空白・スラッシュは避ける（workflow が sanitize で落とす）。
- 内容を端的に表す名前にする（例: `detekt-setup`, `ci-detekt-build`）。feature では必須。

### 5. 起票前の検証（必須欠落ならブロック）
`gh issue create` を実行する**前に**次を満たしているか自己チェックし、欠けていたら起票せず利用者に確認する。

- [ ] タイトルが種別接頭辞で始まる（`[Feature] ` / `[Bug] `）
- [ ] テンプレの **required: true の見出しがすべて存在**し、値が空でない
- [ ] feature は `### ブランチ名` が存在し非空（kebab-case）
- [ ] 見出しは `### <ラベル>`（テンプレの label と完全一致）

### 6. 起票
```bash
gh issue create --repo <owner>/<repo> \
  --title "[Feature] <要約>" \
  --label enhancement \
  --body "$(cat <<'EOF'
### 概要

...

### 終了条件

- [ ] ...

### ブランチ名

detekt-setup
EOF
)"
```

## 注意
- 本文は**結論ベース**で書く（検討の経緯や却下案は載せない）。
- ラベルはテンプレの `labels:` に従う（feature=`enhancement` / bug=`bug`）。勝手に増やさない。
- テンプレ項目が変わっている場合は**実機の YAML を正**とし、本 skill の例より優先する。
