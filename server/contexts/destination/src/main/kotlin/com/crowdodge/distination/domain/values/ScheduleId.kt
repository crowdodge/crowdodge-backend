package com.crowdodge.distination.domain.values

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.distination.domain.error.DestinationError

@JvmInline
value class ScheduleId private constructor(val value: String) {
    companion object {
        fun  Raise<DestinationError.ValidationError>.from(value: String): ScheduleId {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) {DestinationError.ValidationError.BlankScheduleId}
            return ScheduleId(trimmed)
        }
    }
}