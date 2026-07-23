package com.crowdodge.congestion.infrastructure.db

import com.crowdodge.congestion.domain.model.EventCongestionForecast
import com.crowdodge.congestion.domain.repository.EventCongestionForecastRepository
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionForecastsTable
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionsTable
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update

/** Exposed を使って混雑予測集約を保存する。 */
class ExposedEventCongestionForecastRepository : EventCongestionForecastRepository {
    override suspend fun replace(forecast: EventCongestionForecast) {
        val existing = EventCongestionForecastsTable
            .selectAll()
            .where { EventCongestionForecastsTable.eventUuid eq forecast.eventUuid.value }
            .firstOrNull()
        val forecastUuid = existing?.get(EventCongestionForecastsTable.eventCongestionForecastUuid)
            ?: forecast.eventCongestionForecastUuid.value

        EventCongestionsTable.deleteWhere {
            EventCongestionsTable.eventCongestionForecastUuid eq forecastUuid
        }
        if (existing == null) {
            EventCongestionForecastsTable.insert {
                it[eventCongestionForecastUuid] = forecastUuid
                it[EventCongestionForecastsTable.eventUuid] = forecast.eventUuid.value
                it[EventCongestionForecastsTable.generationInputHash] = forecast.generationInputHash.value
                it[generatedAt] = forecast.generatedAt
                it[createdAt] = forecast.generatedAt
                it[updatedAt] = forecast.generatedAt
            }
        } else {
            EventCongestionForecastsTable.update(
                where = { EventCongestionForecastsTable.eventCongestionForecastUuid eq forecastUuid },
            ) {
                it[EventCongestionForecastsTable.generationInputHash] = forecast.generationInputHash.value
                it[generatedAt] = forecast.generatedAt
                it[updatedAt] = forecast.generatedAt
            }
        }
        forecast.periods.forEach { period ->
            EventCongestionsTable.insert {
                it[eventCongestionUuid] = kotlin.uuid.Uuid.random()
                it[EventCongestionsTable.eventCongestionForecastUuid] = forecastUuid
                it[EventCongestionsTable.eventUuid] = forecast.eventUuid.value
                it[congestionStartTime] = period.start
                it[congestionEndTime] = period.end
                it[area] = period.area
                it[description] = period.description
                it[createdAt] = forecast.generatedAt
                it[updatedAt] = forecast.generatedAt
            }
        }
    }
}
