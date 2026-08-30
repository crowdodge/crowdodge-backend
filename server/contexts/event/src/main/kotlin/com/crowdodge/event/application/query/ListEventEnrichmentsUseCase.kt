package com.crowdodge.event.application.query

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.event.application.port.CalendarEventEnrichments
import com.crowdodge.event.application.port.EventEnrichmentReadModel
import com.crowdodge.shared.kernel.UserUuid

class ListEventEnrichmentsUseCase(
    private val readModel: EventEnrichmentReadModel,
) {
    suspend fun handle(
        userUuid: UserUuid,
        googleCalendarIds: Set<String>?,
    ): Either<ListEventEnrichmentsError, List<CalendarEventEnrichments>> {
        val calendars = readModel.findCalendars(userUuid, googleCalendarIds)
        val requested = googleCalendarIds ?: return calendars.right()
        val found = calendars.mapTo(mutableSetOf()) { it.googleCalendarId }
        val unavailable = requested - found
        return if (unavailable.isEmpty()) calendars.right() else ListEventEnrichmentsError(unavailable).left()
    }
}

data class ListEventEnrichmentsError(
    val unavailableGoogleCalendarIds: Set<String>,
)
