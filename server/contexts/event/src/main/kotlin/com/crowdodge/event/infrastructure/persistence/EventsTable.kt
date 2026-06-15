package com.crowdodge.event.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.interval
import org.jetbrains.exposed.v1.datetime.timestamp

object EventsTable : TimestampedTable("events") {
    val eventUuid = uuid("event_uuid")
    val userCalendarUuid = uuid("user_calendar_uuid") // 参照: user_calendars.user_calendar_uuid
    val googleEventId = text("google_event_id").uniqueIndex()
    val recurringEventId = text("recurring_event_id").nullable()
    val originalStart = timestamp("original_start").nullable()
    val etag = text("etag").nullable()
    val title = text("title")
    val description = text("description").nullable()
    val location = text("location").nullable()
    val startTime = timestamp("start_time")
    val endTime = timestamp("end_time")
    val isAllDay = bool("is_all_day")
    val remindTiming = interval("remind_timing").nullable()
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(eventUuid)
}
