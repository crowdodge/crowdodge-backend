package com.crowdodge.event.infrastructure.persistence

import com.crowdodge.shared.infra.db.instantTimestampWithTimeZone
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.time.Clock
import kotlin.time.Instant

abstract class EventTimestampedTable(name: String) : Table(name) {
    val createdAt: Column<Instant> =
        instantTimestampWithTimeZone("created_at").clientDefault { Clock.System.now() }
    val updatedAt: Column<Instant> =
        instantTimestampWithTimeZone("updated_at").clientDefault { Clock.System.now() }
}
