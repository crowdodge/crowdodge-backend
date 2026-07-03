package com.crowdodge.user.application.query

import arrow.core.left
import arrow.core.right
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarCredential
import com.crowdodge.user.application.port.GoogleCalendarCredentialStore
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.application.port.TokenCipher
import com.crowdodge.user.application.service.GoogleAccessTokenProvider
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleAccessToken
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import com.crowdodge.user.infrastructure.db.GoogleCalendarCredentialCalendarRow
import com.crowdodge.user.infrastructure.db.GoogleCalendarCredentialResolver
import com.crowdodge.user.infrastructure.db.GoogleCalendarCredentialTokenCodec
import com.crowdodge.user.infrastructure.db.GoogleCalendarCredentialTokenRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ResolveGoogleCalendarConnectionUseCaseTest : FunSpec({
    val userUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000001"))
    val otherUserUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000002"))
    val userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000001"))
    val otherUserCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000002"))
    test("有効な access token は refresh せず返す") {
        val store = FakeCredentialStore(
            userCalendarUuid to credential(userUuid, expiresAt = Instant.parse("2026-06-27T01:00:00Z")),
        )
        val useCase = ResolveGoogleCalendarConnectionUseCase(
            store = store,
            accessTokens = StaticTokenProvider("access-token"),
            transactionRunner = DirectTransactionRunner,
        )

        useCase.handle(userCalendarUuid).getOrNull() shouldBe
            GoogleCalendarConnection("calendar@example.com", "access-token")
    }

    test("GoogleCalendarConnectionの文字列表現へaccess tokenを露出しない") {
        val secret = "plain-text-secret-token"
        val connection = GoogleCalendarConnection("calendar@example.com", secret)

        connection.toString().contains(secret) shouldBe false
        connection.toString().contains("<redacted>") shouldBe true
    }

    test("指定した calendar 所有者の資格情報だけを返す") {
        val store = FakeCredentialStore(
            userCalendarUuid to credential(
                userUuid = userUuid,
                googleCalendarId = "calendar@example.com",
                accessToken = "access-token",
                expiresAt = Instant.parse("2026-06-27T01:00:00Z"),
            ),
            otherUserCalendarUuid to credential(
                userUuid = otherUserUuid,
                googleCalendarId = "other@example.com",
                accessToken = "other-access-token",
                expiresAt = Instant.parse("2026-06-27T01:00:00Z"),
            ),
        )
        val useCase = ResolveGoogleCalendarConnectionUseCase(
            store = store,
            accessTokens = StaticTokenProvider("other-access-token"),
            transactionRunner = DirectTransactionRunner,
        )

        useCase.handle(otherUserCalendarUuid).getOrNull() shouldBe
            GoogleCalendarConnection("other@example.com", "other-access-token")
    }

    listOf(
        UserError.AuthenticationError.InvalidRefreshToken,
        UserError.ExternalError.GoogleOAuthError,
    ).forEach { tokenFailure ->
        test("access token解決の${tokenFailure.code}を分類を保って返す") {
            val store = FakeCredentialStore(
                userCalendarUuid to credential(userUuid, expiresAt = Instant.parse("2026-06-27T01:00:00Z")),
            )
            val useCase = ResolveGoogleCalendarConnectionUseCase(
                store = store,
                accessTokens = StaticTokenProvider("unused", tokenFailure),
                transactionRunner = DirectTransactionRunner,
            )

            useCase.handle(userCalendarUuid).leftOrNull() shouldBe tokenFailure
        }
    }

    test("credential store codec は保存済み token を復号して返す") {
        val codec = GoogleCalendarCredentialTokenCodec(PrefixTokenCipher)

        codec.decryptAccessToken("encrypted:access-token") shouldBe "access-token"
        codec.decryptRefreshToken("encrypted:refresh-token") shouldBe "refresh-token"
    }

    test("credential store codec は refresh 後の access token を暗号化して保存する") {
        val codec = GoogleCalendarCredentialTokenCodec(PrefixTokenCipher)

        codec.encryptAccessToken("refreshed-access-token") shouldBe "encrypted:refreshed-access-token"
    }

    test("credential resolver は calendar 所有者の user credential を解決する") {
        val resolver = GoogleCalendarCredentialResolver(GoogleCalendarCredentialTokenCodec(PrefixTokenCipher))
        val expiresAt = Instant.parse("2026-06-27T01:00:00Z")

        resolver.resolve(
            calendar = GoogleCalendarCredentialCalendarRow(
                userCalendarUuid = userCalendarUuid,
                userUuid = userUuid,
                googleCalendarId = "calendar@example.com",
            ),
            credential = GoogleCalendarCredentialTokenRow(
                userUuid = userUuid,
                accessToken = "encrypted:access-token",
                refreshToken = "encrypted:refresh-token",
                expiresAt = expiresAt,
            ),
        ) shouldBe GoogleCalendarCredential(
            userUuid = userUuid,
            googleCalendarId = "calendar@example.com",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAt = expiresAt,
        )
    }

    test("credential resolver は別ユーザーの credential を calendar に結びつけない") {
        val resolver = GoogleCalendarCredentialResolver(GoogleCalendarCredentialTokenCodec(PrefixTokenCipher))

        resolver.resolve(
            calendar = GoogleCalendarCredentialCalendarRow(
                userCalendarUuid = userCalendarUuid,
                userUuid = userUuid,
                googleCalendarId = "calendar@example.com",
            ),
            credential = GoogleCalendarCredentialTokenRow(
                userUuid = otherUserUuid,
                accessToken = "encrypted:other-access-token",
                refreshToken = "encrypted:other-refresh-token",
                expiresAt = Instant.parse("2026-06-27T01:00:00Z"),
            ),
        ) shouldBe null
    }
})

private fun credential(
    userUuid: UserUuid,
    googleCalendarId: String = "calendar@example.com",
    accessToken: String = "access-token",
    expiresAt: Instant,
) = GoogleCalendarCredential(
    userUuid = userUuid,
    googleCalendarId = googleCalendarId,
    accessToken = accessToken,
    refreshToken = "refresh-token",
    expiresAt = expiresAt,
)

private class FakeCredentialStore(
    vararg credentials: Pair<UserCalendarUuid, GoogleCalendarCredential>,
) : GoogleCalendarCredentialStore {
    private val credentialsByCalendar = credentials.toMap()

    var updatedUserUuid: UserUuid? = null
    var updatedAccessToken: String? = null
    var updatedExpiresAt: Instant? = null

    override suspend fun find(userCalendarUuid: UserCalendarUuid): GoogleCalendarCredential? =
        credentialsByCalendar[userCalendarUuid]

    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: String,
        expiresAt: Instant,
    ) {
        updatedUserUuid = userUuid
        updatedAccessToken = accessToken
        updatedExpiresAt = expiresAt
    }
}

private object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class StaticTokenProvider(
    private val token: String,
    private val failure: UserError? = null,
) : GoogleAccessTokenProvider(
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
    transactions = DirectTransactionRunner,
) {
    override suspend fun get(userUuid: UserUuid) = failure?.left() ?: token.right()
}

private object PrefixTokenCipher : TokenCipher {
    override fun encrypt(plainText: String): String = "encrypted:$plainText"

    override fun decrypt(encodedCipherText: String): String =
        encodedCipherText.removePrefix("encrypted:")
}
