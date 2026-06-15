package com.crowdodge.event.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object EventCalendarSyncsTable : TimestampedTable("event_calendar_syncs") {
    val userCalendarUuid = uuid("user_calendar_uuid") // 参照: user_calendars.user_calendar_uuid
    val materializedUntil = timestampWithTimeZone("materialized_until").nullable()
    val watchChannelId = text("watch_channel_id").nullable().uniqueIndex()
    val watchResourceId = text("watch_resource_id").nullable()
    val watchChannelToken = text("watch_channel_token").nullable()
    val watchExpiration = timestampWithTimeZone("watch_expiration").nullable().index()
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(userCalendarUuid)
}
