package com.crowdodge.readmodel.congestion

import com.crowdodge.congestion.application.port.CongestionDestination
import com.crowdodge.congestion.application.port.CongestionGenerationCandidate
import com.crowdodge.congestion.application.port.CongestionGenerationReadModel
import com.crowdodge.congestion.application.port.CongestionGenerationSource
import com.crowdodge.congestion.application.port.CongestionRoute
import com.crowdodge.congestion.application.port.CongestionRouteStep
import com.crowdodge.congestion.application.port.SavedCongestionPeriod
import com.crowdodge.congestion.application.port.SavedForecast
import com.crowdodge.congestion.domain.model.EventUuid
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionForecastsTable
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionsTable
import com.crowdodge.distination.infrastructure.persistence.EventDestinationLinksTable
import com.crowdodge.distination.infrastructure.persistence.EventDestinationsTable
import com.crowdodge.event.infrastructure.persistence.EventsTable
import com.crowdodge.shared.kernel.AppTime
import com.crowdodge.shared.kernel.PersistedDataCorruption
import com.crowdodge.shared.kernel.TransactionRunner
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select
import kotlin.time.Instant

/** 混雑生成に必要な複数 BC の保存値を読み取り専用で射影する。 */
class ExposedCongestionGenerationReadModel(
    private val transactions: TransactionRunner,
) : CongestionGenerationReadModel {
    override suspend fun findAll(
        eventUuids: Set<EventUuid>,
    ): Map<EventUuid, CongestionGenerationCandidate> {
        if (eventUuids.isEmpty()) return emptyMap()

        return transactions.readOnly {
            EventsTable
                .join(
                    EventDestinationLinksTable,
                    JoinType.INNER,
                    onColumn = EventsTable.eventUuid,
                    otherColumn = EventDestinationLinksTable.eventUuid,
                )
                .join(
                    EventDestinationsTable,
                    JoinType.INNER,
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
                .select(
                    EventsTable.eventUuid,
                    EventsTable.startTime,
                    EventsTable.endTime,
                    EventsTable.startDate,
                    EventsTable.endDate,
                    EventDestinationsTable.destination,
                    EventDestinationsTable.destinationPoint,
                    EventDestinationsTable.routeDuration,
                    EventDestinationsTable.routeInformation,
                    EventCongestionForecastsTable.eventCongestionForecastUuid,
                    EventCongestionForecastsTable.eventUuid,
                    EventCongestionForecastsTable.generationInputHash,
                    EventCongestionForecastsTable.generatedAt,
                    EventCongestionsTable.eventCongestionUuid,
                    EventCongestionsTable.eventUuid,
                    EventCongestionsTable.congestionStartTime,
                    EventCongestionsTable.congestionEndTime,
                    EventCongestionsTable.area,
                    EventCongestionsTable.description,
                )
                .where { EventsTable.eventUuid inList eventUuids.map { it.value } }
                .toList()
                .groupBy { it[EventsTable.eventUuid] }
                .mapValues { (_, rows) -> toCandidate(rows) }
                .mapKeys { (eventUuid, _) -> EventUuid(eventUuid) }
        }
    }

    private fun toCandidate(rows: List<ResultRow>): CongestionGenerationCandidate {
        val first = rows.first()
        val eventUuid = EventUuid(first[EventsTable.eventUuid])
        val (start, end, isAllDay) = resolveSchedule(first)
        val source = CongestionGenerationSource(
            eventUuid = eventUuid,
            start = start,
            end = end,
            isAllDay = isAllDay,
            destination = CongestionDestination(
                name = first[EventDestinationsTable.destination],
                latitude = first[EventDestinationsTable.destinationPoint].latitude,
                longitude = first[EventDestinationsTable.destinationPoint].longitude,
            ),
            outboundRoute = first[EventDestinationsTable.routeInformation].let { route ->
                CongestionRoute(
                    steps = route.routeSteps.map { step ->
                        CongestionRouteStep(
                            fromName = step.fromName,
                            toName = step.toName,
                            lineName = step.lineName,
                            moveType = step.moveType,
                            callingAt = step.callingAt,
                        )
                    },
                )
            },
            travelDuration = first[EventDestinationsTable.routeDuration],
        )

        return CongestionGenerationCandidate(
            source = source,
            savedForecast = toSavedForecast(rows, eventUuid),
        )
    }

    private fun toSavedForecast(
        rows: List<ResultRow>,
        eventUuid: EventUuid,
    ): SavedForecast? {
        val forecastUuid = rows.first().getOrNull(EventCongestionForecastsTable.eventCongestionForecastUuid)
            ?: return null
        val forecastEventUuid = rows.first().getOrNull(EventCongestionForecastsTable.eventUuid)
        if (forecastEventUuid != eventUuid.value) {
            throw PersistedDataCorruption("forecastのevent_uuidが予定UUIDと一致しません")
        }

        val periods = rows.mapNotNull { row ->
            if (row.getOrNull(EventCongestionsTable.eventCongestionUuid) == null) return@mapNotNull null
            val childEventUuid = row[EventCongestionsTable.eventUuid]
            if (childEventUuid != eventUuid.value) {
                throw PersistedDataCorruption("congestionのevent_uuidが予定UUIDと一致しません")
            }
            SavedCongestionPeriod(
                start = row[EventCongestionsTable.congestionStartTime],
                end = row[EventCongestionsTable.congestionEndTime],
                area = row[EventCongestionsTable.area],
                description = row[EventCongestionsTable.description],
            )
        }.distinct()

        return SavedForecast(
            forecastUuid = com.crowdodge.congestion.domain.model.EventCongestionForecastUuid(forecastUuid),
            generationInputHash = rows.first()[EventCongestionForecastsTable.generationInputHash],
            generatedAt = rows.first()[EventCongestionForecastsTable.generatedAt],
            periods = periods,
        )
    }

    private fun resolveSchedule(row: ResultRow): ScheduleProjection {
        val startTime = row[EventsTable.startTime]
        val endTime = row[EventsTable.endTime]
        val startDate = row[EventsTable.startDate]
        val endDate = row[EventsTable.endDate]
        return when {
            startTime != null && endTime != null && startDate == null && endDate == null -> {
                ScheduleProjection(startTime, endTime, false)
            }

            startTime == null && endTime == null && startDate != null && endDate != null -> {
                ScheduleProjection(
                    start = AppTime.startOfBusinessDate(startDate),
                    end = AppTime.startOfBusinessDate(endDate),
                    isAllDay = true,
                )
            }

            else -> throw PersistedDataCorruption("予定の時刻フィールドが不正です")
        }
    }

    private data class ScheduleProjection(
        val start: Instant,
        val end: Instant,
        val isAllDay: Boolean,
    )
}
