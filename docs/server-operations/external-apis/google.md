# Google

## 更新対象

- Google OAuth、Google Calendar API、Webhookの利用目的、認可scope、資格情報の扱い、外部API失敗時の扱いを変更した場合に更新する。

## OAuth

- Google Sign-Inで取得した認可コードをGoogle token endpointへ送信し、access token、refresh token、ID tokenを取得する。
- 必須scopeは `https://www.googleapis.com/auth/calendar.events` と `https://www.googleapis.com/auth/calendar.calendarlist.readonly` とする。
- ID tokenはGoogle JWKSで署名、issuer、audience、email verificationを検証する。
- JWKSはGoogleの `Cache-Control` に従ってキャッシュする。
- access tokenの有効期限が1分以内の場合は、外部API呼び出し前にrefresh tokenで更新する。
- token endpointが `invalid_grant` を返した場合は、再認可が必要な状態として扱う。

## Google Calendar API

| API | サーバー側の利用目的 |
|---|---|
| Calendar List | カレンダー選択候補とaccess roleの取得 |
| Events | 選択済みカレンダーの予定の読み取り、書き込み、中継、同期 |
| Events watch | 予定変更通知の受信登録 |
| Channels stop | 不要または更新済みのwatch停止 |

- カレンダー予定のSource of TruthはGoogle Calendarとする。
- 同期はGoogleから読み取った予定をサーバーへ投影する。
- Google Calendar Events APIのHTTPプロキシは、レスポンス内容を保存または解釈しない。
- 接続、送信、応答のtimeoutは10秒とする。
- token endpointまたはCalendar APIのtimeoutは外部連携timeoutとして扱う。その他の外部呼び出し失敗はGoogle連携失敗として扱う。

## Webhook

- Google Calendar watchの通知先はCloud Run API serviceとする。
- `X-Goog-Channel-ID` と `X-Goog-Resource-State` がない通知は受け付けない。
- 初期同期を表す `X-Goog-Resource-State: sync` では予定同期を行わない。
- 変更通知はchannel IDで同期対象を識別し、保存済みchannel tokenと `X-Goog-Channel-Token` が一致する場合だけ同期する。
- watchの有効期限が24時間以内の場合は更新対象とする。
