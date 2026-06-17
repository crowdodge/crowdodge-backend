package com.crowdodge.infrastrcuture.persistence

import com.crowdodge.shared.infra.db.TimestampedTable

object NotificationSchedulesTable : TimestampedTable("notification_schedules") {
    val notificationScheduleUuid = uuid("notification_schedules_uuid")
    val userUuid = uuid("user_uuid") // 参照: users.user_uuid
    val eventUuid = uuid("event_uuid") // 参照: events.event_uuid
    val kind = text("kind") // 予定前リマインドor混雑アラート
    val status = text("status") // pending / processing / completed / failed / canceled
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(notificationScheduleUuid)
}
