package com.crowdodge.app.calendar

import arrow.core.left
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarCredential
import com.crowdodge.user.application.port.GoogleCalendarCredentialStore
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.application.query.ResolveGoogleCalendarConnectionUseCase
import com.crowdodge.user.application.service.GoogleAccessTokenProvider
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleAccessToken
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.crowdodge.event.domain.model.UserCalendarUuid as EventUserCalendarUuid

class UserCalendarConnectionAdapterTest : FunSpec({
    test("User BCのGoogle Calendar timeoutをEvent BCのtimeoutへ変換する") {
        val userUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000001"))
        val userCalendarUuid = UserCalendarUuid(Uuid.parse("20000000-0000-0000-0000-000000000001"))
        val resolve = ResolveGoogleCalendarConnectionUseCase(
            store = object : GoogleCalendarCredentialStore {
                override suspend fun find(userCalendarUuid: UserCalendarUuid) =
                    GoogleCalendarCredential(
                        userUuid = userUuid,
                        googleCalendarId = "calendar",
                        accessToken = "access",
                        refreshToken = "refresh",
                        expiresAt = Instant.parse("2026-07-02T00:00:00Z"),
                    )

                override suspend fun updateAccessToken(
                    userUuid: UserUuid,
                    accessToken: String,
                    expiresAt: Instant,
                ) = Unit
            },
            accessTokens = TimeoutTokenProvider,
            transactionRunner = AdapterDirectTransactions,
        )

        UserCalendarConnectionAdapter(resolve)
            .connection(EventUserCalendarUuid(userCalendarUuid.value))
            .leftOrNull() shouldBe EventError.ExternalError.GoogleCalendarTimeoutError
    }
})

private object TimeoutTokenProvider : GoogleAccessTokenProvider(
    credentials = object : UserGoogleCredentialRepository {
        override suspend fun findByUserUuid(userUuid: UserUuid): UserGoogleCredential? = null
        override suspend fun upsert(credential: UserGoogleCredential) = Unit
        override suspend fun updateAccessToken(
            userUuid: UserUuid,
            accessToken: GoogleAccessToken,
            accessTokenExpiresAt: Instant,
        ) = Unit
    },
    refreshGateway = GoogleOAuthTokenRefreshGateway { error("unused") },
    transactions = AdapterDirectTransactions,
) {
    override suspend fun get(userUuid: UserUuid) =
        UserError.ExternalError.GoogleCalendarTimeoutError.left()
}

private object AdapterDirectTransactions : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}
