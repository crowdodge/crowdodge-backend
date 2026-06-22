package com.crowdodge.distination.infrastructure.persistence

import com.crowdodge.distination.infrastructure.RouteInformation
import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.geographyPoint
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.datetime.duration
import org.jetbrains.exposed.v1.json.jsonb

object EventDestinationsTable : TimestampedTable("event_destinations") {
    val eventDestinationUuid = uuid("event_destination_uuid")
    val recurringEventId = text("recurring_event_id").nullable().uniqueIndex()
    val destination = text("destination")
    val destinationPoint = geographyPoint("destination_point")
    val routeDuration = duration("route_duration")
    val routeInformation = jsonb<RouteInformation>("route_information", Json)
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(eventDestinationUuid)
}
