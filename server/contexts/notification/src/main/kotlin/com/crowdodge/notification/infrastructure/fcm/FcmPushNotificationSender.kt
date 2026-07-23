package com.crowdodge.notification.infrastructure.fcm

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.notification.application.port.OutboundPushMessage
import com.crowdodge.notification.application.port.PushNotification
import com.crowdodge.notification.application.port.PushNotificationSender
import com.crowdodge.notification.domain.error.NotificationError
import com.google.firebase.messaging.BatchResponse
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.google.firebase.messaging.Notification as FcmNotification

/** FCM の Message 組み立て（送信と分離してユニット検証可能にする）。 */
object FcmMessageBuilder {
    @Suppress("DEPRECATION")
    fun build(fcmToken: String, notification: PushNotification): Message =
        Message.builder()
            .setToken(fcmToken)
            .setNotification(
                FcmNotification.builder()
                    .setTitle(notification.title)
                    .setBody(notification.body)
                    .build(),
            )
            .build()
}

/**
 * Firebase Admin SDK による FCM 一括送信。認証はサービスアカウント
 * （GOOGLE_APPLICATION_CREDENTIALS 等の Application Default Credentials）。
 * sendEach は 1 バッチ 500 件上限のためチャンク分割し、ブロッキング API のため Dispatchers.IO で実行する。
 * 無効トークン等の失敗はログに記録し PushSendFailed を返す（トークン削除はスコープ外）。
 */
@Suppress("TooGenericExceptionCaught")
class FcmPushNotificationSender internal constructor(
    private val batchClient: FcmBatchClient,
) : PushNotificationSender {
    constructor(messaging: FirebaseMessaging) : this(FcmBatchClient { messages -> messaging.sendEach(messages) })

    override suspend fun sendAll(
        messages: List<OutboundPushMessage>,
    ): List<Either<NotificationError.DispatchError, Unit>> {
        if (messages.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            messages.chunked(FCM_BATCH_LIMIT).flatMap { chunk -> sendChunk(chunk) }
        }
    }

    private fun sendChunk(chunk: List<OutboundPushMessage>): List<Either<NotificationError.DispatchError, Unit>> =
        try {
            val batch = batchClient.sendEach(chunk.map { FcmMessageBuilder.build(it.fcmToken, it.notification) })
            batch.responses.map { response ->
                if (response.isSuccessful) {
                    Unit.right()
                } else {
                    logger.warn("FCM 送信に失敗しました", response.exception)
                    NotificationError.DispatchError.PushSendFailed.left()
                }
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            logger.error("FCM バッチ送信に失敗しました", cause)
            throw cause
        }

    private companion object {
        /** FCM sendEach の 1 バッチ上限。 */
        private const val FCM_BATCH_LIMIT = 500
        private val logger = LoggerFactory.getLogger(FcmPushNotificationSender::class.java)
    }
}

internal fun interface FcmBatchClient {
    fun sendEach(messages: List<Message>): BatchResponse
}
