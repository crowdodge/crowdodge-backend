package com.crowdodge.app.calendar

import com.crowdodge.event.application.service.GoogleCalendarEventSynchronizer
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventHandler
import com.crowdodge.user.domain.event.CalendarInitialSyncRequested
import org.slf4j.LoggerFactory
import com.crowdodge.event.domain.model.UserCalendarUuid as EventUserCalendarUuid

class CalendarInitialSyncRequestedHandler(
    private val synchronizer: GoogleCalendarEventSynchronizer,
) : DomainEventHandler {
    override fun supports(event: DomainEvent): Boolean = event is CalendarInitialSyncRequested

    override suspend fun handle(event: DomainEvent) {
        val requested = event as? CalendarInitialSyncRequested ?: return
        synchronizer.initialSync(EventUserCalendarUuid(requested.userCalendarUuid.value)).onLeft {
            logger.warn("Failed initial Google Calendar sync: {}", it.code)
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(CalendarInitialSyncRequestedHandler::class.java)
    }
}
