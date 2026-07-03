package com.crowdodge.event.application.port

import arrow.core.Either
import com.crowdodge.event.domain.error.EventError
import kotlin.time.Instant

interface CalendarWatchRegistrationGateway {
    suspend fun startWatch(
        connection: CalendarConnection,
    ): Either<EventError.ExternalError, CalendarWatchRegistration>

    suspend fun stopWatch(
        connection: CalendarConnection,
        channelId: String,
        resourceId: String,
    ): Either<EventError.ExternalError, Unit>
}

data class CalendarWatchRegistration(
    val channelId: String,
    val resourceId: String,
    val channelToken: String?,
    val expiration: Instant?,
)
