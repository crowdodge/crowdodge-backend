package com.crowdodge.notification.domain.service

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * kind 別の通知時刻計算（純粋関数）。
 * - Reminder: 開始 − remindTiming の 1 点。
 * - CongestionAlert: 作成直後（now）+ 開始 30 日前 + 開始 7 日前 の最大 3 点（「1ヶ月前」は 30 日で近似）。
 * いずれも予定開始済みなら対象外。
 */
object NotificationTimingPolicy {
    private val congestionAlertOffsets: List<Duration> = listOf(30.days, 7.days)

    /** 開始済みなら null。通知時刻が過去でも開始前なら返す（次回 Job が即送信する）。 */
    fun reminderTime(eventStart: Instant, remindTiming: Duration, now: Instant): Instant? =
        if (eventStart <= now) null else eventStart - remindTiming

    /**
     * 開始済みなら空。[includeImmediate] は EventScheduled（新規登録）時のみ true にする
     * （再計算時に「作成直後」通知を再生成しないため）。
     */
    fun congestionAlertTimes(eventStart: Instant, now: Instant, includeImmediate: Boolean): List<Instant> {
        if (eventStart <= now) return emptyList()
        val offsetTimes = congestionAlertOffsets.map { eventStart - it }.filter { it > now }
        return if (includeImmediate) listOf(now) + offsetTimes else offsetTimes
    }
}
