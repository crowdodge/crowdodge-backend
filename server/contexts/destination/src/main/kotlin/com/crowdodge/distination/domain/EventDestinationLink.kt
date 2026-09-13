package com.crowdodge.distination.domain

// 予定（ID）に対する目的地IDの紐付け
class EventDestinationLink(
    val scheduleId: ScheduleId,
    val eventDestinationId: EventDestinationId,
)