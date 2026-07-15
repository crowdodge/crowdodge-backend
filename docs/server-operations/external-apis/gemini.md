# Gemini

## 更新対象

- Gemini API、モデル、生成入力、レスポンス形式、再試行条件を変更した場合に更新する。

## 利用目的

- Notification dispatch Cloud Run Jobから、予定に紐づく混雑時間帯を生成する。
- 使用するモデルは`gemini-3.5-flash`とする。
- 予定開始・終了、終日予定かどうか、目的地、往路経路、移動時間を生成入力とする。
- 復路は往路の経路ステップを反転して生成入力へ含める。
- 予測対象期間は、予定開始の「移動時間 + 2時間」前から、予定終了の「移動時間 + 2時間」後までとする。

## Google Searchによる調査

- 1回のInteraction内でGoogle Searchと構造化出力を利用する。
- 目的地周辺に加え、往路・復路の経路上にある実在イベントと交通障害を調査対象にする。
- Google Searchで実行した検索クエリを1件も取得できない応答は生成拒否として扱う。
- 混雑情報がある応答はURL citationを必須とし、根拠がない応答は生成拒否として扱う。
- 経路上の検索の完全網羅は保証せず、生成結果は根拠に基づく混雑予測として扱う。

## レスポンス契約

- レスポンスはJSONオブジェクトとし、`congestions`配列を持つ。
- `congestions`は最大3件とする。
- 各要素は`start`、`end`、`area`、`description`を持つ。
- `start`と`end`はdate-time形式とし、`start < end`を満たす。
- 混雑期間は予測対象期間内に収める。
- `area`と`description`は空文字を許可しない。
- レスポンス契約に違反した生成結果は恒久的な生成拒否として扱う。

## 再試行

- HTTP 429、HTTP 5xx、I/O障害、接続timeout、リクエストtimeout、socket timeoutを再試行する。
- `Retry-After`が秒数またはHTTP-dateとして解釈できる場合は、その値を待機時間として使用する。
- `Retry-After`がない、または解釈できない場合は、指数バックオフとjitterを使用する。
- 1回の生成処理での最大試行回数は2回とする。
- 最大試行回数まで失敗した場合は一時的な生成失敗として扱う。
- HTTP 429以外の4xx、不正JSON、未完了レスポンス、出力テキスト欠損は再試行しない。
- コルーチンのキャンセルは再試行せず再送出する。

## 認証

- Gemini APIの認証には`GEMINI_API_KEY`を使用する。
- APIキーはSecret ManagerからNotification dispatch Jobへ環境変数として注入する。
