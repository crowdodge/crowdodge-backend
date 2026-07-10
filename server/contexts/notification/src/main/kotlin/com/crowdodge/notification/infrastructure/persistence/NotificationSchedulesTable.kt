package com.crowdodge.notification.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.instantTimestampWithTimeZone

object NotificationSchedulesTable : TimestampedTable("notification_schedules") {
    val notificationScheduleUuid = uuid("notification_schedules_uuid")
    val userUuid = uuid("user_uuid") // 参照: users.user_uuid（BC またぎのため物理 FK なし）
    val eventUuid = uuid("event_uuid") // 参照: events.event_uuid（BC またぎのため物理 FK なし）
    val notificateTime = instantTimestampWithTimeZone("notificate_time")
    val kind = text("kind") // Reminder / CongestionAlert
    val status = text("status") // pending / processing / completed / failed / canceled
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(notificationScheduleUuid)

    init {
        index(false, status, notificateTime)
        index(false, eventUuid)
    }
}
