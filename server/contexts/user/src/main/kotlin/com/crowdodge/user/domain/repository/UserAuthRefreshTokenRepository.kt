package com.crowdodge.user.domain.repository

import com.crowdodge.user.domain.model.AuthRefreshTokenHash
import com.crowdodge.user.domain.model.UserAuthRefreshToken
import com.crowdodge.user.domain.model.UserAuthRefreshTokenUuid
import kotlin.time.Instant

interface UserAuthRefreshTokenRepository {
    suspend fun create(refreshToken: UserAuthRefreshToken)

    suspend fun findByHash(tokenHash: AuthRefreshTokenHash): UserAuthRefreshToken?

    suspend fun consumeUsableByHash(tokenHash: AuthRefreshTokenHash, now: Instant): UserAuthRefreshToken?

    suspend fun revoke(refreshTokenUuid: UserAuthRefreshTokenUuid, revokedAt: Instant)
}
