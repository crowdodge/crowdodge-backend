package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.EntityUuid
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import kotlin.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class UserAuthRefreshTokenUuid(override val value: Uuid) : EntityUuid {
    companion object {
        fun new(): UserAuthRefreshTokenUuid = UserAuthRefreshTokenUuid(Uuid.random())
    }
}

@JvmInline
value class AuthRefreshTokenHash private constructor(val value: String) {
    companion object {
        private const val HASH_LENGTH = 64

        fun Raise<UserError.ValidationError>.authRefreshTokenHash(value: String): AuthRefreshTokenHash {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { UserError.ValidationError.BlankAuthRefreshTokenHash }
            ensure(trimmed.length == HASH_LENGTH) { UserError.ValidationError.InvalidAuthRefreshTokenHash }
            return AuthRefreshTokenHash(trimmed)
        }
    }
}

class UserAuthRefreshToken(
    val refreshTokenUuid: UserAuthRefreshTokenUuid,
    val userUuid: UserUuid,
    val tokenHash: AuthRefreshTokenHash,
    val expiresAt: Instant,
    revokedAt: Instant?,
) {
    var revokedAt: Instant? = revokedAt
        private set

    fun isUsable(now: Instant): Boolean = revokedAt == null && now < expiresAt

    fun revoke(now: Instant) {
        if (revokedAt == null) {
            revokedAt = now
        }
    }
}
