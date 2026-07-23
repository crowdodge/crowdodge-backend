package com.crowdodge.congestion.domain.service

import com.crowdodge.congestion.domain.model.CongestionPeriod
import com.crowdodge.shared.kernel.AppTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 混雑期間から通知用の概要を作成する。 */
class CongestionSummaryFormatter {
    /** 予定期間と混雑期間から通知本文用の概要を返す。 */
    fun format(
        eventStart: Instant,
        eventEnd: Instant,
        periods: List<CongestionPeriod>,
    ): String? {
        if (periods.isEmpty()) return null
        val sorted = periods.sortedWith(compareBy({ it.start }, { it.end }, { it.area }, { it.description }))
        val body = if (eventEnd - eventStart <= 24.hours) {
            sorted.joinToString("、", transform = ::formatPeriod)
        } else {
            sorted.asSequence()
                .map { it.start.toLocalDateTime(AppTime.businessTimeZone).date.day }
                .distinct()
                .sorted()
                .joinToString("、") { "${it}日" }
        }
        return "${body}に混雑予測あり"
    }

    private fun formatPeriod(period: CongestionPeriod): String {
        val start = period.start.toLocalDateTime(AppTime.businessTimeZone)
        val end = period.end.toLocalDateTime(AppTime.businessTimeZone)
        return if (start.date == end.date) {
            "${formatRangeStartTime(start)}〜${formatTime(end)}"
        } else {
            "${start.date.day}日${formatTime(start)}〜${end.date.day}日${formatTime(end)}"
        }
    }

    private fun formatRangeStartTime(value: LocalDateTime): String =
        if (value.minute == 0) value.hour.toString() else formatTime(value)

    private fun formatTime(value: LocalDateTime): String =
        if (value.minute == 0) "${value.hour}時" else "%d:%02d".format(value.hour, value.minute)
}
