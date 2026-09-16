package com.crowdodge.distination.domain.aggregates.eventdestination

import com.crowdodge.shared.kernel.EntityUuid
import kotlin.uuid.Uuid

@JvmInline
value class EventDestinationId (override val value: Uuid): EntityUuid {
    companion object {
        fun generate(): EventDestinationId = EventDestinationId(Uuid.random())
        fun from(value: Uuid): EventDestinationId = EventDestinationId(value)
    }
}
