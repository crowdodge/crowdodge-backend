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
    /**
     * 認可コードを Google token endpoint で交換する。
     * 主フローは Google Sign-In SDK の serverAuthCode で、[redirectUri] と [codeVerifier] は null。
     * デバッグ用 PKCE フローでのみ両方を渡す。
     */
    suspend fun exchange(
        authorizationCode: String,
        redirectUri: String?,
        codeVerifier: String?,
    ): Either<UserError, GoogleAuthorization>
}
