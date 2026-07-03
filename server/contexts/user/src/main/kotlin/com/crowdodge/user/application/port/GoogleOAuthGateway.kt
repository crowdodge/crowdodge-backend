package com.crowdodge.user.application.port

import arrow.core.Either
import com.crowdodge.user.domain.error.UserError
import kotlin.time.Instant

data class GoogleIdentity(
    val googleSubject: String,
    val email: String,
)

data class GoogleAuthorization(
    val identity: GoogleIdentity,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant,
    val grantedScopes: Set<String>,
)

fun interface GoogleOAuthGateway {
    suspend fun exchange(
        authorizationCode: String,
        redirectUri: String,
        codeVerifier: String,
    ): Either<UserError, GoogleAuthorization>
}
