package com.crowdodge.event.application.port

import arrow.core.Either
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.EventContent
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.RecurringEventId
import kotlin.time.Instant

interface GoogleCalendarEventsGateway {
    suspend fun incrementalSync(
        connection: CalendarConnection,
        syncToken: String,
    ): Either<EventError.ExternalError, CalendarSyncFetchResult>

    suspend fun fullSync(
        connection: CalendarConnection,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError.ExternalError, CalendarSyncBatch>
}

sealed interface CalendarSyncFetchResult {
    data class Events(val batch: CalendarSyncBatch) : CalendarSyncFetchResult

    data object SyncTokenExpired : CalendarSyncFetchResult
}

data class CalendarSyncBatch(
    val upserts: List<IncomingCalendarEvent>,
    val cancellations: List<GoogleEventId>,
    val nextSyncToken: String?,
)

data class IncomingCalendarEvent(
    val googleEventId: GoogleEventId,
    val recurringEventId: RecurringEventId?,
    val originalStart: Instant?,
    val eventContent: EventContent,
)
