package com.crowdodge.readmodel.event

import com.crowdodge.congestion.infrastructure.persistence.EventCongestionForecastsTable
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionsTable
import com.crowdodge.distination.infrastructure.persistence.EventDestinationLinksTable
import com.crowdodge.distination.infrastructure.persistence.EventDestinationsTable
import com.crowdodge.event.application.port.CalendarEventEnrichments
import com.crowdodge.event.application.port.EventEnrichment
import com.crowdodge.event.application.port.EventEnrichmentCongestion
import com.crowdodge.event.application.port.EventEnrichmentDestination
import com.crowdodge.event.application.port.EventEnrichmentReadModel
import com.crowdodge.event.infrastructure.persistence.EventsTable
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

class ExposedEventEnrichmentReadModel(
    private val transactions: TransactionRunner,
) : EventEnrichmentReadModel {
    override suspend fun findCalendars(
        userUuid: UserUuid,
        googleCalendarIds: Set<String>?,
    ): List<CalendarEventEnrichments> = transactions.readOnly {
        selectRows(userUuid, googleCalendarIds)
            .groupBy { it[UserCalendarsTable.googleCalendarId] }
            .map { (calendarId, calendarRows) ->
                CalendarEventEnrichments(
                    googleCalendarId = calendarId,
                    events = calendarRows
                        .filter { it.getOrNull(EventsTable.eventUuid) != null }
                        .groupBy { it[EventsTable.eventUuid] }
                        .map { (_, eventRows) -> eventRows.toEvent() }
                        .sortedBy(EventEnrichment::googleEventId),
                )
            }
            .sortedBy(CalendarEventEnrichments::googleCalendarId)
    }

    private suspend fun selectRows(
        userUuid: UserUuid,
        googleCalendarIds: Set<String>?,
    ): List<ResultRow> {
        // INNER JOINでは予定が0件の選択中カレンダーを契約どおり空配列で返せないため、付加情報までLEFT JOINする。
        val joined = UserCalendarsTable
            .join(
                EventsTable,
                JoinType.LEFT,
                onColumn = UserCalendarsTable.userCalendarUuid,
                otherColumn = EventsTable.userCalendarUuid,
            )
            .join(
                EventDestinationLinksTable,
                JoinType.LEFT,
                onColumn = EventsTable.eventUuid,
                otherColumn = EventDestinationLinksTable.eventUuid,
            )
            .join(
                EventDestinationsTable,
                JoinType.LEFT,
                onColumn = EventDestinationLinksTable.eventDestinationUuid,
                otherColumn = EventDestinationsTable.eventDestinationUuid,
            )
            .join(
                EventCongestionForecastsTable,
                JoinType.LEFT,
                onColumn = EventsTable.eventUuid,
                otherColumn = EventCongestionForecastsTable.eventUuid,
            )
            .join(
                EventCongestionsTable,
                JoinType.LEFT,
                onColumn = EventCongestionForecastsTable.eventCongestionForecastUuid,
                otherColumn = EventCongestionsTable.eventCongestionForecastUuid,
            )

        return joined.select(
            UserCalendarsTable.googleCalendarId,
            EventsTable.eventUuid,
            EventsTable.googleEventId,
            EventDestinationsTable.eventDestinationUuid,
            EventDestinationsTable.destination,
            EventDestinationsTable.destinationPoint,
            EventCongestionsTable.eventCongestionUuid,
            EventCongestionsTable.congestionStartTime,
            EventCongestionsTable.congestionEndTime,
            EventCongestionsTable.area,
            EventCongestionsTable.description,
        ).where {
            val ownedByUser = UserCalendarsTable.userUuid eq userUuid.value
            if (googleCalendarIds == null) {
                ownedByUser
            } else {
                ownedByUser and (UserCalendarsTable.googleCalendarId inList googleCalendarIds)
            }
        }.toList()
    }

    private fun List<ResultRow>.toEvent(): EventEnrichment {
        val first = first()
        val destination = first.toDestination()
        return EventEnrichment(
            googleEventId = first[EventsTable.googleEventId],
            eventUuid = first[EventsTable.eventUuid],
            destination = destination,
            congestions = if (destination == null) {
                // 目的地未推定では残存した予測行を返すと公開契約に反するため、混雑も未推定として扱う。
                emptyList()
            } else {
                mapNotNull { it.toCongestion() }
                    .distinct()
                    .sortedWith(compareBy(EventEnrichmentCongestion::start, EventEnrichmentCongestion::end))
            },
        )
    }

    private fun ResultRow.toDestination(): EventEnrichmentDestination? {
        getOrNull(EventDestinationsTable.eventDestinationUuid) ?: return null
        val point = this[EventDestinationsTable.destinationPoint]
        return EventEnrichmentDestination(
            name = this[EventDestinationsTable.destination],
            latitude = point.latitude,
            longitude = point.longitude,
        )
    }

    private fun ResultRow.toCongestion(): EventEnrichmentCongestion? {
        getOrNull(EventCongestionsTable.eventCongestionUuid) ?: return null
        return EventEnrichmentCongestion(
            start = this[EventCongestionsTable.congestionStartTime],
            end = this[EventCongestionsTable.congestionEndTime],
            area = this[EventCongestionsTable.area],
            description = this[EventCongestionsTable.description],
        )
    }
}
