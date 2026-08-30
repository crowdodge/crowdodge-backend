package com.crowdodge.event.application.port

import com.crowdodge.shared.kernel.UserUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface EventEnrichmentReadModel {
    suspend fun findCalendars(
        userUuid: UserUuid,
        googleCalendarIds: Set<String>?,
    ): List<CalendarEventEnrichments>
}

data class CalendarEventEnrichments(
    val googleCalendarId: String,
    val events: List<EventEnrichment>,
)
data class EventEnrichment(
    val googleEventId: String,
    val eventUuid: Uuid,
    val destination: EventEnrichmentDestination?,
    val congestions: List<EventEnrichmentCongestion>,
)

data class EventEnrichmentDestination(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

data class EventEnrichmentCongestion(
    val start: Instant,
    val end: Instant,
    val area: String,
    val description: String,
)
