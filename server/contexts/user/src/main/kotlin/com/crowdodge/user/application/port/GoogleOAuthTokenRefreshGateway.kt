package com.crowdodge.user.application.port

import arrow.core.Either
import com.crowdodge.user.domain.error.UserError
import kotlin.time.Instant

data class RefreshedGoogleToken(
    val accessToken: String,
    val expiresAt: Instant,
)

fun interface GoogleOAuthTokenRefreshGateway {
    suspend fun refresh(refreshToken: String): Either<UserError, RefreshedGoogleToken>
}
