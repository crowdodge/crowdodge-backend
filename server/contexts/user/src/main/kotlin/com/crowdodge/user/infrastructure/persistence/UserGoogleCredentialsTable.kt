package com.crowdodge.user.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.instantTimestampWithTimeZone
import org.jetbrains.exposed.v1.core.ReferenceOption

object UserGoogleCredentialsTable : TimestampedTable("user_google_credentials") {
    private const val GOOGLE_SUBJECT_LENGTH = 255

    val userUuid = reference(
        "user_uuid",
        UsersTable.userUuid,
        onDelete = ReferenceOption.CASCADE,
    )
    val googleSubject = varchar("google_subject", GOOGLE_SUBJECT_LENGTH).uniqueIndex()
    val accessToken = text("access_token")
    val refreshToken = text("refresh_token").nullable()
    val accessTokenExpiresAt = instantTimestampWithTimeZone("access_token_expires_at")
    val grantedScopes = text("granted_scopes")

    override val primaryKey: PrimaryKey
        get() = PrimaryKey(userUuid)
}
