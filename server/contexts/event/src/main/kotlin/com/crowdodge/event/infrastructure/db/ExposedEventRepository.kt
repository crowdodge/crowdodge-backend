package com.crowdodge.event.infrastructure.db

import arrow.core.getOrElse
import arrow.core.raise.either
import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.EventContent
import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.GoogleEventId.Companion.googleEventId
import com.crowdodge.event.domain.model.RecurringEventId.Companion.recurringEventId
import com.crowdodge.event.domain.model.RemindTiming.Companion.remindTiming
import com.crowdodge.event.domain.model.Schedule
import com.crowdodge.event.domain.model.Schedule.Companion.schedule
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.event.infrastructure.persistence.EventsTable
import com.crowdodge.shared.kernel.PersistedDataCorruption
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll

class ExposedEventRepository : EventRepository {
    override suspend fun upsertAll(events: List<Event>) {
        if (events.isEmpty()) return
        // 競合キー = (user_calendar_uuid, google_event_id)。衝突時の UPDATE では event_uuid と created_at を
        // 据え置く（自社 ID の安定・作成時刻の保持）。updated_at は clientDefault で前進する。
        EventsTable.batchUpsert(
            events,
            EventsTable.userCalendarUuid,
            EventsTable.googleEventId,
            onUpdateExclude = listOf(EventsTable.eventUuid, EventsTable.createdAt),
        ) { event -> toModel(event)(this) }
    }

    override suspend fun deleteByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ) {
        if (googleEventIds.isEmpty()) return
        EventsTable.deleteWhere {
            (EventsTable.userCalendarUuid eq userCalendarUuid.value) and
                (EventsTable.googleEventId inList googleEventIds.map { it.value })
        }
    }

    override suspend fun delete(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid) {
        EventsTable
            .deleteWhere {
                (EventsTable.eventUuid eq eventUuid.value) and
                    (EventsTable.userCalendarUuid eq userCalendarUuid.value)
            }
    }

    override suspend fun findByEventUuid(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid): Event? =
        EventsTable
            .selectAll()
            .where {
                (EventsTable.userCalendarUuid eq userCalendarUuid.value) and
                    (EventsTable.eventUuid eq eventUuid.value)
            }
            .firstOrNull()
            ?.let {
                toDomain(it)
            }

    override suspend fun findByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ): List<Event> {
        if (googleEventIds.isEmpty()) return emptyList()
        return EventsTable
            .selectAll()
            .where {
                (EventsTable.userCalendarUuid eq userCalendarUuid.value) and
                    (EventsTable.googleEventId inList googleEventIds.map { it.value })
            }
            .map { toDomain(it) }
            .toList()
    }

    override suspend fun findAllByUserCalendarUuid(userCalendarUuid: UserCalendarUuid): List<Event> =
        EventsTable
            .selectAll()
            .where { EventsTable.userCalendarUuid eq userCalendarUuid.value }
            .map { toDomain(it) }
            .toList()

    private fun toModel(event: Event): (UpdateBuilder<*>) -> Unit {
        return {
            with(EventsTable) {
                it[eventUuid] = event.eventUuid.value
                it[userCalendarUuid] = event.userCalendarUuid.value
                it[googleEventId] = event.googleEventId.value
                it[recurringEventId] = event.recurringEventId?.value
                it[originalStart] = event.originalStart
                it[title] = event.eventContent.title
                it[description] = event.eventContent.description
                it[location] = event.eventContent.location
                when (val schedule = event.eventContent.schedule) {
                    is Schedule.Timed -> {
                        it[startTime] = schedule.startTime
                        it[endTime] = schedule.endTime
                        it[startDate] = null
                        it[endDate] = null
                    }

                    is Schedule.AllDay -> {
                        it[startTime] = null
                        it[endTime] = null
                        it[startDate] = schedule.startDate
                        it[endDate] = schedule.endDate
                    }
                }
                it[remindTiming] = event.eventContent.remindTiming?.duration
            }
        }
    }

    private fun toDomain(row: ResultRow): Event {
        val startTime = row[EventsTable.startTime]
        val endTime = row[EventsTable.endTime]
        val startDate = row[EventsTable.startDate]
        val endDate = row[EventsTable.endDate]

        return either {
            val schedule: Schedule = when {
                startTime != null && endTime != null && startDate == null && endDate == null -> {
                    schedule(
                        startTime = startTime,
                        endTime = endTime
                    )
                }

                startTime == null && endTime == null && startDate != null && endDate != null -> {
                    schedule(
                        startDate = startDate,
                        endDate = endDate
                    )
                }
                else -> throw PersistedDataCorruption("Event の復元に失敗しました: $startTime, $endTime, $startDate, $endDate")
            }
            Event.reconstitute(
                eventUuid = EventUuid(row[EventsTable.eventUuid]),
                userCalendarUuid = UserCalendarUuid(row[EventsTable.userCalendarUuid]),
                googleEventId = googleEventId(row[EventsTable.googleEventId]),
                recurringEventId = row[EventsTable.recurringEventId]?.let { recurringEventId(it) },
                originalStart = row[EventsTable.originalStart],
                eventContent = EventContent(
                    title = row[EventsTable.title],
                    description = row[EventsTable.description],
                    location = row[EventsTable.location],
                    schedule = schedule,
                    remindTiming = row[EventsTable.remindTiming]?.let { remindTiming(it) }
                )
            )
        }.getOrElse { throw PersistedDataCorruption("Event の復元に失敗しました: ${it.code}") }
    }
}
