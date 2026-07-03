package com.crowdodge.user.infrastructure.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.crowdodge.shared.kernel.UserUuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import java.time.Instant as JavaInstant

class JwtAppTokenAdapterTest : FunSpec({
    val now = Instant.parse("2026-06-28T01:02:03Z")
    val secret = "12345678901234567890123456789012"
    val userUuid = UserUuid.new()

    test("access JWT は sub iss aud iat exp を持つ HMAC-SHA256 token を発行する") {
        val adapter = JwtAppTokenAdapter(
            config = JwtAppTokenConfig(
                issuer = "crowdodge-api",
                audience = "crowdodge-app",
                secret = secret,
                accessTokenTtl = 15.minutes,
                refreshTokenTtl = 30.days,
            ),
            clock = FixedClock(now),
            randomBytes = { ByteArray(it) { index -> index.toByte() } },
        )

        val token = adapter.issueAccessToken(userUuid)
        val verification = JWT.require(Algorithm.HMAC256(secret))
            .withIssuer("crowdodge-api")
            .withAudience("crowdodge-app")
        val decoded = (verification as JWTVerifier.BaseVerification)
            .build(java.time.Clock.fixed(JavaInstant.parse(now.toString()), ZoneOffset.UTC))
            .verify(token)

        decoded.subject shouldBe userUuid.value.toString()
        decoded.issuedAtAsInstant shouldBe JavaInstant.parse(now.toString())
        decoded.expiresAtAsInstant shouldBe JavaInstant.parse((now + 15.minutes).toString())
    }

    test("refresh token は 32 random bytes の Base64URL 平文と SHA-256 lowercase hex hash を発行する") {
        val adapter = JwtAppTokenAdapter(
            config = JwtAppTokenConfig(
                issuer = "crowdodge-api",
                audience = "crowdodge-app",
                secret = secret,
                accessTokenTtl = 15.minutes,
                refreshTokenTtl = 30.days,
            ),
            clock = FixedClock(now),
            randomBytes = { ByteArray(it) { index -> index.toByte() } },
        )

        val refreshToken = adapter.issueRefreshToken(userUuid)

        refreshToken.plainText shouldBe "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        refreshToken.hash.value shouldBe "ea866a757e4c38babfa8127cbe9a409d3e1f93a00ff1488ff735fcf917afffd0"
        refreshToken.expiresAt shouldBe now + 30.days
        refreshToken.hash.value shouldNotBe refreshToken.plainText
    }

    test("秘密鍵が 32 bytes 未満なら構築時に失敗する") {
        shouldThrow<IllegalArgumentException> {
            JwtAppTokenAdapter(
                config = JwtAppTokenConfig(
                    issuer = "crowdodge-api",
                    audience = "crowdodge-app",
                    secret = "short-secret",
                    accessTokenTtl = 15.minutes,
                    refreshTokenTtl = 30.days,
                ),
            )
        }
    }

    test("access token TTL が非正なら構築時に失敗する") {
        shouldThrow<IllegalArgumentException> {
            JwtAppTokenAdapter(
                config = JwtAppTokenConfig(
                    issuer = "crowdodge-api",
                    audience = "crowdodge-app",
                    secret = secret,
                    accessTokenTtl = Duration.ZERO,
                    refreshTokenTtl = 30.days,
                ),
            )
        }
    }

    test("refresh token TTL が非正なら構築時に失敗する") {
        shouldThrow<IllegalArgumentException> {
            JwtAppTokenAdapter(
                config = JwtAppTokenConfig(
                    issuer = "crowdodge-api",
                    audience = "crowdodge-app",
                    secret = secret,
                    accessTokenTtl = 15.minutes,
                    refreshTokenTtl = Duration.ZERO,
                ),
            )
        }
    }
})

private class FixedClock(private val now: Instant) : Clock {
    override fun now(): Instant = now
}
