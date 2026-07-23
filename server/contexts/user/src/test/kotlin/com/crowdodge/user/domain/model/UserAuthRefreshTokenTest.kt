package com.crowdodge.user.domain.model

import arrow.core.raise.either
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.AuthRefreshTokenHash.Companion.authRefreshTokenHash
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class UserAuthRefreshTokenTest : FunSpec({
    test("isUsable は未失効かつ期限前の場合だけ true を返す") {
        val token = UserAuthRefreshToken(
            refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
            userUuid = UserUuid.new(),
            tokenHash = validAuthRefreshTokenHash("a".repeat(64)),
            expiresAt = Instant.parse("2026-02-01T00:00:00Z"),
            revokedAt = null,
        )

        token.isUsable(Instant.parse("2026-01-31T23:59:59Z")) shouldBe true
        token.isUsable(Instant.parse("2026-02-01T00:00:00Z")) shouldBe false
    }

    test("revoke は失効日時を設定して token を利用不能にする") {
        val token = UserAuthRefreshToken(
            refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
            userUuid = UserUuid.new(),
            tokenHash = validAuthRefreshTokenHash("b".repeat(64)),
            expiresAt = Instant.parse("2026-02-01T00:00:00Z"),
            revokedAt = null,
        )
        val revokedAt = Instant.parse("2026-01-15T00:00:00Z")

        token.revoke(revokedAt)

        token.revokedAt shouldBe revokedAt
        token.isUsable(Instant.parse("2026-01-16T00:00:00Z")) shouldBe false
    }

    test("AuthRefreshTokenHash は blank と 64 文字以外を拒否する") {
        either { authRefreshTokenHash("   ") }.shouldBeLeft() shouldBe
            UserError.ValidationError.BlankAuthRefreshTokenHash
        either { authRefreshTokenHash("a".repeat(63)) }.shouldBeLeft() shouldBe
            UserError.ValidationError.InvalidAuthRefreshTokenHash
        either { authRefreshTokenHash("a".repeat(65)) }.shouldBeLeft() shouldBe
            UserError.ValidationError.InvalidAuthRefreshTokenHash
    }

    test("revoke は二重呼び出しでも初回の失効日時を維持する") {
        val token = UserAuthRefreshToken(
            refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
            userUuid = UserUuid.new(),
            tokenHash = validAuthRefreshTokenHash("c".repeat(64)),
            expiresAt = Instant.parse("2026-02-01T00:00:00Z"),
            revokedAt = null,
        )
        val firstRevokedAt = Instant.parse("2026-01-15T00:00:00Z")
        val secondRevokedAt = Instant.parse("2026-01-16T00:00:00Z")

        token.revoke(firstRevokedAt)
        token.revoke(secondRevokedAt)

        token.revokedAt shouldBe firstRevokedAt
    }

    test("repository contract は hash 検索に AuthRefreshTokenHash を要求する") {
        val tokenHash = validAuthRefreshTokenHash("d".repeat(64))
        val repository = object : UserAuthRefreshTokenRepository {
            override suspend fun create(refreshToken: UserAuthRefreshToken) = Unit

            override suspend fun findByHash(tokenHash: AuthRefreshTokenHash): UserAuthRefreshToken? = null

            override suspend fun consumeUsableByHash(
                tokenHash: AuthRefreshTokenHash,
                now: Instant,
            ): UserAuthRefreshToken? = null

            override suspend fun revoke(refreshTokenUuid: UserAuthRefreshTokenUuid, revokedAt: Instant) = Unit
        }

        repository.findByHash(tokenHash) shouldBe null
    }
})

private fun validAuthRefreshTokenHash(value: String): AuthRefreshTokenHash =
    either { authRefreshTokenHash(value) }.getOrNull() ?: error("invalid test token hash")
