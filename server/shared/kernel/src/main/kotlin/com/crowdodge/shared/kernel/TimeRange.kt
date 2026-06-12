package com.crowdodge.shared.kernel

import kotlin.time.Instant

/**
 * 日時範囲 VO（events.start_time / end_time）。`start < end` を不変条件とする（§6.2）。
 */
data class TimeRange(val start: Instant, val end: Instant) {
    init {
        require(end > start) { "end は start より後でなければならない: start=$start end=$end" }
    }

    /** 他の範囲と時間が重なるか。 */
    fun overlaps(other: TimeRange): Boolean =
        start < other.end && other.start < end
}
