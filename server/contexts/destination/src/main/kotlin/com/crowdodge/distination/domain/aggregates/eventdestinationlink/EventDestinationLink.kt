package com.crowdodge.distination.domain.aggregates.eventdestinationlink

import com.crowdodge.distination.domain.aggregates.eventdestination.EventDestinationId
import com.crowdodge.distination.domain.values.ScheduleId

// 予定（ID）に対する目的地IDの紐付け
class EventDestinationLink(
    val scheduleId: ScheduleId,
    val eventDestinationId: EventDestinationId,
)
