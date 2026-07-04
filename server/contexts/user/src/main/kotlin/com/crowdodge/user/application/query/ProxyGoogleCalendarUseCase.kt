package com.crowdodge.user.application.query

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.CalendarProxyRequest
import com.crowdodge.user.application.port.CalendarProxyResponse
import com.crowdodge.user.application.port.GoogleCalendarProxyGateway
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class ProxyGoogleCalendarUseCase(
    private val gateway: GoogleCalendarProxyGateway,
    private val credentialRepository: UserGoogleCredentialRepository,
    private val refreshGateway: GoogleOAuthTokenRefreshGateway,
    private val transactionRunner: TransactionRunner,
    private val calendars: UserCalendarRepository,
    private val clock: Clock = Clock.System,
) {
    @Suppress("ReturnCount")
    suspend fun handle(userUuid: UserUuid, request: CalendarProxyRequest): CalendarProxyResponse {
        val selected = transactionRunner.readOnly {
            calendars.findByUserUuid(userUuid).any { it.googleCalendarId.value == request.calendarId }
        }
        if (!selected) return forbiddenResponse()
        val credential = transactionRunner.readOnly { credentialRepository.findByUserUuid(userUuid) }
            ?: return unauthorizedResponse()
        val accessToken = if (credential.accessTokenExpiresAt <= clock.now() + REFRESH_SKEW) {
            refresh(userUuid, credential.refreshToken?.value).fold(
                { return refreshFailureResponse(it) },
                { it },
            )
        } else {
            credential.accessToken.value
        }
        var refreshFailure: UserError? = null
        val response = gateway.proxy(request, accessToken) {
            refresh(userUuid, credential.refreshToken?.value).fold(
                {
                    refreshFailure = it
                    null
                },
                { it },
            )
        }
        return refreshFailure?.let(::refreshFailureResponse) ?: response
    }

    @Suppress("ReturnCount")
    private suspend fun refresh(userUuid: UserUuid, refreshToken: String?): Either<UserError, String> {
        val token = refreshToken ?: return UserError.AuthenticationError.InvalidRefreshToken.left()
        val refreshed = refreshGateway.refresh(token).fold({ return it.left() }, { it })
        val accessToken = either { googleAccessToken(refreshed.accessToken) }.getOrNull()
            ?: return UserError.AuthenticationError.InvalidRefreshToken.left()
        transactionRunner.inTransaction {
            credentialRepository.updateAccessToken(userUuid, accessToken, refreshed.expiresAt)
        }
        return refreshed.accessToken.right()
    }

    private fun unauthorizedResponse() =
        CalendarProxyResponse(status = 401, contentType = null, body = byteArrayOf())

    private fun refreshFailureResponse(error: UserError) =
        CalendarProxyResponse(
            status = when (error) {
                is UserError.AuthenticationError -> 401
                UserError.ExternalError.GoogleCalendarTimeoutError -> 504
                else -> 502
            },
            contentType = null,
            body = byteArrayOf(),
        )

    private fun forbiddenResponse() =
        CalendarProxyResponse(status = 403, contentType = null, body = byteArrayOf())

    private companion object {
        val REFRESH_SKEW = 1.minutes
    }
}
