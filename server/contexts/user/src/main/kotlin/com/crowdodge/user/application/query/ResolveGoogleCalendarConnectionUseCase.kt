package com.crowdodge.user.application.query

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.user.application.port.GoogleCalendarCredentialStore
import com.crowdodge.user.application.service.GoogleAccessTokenProvider
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.UserCalendarUuid

data class GoogleCalendarConnection(
    val googleCalendarId: String,
    val accessToken: String,
) {
    override fun toString(): String =
        "GoogleCalendarConnection(googleCalendarId=$googleCalendarId, accessToken=<redacted>)"
}

class ResolveGoogleCalendarConnectionUseCase(
    private val store: GoogleCalendarCredentialStore,
    private val accessTokens: GoogleAccessTokenProvider,
    private val transactionRunner: TransactionRunner,
) {
    @Suppress("ReturnCount")
    suspend fun handle(
        userCalendarUuid: UserCalendarUuid,
    ): Either<UserError, GoogleCalendarConnection> {
        val credential = transactionRunner.readOnly { store.find(userCalendarUuid) }
            ?: return UserError.ExternalError.GoogleOAuthError.left()
        val accessToken = accessTokens.get(credential.userUuid).fold(
            { return it.left() },
            { it },
        )
        return GoogleCalendarConnection(credential.googleCalendarId, accessToken).right()
    }
}
