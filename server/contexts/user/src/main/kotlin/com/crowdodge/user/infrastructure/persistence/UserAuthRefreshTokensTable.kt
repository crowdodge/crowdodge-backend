package com.crowdodge.user.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.instantTimestampWithTimeZone
import org.jetbrains.exposed.v1.core.ReferenceOption

object UserAuthRefreshTokensTable : TimestampedTable("user_auth_refresh_tokens") {
    private const val TOKEN_HASH_LENGTH = 64

    val refreshTokenUuid = uuid("refresh_token_uuid")
    val userUuid = reference(
        "user_uuid",
        UsersTable.userUuid,
        onDelete = ReferenceOption.CASCADE,
    ).index()
    val tokenHash = varchar("token_hash", TOKEN_HASH_LENGTH).uniqueIndex()
    val expiresAt = instantTimestampWithTimeZone("expires_at")
    val revokedAt = instantTimestampWithTimeZone("revoked_at").nullable()

    override val primaryKey: PrimaryKey
        get() = PrimaryKey(refreshTokenUuid)
}
