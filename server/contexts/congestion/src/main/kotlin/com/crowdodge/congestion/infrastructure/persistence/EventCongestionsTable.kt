package com.crowdodge.congestion.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import org.jetbrains.exposed.v1.datetime.timestamp

object EventCongestionsTable : TimestampedTable("event_congestions") {
    val eventCongestionUuid = uuid("event_congestion_uuid")
    val eventUuid = uuid("event_uuid") // 参照: events.event_uuid
    val congestionStartTime = timestamp("congestion_start_time")
    val congestionEndTime = timestamp("congestion_end_time")
    val description = text("description")
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(eventCongestionUuid)
}
