package com.crowdodge.event.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.duration
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object EventsTable : TimestampedTable("events") {
    val eventUuid = uuid("event_uuid")
    val userCalendarUuid = uuid("user_calendar_uuid") // 参照: user_calendars.user_calendar_uuid
    val googleEventId = text("google_event_id").uniqueIndex()
    val recurringEventId = text("recurring_event_id").nullable()
    val originalStart = timestampWithTimeZone("original_start").nullable()
    val etag = text("etag").nullable()
    val title = text("title").nullable()
    val description = text("description").nullable()
    val location = text("location").nullable()
    val startTime = timestampWithTimeZone("start_time").nullable()
    val endTime = timestampWithTimeZone("end_time").nullable()
    val startDate = date("start_date").nullable()
    val endDate = date("end_date").nullable()
    val remindTiming = duration("remind_timing").nullable()
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(eventUuid)
}
