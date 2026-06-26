package com.crowdodge.shared.infra.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.datetime.OffsetDateTimeColumnType
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant

class InstantTimestampWithTimeZoneColumnType : OffsetDateTimeColumnType<Instant>() {
    override fun toOffsetDateTime(value: Instant): OffsetDateTime =
        OffsetDateTime.ofInstant(java.time.Instant.parse(value.toString()), ZoneOffset.UTC)

    override fun fromOffsetDateTime(datetime: OffsetDateTime): Instant =
        Instant.parse(datetime.toInstant().toString())
}

fun Table.instantTimestampWithTimeZone(name: String): Column<Instant> =
    registerColumn(name, InstantTimestampWithTimeZoneColumnType())
