package com.crowdodge.notification.infrastructure.fcm

import com.crowdodge.notification.application.port.OutboundPushMessage
import com.crowdodge.notification.application.port.PushNotification
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

class FcmMessageBuilderTest : FunSpec({
    test("token と notification から Message を組み立てられる") {
        val message = FcmMessageBuilder.build(
            fcmToken = "token-1",
            notification = PushNotification(title = "打合せ", body = "07/08 19:00 開始"),
        )
        message shouldNotBe null
    }

    test("FCM バッチ呼び出し自体の例外は呼び出し元へ伝播する") {
        val sender = FcmPushNotificationSender {
            error("FCM unavailable")
        }

        shouldThrow<IllegalStateException> {
            sender.sendAll(
                listOf(
                    OutboundPushMessage(
                        fcmToken = "token-1",
                        notification = PushNotification(title = "打合せ", body = "07/08 19:00 開始"),
                    ),
                ),
            )
        }
    }
})
