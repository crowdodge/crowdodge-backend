package com.crowdodge.user.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable

object UserDevicesTable : TimestampedTable("user_devices") {
    val deviceUuid = uuid("device_uuid")
    val userUuid = reference("user_uuid", UsersTable.userUuid)
    val fcmToken = text("fcm_token").uniqueIndex()
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(deviceUuid)
}
