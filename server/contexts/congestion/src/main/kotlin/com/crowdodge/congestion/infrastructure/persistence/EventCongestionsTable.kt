package com.crowdodge.congestion.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.instantTimestampWithTimeZone
import org.jetbrains.exposed.v1.core.ReferenceOption

/** 混雑予測に含まれる個別の混雑期間を保持するテーブル。 */
object EventCongestionsTable : TimestampedTable("event_congestions") {
    val eventCongestionUuid = uuid("event_congestion_uuid")
    val eventUuid = uuid("event_uuid") // 参照: events.event_uuid
    val eventCongestionForecastUuid = reference(
        "event_congestion_forecast_uuid",
        EventCongestionForecastsTable.eventCongestionForecastUuid,
        onDelete = ReferenceOption.CASCADE,
    )
    val congestionStartTime = instantTimestampWithTimeZone("congestion_start_time")
    val congestionEndTime = instantTimestampWithTimeZone("congestion_end_time")
    val area = text("area")
    val description = text("description")
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(eventCongestionUuid)
}
