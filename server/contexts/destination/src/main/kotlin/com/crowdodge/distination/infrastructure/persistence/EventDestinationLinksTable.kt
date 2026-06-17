package com.crowdodge.distination.infrastructure.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object EventDestinationLinksTable : Table("event_destination_links") {
    val eventUuid = uuid("event_uuid") // 参照: events.event_uuid
    val eventDestinationUuid = reference("event_destination_uuid", EventDestinationsTable.eventDestinationUuid)
    val createdAt = timestamp("created_at")
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(eventUuid)
}
