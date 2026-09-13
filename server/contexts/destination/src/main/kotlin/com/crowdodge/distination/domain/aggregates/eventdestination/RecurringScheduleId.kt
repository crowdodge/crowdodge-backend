package com.crowdodge.distination.domain.aggregates.eventdestination

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.distination.domain.error.DestinationError

// HACK: こちらのBCでも同じIDを利用することが発覚したため、
// HACK: event/domain/EventEvents.ktのRecurringEventIdを、
// HACK: shared kernelのUserIdの場所に移動してId interfaceを定義・実装、
// HACK: こちらとevent/domain/EventEvents.ktのRecurringEventIdを削除するべきかも。

@JvmInline
value class RecurringScheduleId private constructor(val value: String) { // NOTE: バリデーション経由の実行を強制する
    companion object {
        fun  Raise<DestinationError.ValidationError>.recurringScheduleId(value: String): RecurringScheduleId {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) {DestinationError.ValidationError.BlankRecurringScheduleId}
            return RecurringScheduleId(trimmed)
        }
    }
}
