package com.crowdodge.user.application.service

import arrow.core.left
import arrow.core.right
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.application.port.RefreshedGoogleToken
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleAccessToken
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.model.GoogleRefreshToken.Companion.googleRefreshToken
import com.crowdodge.user.domain.model.GoogleSubject.Companion.googleSubject
import com.crowdodge.user.domain.model.GrantedGoogleScopes.Companion.grantedGoogleScopes
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GoogleAccessTokenProviderTest : FunSpec({
    val now = Instant.parse("2026-07-02T00:00:00Z")
    val userUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000001"))

    test("有効tokenをrefreshせず再利用する") {
        val repository = FakeCredentialRepository(credential(userUuid, "token", "2026-07-02T01:00:00Z"))
        var refreshCount = 0
        val provider = GoogleAccessTokenProvider(
            repository,
            GoogleOAuthTokenRefreshGateway {
                refreshCount++
                RefreshedGoogleToken("new", Instant.parse("2026-07-02T02:00:00Z")).right()
            },
            DirectTransactions,
            FixedClock(now),
        )

        provider.get(userUuid).getOrNull() shouldBe "token"
        refreshCount shouldBe 0
    }

    test("期限接近時にrefreshして保存する") {
        val repository = FakeCredentialRepository(credential(userUuid, "old", "2026-07-02T00:00:30Z"))
        val expiry = Instant.parse("2026-07-02T02:00:00Z")
        val provider = GoogleAccessTokenProvider(
            repository,
            GoogleOAuthTokenRefreshGateway { RefreshedGoogleToken("new", expiry).right() },
            DirectTransactions,
            FixedClock(now),
        )

        provider.get(userUuid).getOrNull() shouldBe "new"
        repository.updated shouldBe Triple(userUuid, "new", expiry)
    }

    test("期限接近時にrefresh tokenがなければ失敗する") {
        val repository = FakeCredentialRepository(
            credential(userUuid, "old", "2026-07-02T00:00:30Z", refreshToken = null),
        )
        val provider = GoogleAccessTokenProvider(
            repository,
            GoogleOAuthTokenRefreshGateway { error("呼ばれない") },
            DirectTransactions,
            FixedClock(now),
        )

        provider.get(userUuid).isLeft() shouldBe true
    }

    test("保存済みscopeが不足していたらtokenを再利用もrefreshもせずMissingGoogleScopeを返す") {
        val repository = FakeCredentialRepository(
            credential(
                userUuid,
                "token",
                "2026-07-02T01:00:00Z",
                scopes = "https://www.googleapis.com/auth/calendar.events",
            ),
        )
        var refreshCount = 0
        val provider = GoogleAccessTokenProvider(
            repository,
            GoogleOAuthTokenRefreshGateway {
                refreshCount++
                error("呼ばれない")
            },
            DirectTransactions,
            FixedClock(now),
        )

        provider.get(userUuid).leftOrNull() shouldBe UserError.AuthenticationError.MissingGoogleScope
        refreshCount shouldBe 0
    }

    listOf(
        UserError.AuthenticationError.InvalidRefreshToken,
        UserError.ExternalError.GoogleOAuthError,
    ).forEach { refreshFailure ->
        test("refresh失敗の${refreshFailure.code}を分類を保って返す") {
            val repository = FakeCredentialRepository(
                credential(userUuid, "old", "2026-07-02T00:00:30Z"),
            )
            val provider = GoogleAccessTokenProvider(
                repository,
                GoogleOAuthTokenRefreshGateway { refreshFailure.left() },
                DirectTransactions,
                FixedClock(now),
            )

            provider.get(userUuid).leftOrNull() shouldBe refreshFailure
        }
    }
})

private fun credential(
    userUuid: UserUuid,
    accessToken: String,
    expiresAt: String,
    refreshToken: String? = "refresh",
    scopes: String =
        "https://www.googleapis.com/auth/calendar.events " +
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
): UserGoogleCredential = arrow.core.raise.either {
    UserGoogleCredential(
        userUuid,
        googleSubject("subject"),
        googleAccessToken(accessToken),
        refreshToken?.let { googleRefreshToken(it) },
        Instant.parse(expiresAt),
        grantedGoogleScopes(scopes),
    )
}.getOrNull()!!

private class FakeCredentialRepository(
    private val credential: UserGoogleCredential?,
) : UserGoogleCredentialRepository {
    var updated: Triple<UserUuid, String, Instant>? = null
    override suspend fun findByUserUuid(userUuid: UserUuid) = credential
    override suspend fun upsert(credential: UserGoogleCredential) = Unit
    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: GoogleAccessToken,
        accessTokenExpiresAt: Instant,
    ) {
        updated = Triple(userUuid, accessToken.value, accessTokenExpiresAt)
    }
}

private object DirectTransactions : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}
