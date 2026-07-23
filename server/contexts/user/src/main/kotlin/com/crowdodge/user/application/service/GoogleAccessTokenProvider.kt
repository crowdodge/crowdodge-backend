package com.crowdodge.user.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.application.port.hasRequiredGoogleCalendarScopes
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

open class GoogleAccessTokenProvider(
    private val credentials: UserGoogleCredentialRepository,
    private val refreshGateway: GoogleOAuthTokenRefreshGateway,
    private val transactions: TransactionRunner,
    private val clock: Clock = Clock.System,
) {
    @Suppress("ReturnCount")
    open suspend fun get(userUuid: UserUuid): Either<UserError, String> {
        val credential = transactions.readOnly { credentials.findByUserUuid(userUuid) }
            ?: return UserError.AuthenticationError.InvalidRefreshToken.left()
        if (!credential.grantedScopes.hasRequiredGoogleCalendarScopes()) {
            return UserError.AuthenticationError.MissingGoogleScope.left()
        }
        if (credential.accessTokenExpiresAt > clock.now() + REFRESH_SKEW) {
            return Either.Right(credential.accessToken.value)
        }
        val refreshToken = credential.refreshToken
            ?: return UserError.AuthenticationError.InvalidRefreshToken.left()
        val refreshed = refreshGateway.refresh(refreshToken.value).fold(
            { return it.left() },
            { it },
        )
        val accessToken = either { googleAccessToken(refreshed.accessToken) }
            .getOrNull()
            ?: return UserError.AuthenticationError.InvalidRefreshToken.left()
        transactions.inTransaction {
            credentials.updateAccessToken(userUuid, accessToken, refreshed.expiresAt)
        }
        return Either.Right(refreshed.accessToken)
    }

    private companion object {
        val REFRESH_SKEW = 1.minutes
    }
}
