package com.crowdodge.event.application.port

import com.crowdodge.event.domain.model.UserCalendarUuid
import kotlin.time.Instant

/**
 * Google Calendar watch 状態の参照ポート。
 *
 * webhook 通知は `X-Goog-Channel-ID` だけでは自社カレンダーを直接識別できないため、
 * application はこのポートを通して同期対象の [UserCalendarUuid] を解決する。
 */
interface CalendarWatchPort {
    suspend fun findByChannelId(channelId: String): CalendarWatch?
}

data class CalendarWatch(
    val userCalendarUuid: UserCalendarUuid,
    val resourceId: String?,
    val channelToken: String?,
    val expiration: Instant?,
)
