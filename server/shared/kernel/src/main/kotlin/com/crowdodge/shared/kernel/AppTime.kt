package com.crowdodge.shared.kernel

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant

/**
 * アプリ全体の業務日時ルール。
 *
 * crowdodge は日本国内で利用する前提のため、日付だけで表される値の Instant 化は
 * Asia/Tokyo の日付境界として扱う。
 */
object AppTime {
    val businessTimeZone: TimeZone = TimeZone.of("Asia/Tokyo")

    fun startOfBusinessDate(date: LocalDate): Instant =
        date.atStartOfDayIn(businessTimeZone)
}
