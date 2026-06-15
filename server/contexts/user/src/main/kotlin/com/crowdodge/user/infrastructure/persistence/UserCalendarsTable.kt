package com.crowdodge.user.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable

object UserCalendarsTable : TimestampedTable("user_calendars") {
    val userCalendarUuid = uuid("user_calendar_uuid")
    val userUuid = reference("user_uuid", UsersTable.userUuid)
    val googleCalendarId = text("google_calendar_id")
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(userCalendarUuid)
    init {
        index(true, userUuid, googleCalendarId)
    }
}
