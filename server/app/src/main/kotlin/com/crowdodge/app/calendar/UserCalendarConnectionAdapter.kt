package com.crowdodge.app.calendar

import arrow.core.left
import arrow.core.right
import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.port.CalendarConnectionProvider
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.user.application.query.ResolveGoogleCalendarConnectionUseCase
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.UserCalendarUuid as UserBcCalendarUuid

class UserCalendarConnectionAdapter(
    private val resolveConnection: ResolveGoogleCalendarConnectionUseCase,
) : CalendarConnectionProvider {
    override suspend fun connection(
        userCalendarUuid: com.crowdodge.event.domain.model.UserCalendarUuid,
    ) = resolveConnection.handle(UserBcCalendarUuid(userCalendarUuid.value)).fold(
        ifLeft = {
            when (it) {
                UserError.ExternalError.GoogleCalendarTimeoutError ->
                    EventError.ExternalError.GoogleCalendarTimeoutError.left()
                else -> EventError.ExternalError.GoogleCalendarError.left()
            }
        },
        ifRight = { CalendarConnection(it.googleCalendarId, it.accessToken).right() },
    )
}
