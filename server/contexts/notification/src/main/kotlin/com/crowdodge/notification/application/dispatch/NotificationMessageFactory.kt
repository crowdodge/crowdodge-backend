package com.crowdodge.notification.application.dispatch

import com.crowdodge.notification.application.port.CongestionInfo
import com.crowdodge.notification.application.port.EventDispatchSource
import com.crowdodge.notification.application.port.PushNotification
import com.crowdodge.shared.kernel.AppTime
import kotlinx.datetime.toLocalDateTime

/** 通知ペイロード（タイトル・予定日時・混雑情報の最小構成）を組み立てる。 */
object NotificationMessageFactory {
    private const val DEFAULT_TITLE = "予定のお知らせ"

    fun create(source: EventDispatchSource, congestion: CongestionInfo?): PushNotification {
        val body = buildString {
            append(formatStart(source))
            congestion?.let {
                append('\n')
                append("混雑情報: ")
                append(it.description)
            }
        }
        return PushNotification(
            title = source.title ?: DEFAULT_TITLE,
            body = body,
        )
    }

    private fun formatStart(source: EventDispatchSource): String {
        val local = source.start.toLocalDateTime(AppTime.businessTimeZone)
        val date = "%02d/%02d".format(local.month.ordinal + 1, local.day)
        return if (source.isAllDay) {
            "$date 終日"
        } else {
            "$date %02d:%02d 開始".format(local.hour, local.minute)
        }
    }
}
