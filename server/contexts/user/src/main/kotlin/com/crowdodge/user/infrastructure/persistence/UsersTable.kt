package com.crowdodge.user.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable

object UsersTable : TimestampedTable("users") {
    private const val GOOGLE_ID_LENGTH = 255
    val userUuid = uuid("user_uuid")
    val googleId = varchar("google_id", GOOGLE_ID_LENGTH)
    val email = text("email")
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(userUuid)
}
