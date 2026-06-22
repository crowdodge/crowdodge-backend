package com.crowdodge.user.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.geographyPoint
import org.jetbrains.exposed.v1.datetime.duration

object UserSettingsTable : TimestampedTable("user_settings") {
    val userUuid = reference("user_uuid", UsersTable.userUuid)
    val home = geographyPoint("home")
    val remindTiming = duration("remind_timing")
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(userUuid)
}
