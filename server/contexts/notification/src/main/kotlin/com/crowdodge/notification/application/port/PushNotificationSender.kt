package com.crowdodge.notification.application.port

import arrow.core.Either
import com.crowdodge.notification.domain.error.NotificationError

data class PushNotification(val title: String, val body: String)

/** 1 通の送信要求。 */
data class OutboundPushMessage(val fcmToken: String, val notification: PushNotification)

/** プッシュ通知の一括送信。結果は入力と同順・同数で返す。実装は infrastructure の FCM ゲートウェイ。トランザクション外で呼ぶ。 */
fun interface PushNotificationSender {
    suspend fun sendAll(
        messages: List<OutboundPushMessage>,
    ): List<Either<NotificationError.DispatchError, Unit>>
}
