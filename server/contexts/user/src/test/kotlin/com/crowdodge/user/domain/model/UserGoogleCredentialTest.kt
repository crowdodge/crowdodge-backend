package com.crowdodge.user.domain.model

import arrow.core.raise.either
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.model.GoogleRefreshToken.Companion.googleRefreshToken
import com.crowdodge.user.domain.model.GoogleSubject.Companion.googleSubject
import com.crowdodge.user.domain.model.GrantedGoogleScopes.Companion.grantedGoogleScopes
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class UserGoogleCredentialTest : FunSpec({
    test("reauthorize は新しい refresh token が null の場合に既存値を維持する") {
        val credential = UserGoogleCredential(
            userUuid = UserUuid.new(),
            googleSubject = validGoogleSubject(),
            accessToken = validGoogleAccessToken("old-access-token"),
            refreshToken = validGoogleRefreshToken("old-refresh-token"),
            accessTokenExpiresAt = Instant.parse("2026-01-01T00:00:00Z"),
            grantedScopes = validGrantedGoogleScopes("openid calendar.readonly"),
        )

        credential.reauthorize(
            newAccessToken = validGoogleAccessToken("new-access-token"),
            newRefreshToken = null,
            newExpiresAt = Instant.parse("2026-02-01T00:00:00Z"),
            scopes = validGrantedGoogleScopes("openid calendar"),
        )

        credential.accessToken shouldBe validGoogleAccessToken("new-access-token")
        credential.refreshToken shouldBe validGoogleRefreshToken("old-refresh-token")
        credential.accessTokenExpiresAt shouldBe Instant.parse("2026-02-01T00:00:00Z")
        credential.grantedScopes shouldBe validGrantedGoogleScopes("openid calendar")
    }

    test("reauthorize は新しい refresh token がある場合に置き換える") {
        val credential = UserGoogleCredential(
            userUuid = UserUuid.new(),
            googleSubject = validGoogleSubject(),
            accessToken = validGoogleAccessToken("old-access-token"),
            refreshToken = validGoogleRefreshToken("old-refresh-token"),
            accessTokenExpiresAt = Instant.parse("2026-01-01T00:00:00Z"),
            grantedScopes = validGrantedGoogleScopes("openid"),
        )

        credential.reauthorize(
            newAccessToken = validGoogleAccessToken("new-access-token"),
            newRefreshToken = validGoogleRefreshToken("new-refresh-token"),
            newExpiresAt = Instant.parse("2026-02-01T00:00:00Z"),
            scopes = validGrantedGoogleScopes("openid calendar"),
        )

        credential.refreshToken shouldBe validGoogleRefreshToken("new-refresh-token")
    }

    test("Google 認証情報 VO は blank を拒否する") {
        either { googleSubject("   ") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankGoogleSubject
        either { googleAccessToken("   ") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankGoogleAccessToken
        either { googleRefreshToken("   ") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankGoogleRefreshToken
        either { grantedGoogleScopes("   ") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankGrantedGoogleScopes
    }

    test("repository contract は access token 更新に GoogleAccessToken を要求する") {
        val userUuid = UserUuid.new()
        val accessToken = validGoogleAccessToken("new-access-token")
        val expiresAt = Instant.parse("2026-02-01T00:00:00Z")
        val repository = object : UserGoogleCredentialRepository {
            override suspend fun findByUserUuid(userUuid: UserUuid): UserGoogleCredential? = null

            override suspend fun upsert(credential: UserGoogleCredential) = Unit

            override suspend fun updateAccessToken(
                userUuid: UserUuid,
                accessToken: GoogleAccessToken,
                accessTokenExpiresAt: Instant,
            ) = Unit
        }

        repository.updateAccessToken(userUuid, accessToken, expiresAt)
    }
})

private fun validGoogleSubject(value: String = "google-subject"): GoogleSubject =
    either { googleSubject(value) }.getOrNull() ?: error("invalid test google subject")

private fun validGoogleAccessToken(value: String): GoogleAccessToken =
    either { googleAccessToken(value) }.getOrNull() ?: error("invalid test access token")

private fun validGoogleRefreshToken(value: String): GoogleRefreshToken =
    either { googleRefreshToken(value) }.getOrNull() ?: error("invalid test refresh token")

private fun validGrantedGoogleScopes(value: String): GrantedGoogleScopes =
    either { grantedGoogleScopes(value) }.getOrNull() ?: error("invalid test scopes")
