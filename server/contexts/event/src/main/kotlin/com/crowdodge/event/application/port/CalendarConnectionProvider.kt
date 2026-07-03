package com.crowdodge.event.application.port

import arrow.core.Either
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.UserCalendarUuid

data class CalendarConnection(
    val calendarId: String,
    val accessToken: String,
)

fun interface CalendarConnectionProvider {
    suspend fun connection(
        userCalendarUuid: UserCalendarUuid,
    ): Either<EventError.ExternalError, CalendarConnection>
}
