# Firebase Cloud Messaging

## 更新対象

- FCM送信方式、送信元Job、認証方式、必要な権限を変更した場合に更新する。

## 通知送信

- Notification dispatch Cloud Run JobがFCMで通知を送信する。
- 送信先はFCM registration tokenとする。
- Firebase Admin SDKは、Notification dispatch Jobの実行サービスアカウントによるApplication Default Credentialsを使用する。
- Notification dispatch Jobの実行サービスアカウントには、Firebase Cloud Messagingを利用する権限を付与する。
