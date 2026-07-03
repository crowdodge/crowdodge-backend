package com.crowdodge.user.application.port

import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.AuthRefreshTokenHash
import kotlin.time.Instant

data class AppRefreshToken(
    val plainText: String,
    val hash: AuthRefreshTokenHash,
    val expiresAt: Instant,
)

interface AppTokenPort {
    fun issueRefreshToken(userUuid: UserUuid): AppRefreshToken

    fun hashRefreshToken(plainText: String): AuthRefreshTokenHash

    fun issueAccessToken(userUuid: UserUuid): String
}
