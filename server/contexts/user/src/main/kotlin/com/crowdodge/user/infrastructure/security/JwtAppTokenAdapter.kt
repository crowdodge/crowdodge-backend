package com.crowdodge.user.infrastructure.security

import arrow.core.getOrElse
import arrow.core.raise.either
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.AppRefreshToken
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.domain.model.AuthRefreshTokenHash
import com.crowdodge.user.domain.model.AuthRefreshTokenHash.Companion.authRefreshTokenHash
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration
import java.time.Instant as JavaInstant

data class JwtAppTokenConfig(
    val issuer: String,
    val audience: String,
    val secret: String,
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
)

fun JwtAppTokenConfig.hmacAlgorithm(): Algorithm {
    require(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
        "JWT app token secret must be at least $MIN_SECRET_BYTES bytes"
    }
    require(accessTokenTtl.isPositive()) { "JWT access token TTL must be positive" }
    require(refreshTokenTtl.isPositive()) { "JWT refresh token TTL must be positive" }
    return Algorithm.HMAC256(secret)
}

class JwtAppTokenAdapter(
    private val config: JwtAppTokenConfig,
    private val clock: Clock = Clock.System,
    private val randomBytes: (Int) -> ByteArray = secureRandomBytes(),
) : AppTokenPort {
    private val algorithm: Algorithm = config.hmacAlgorithm()

    override fun issueRefreshToken(userUuid: UserUuid): AppRefreshToken {
        val plainText = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(REFRESH_TOKEN_BYTES))
        return AppRefreshToken(
            plainText = plainText,
            hash = hashRefreshToken(plainText),
            expiresAt = clock.now() + config.refreshTokenTtl,
        )
    }

    override fun hashRefreshToken(plainText: String): AuthRefreshTokenHash {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(plainText.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and BYTE_MASK) }
        return either { authRefreshTokenHash(digest) }
            .getOrElse { error("SHA-256 digest must be a valid refresh token hash") }
    }

    override fun issueAccessToken(userUuid: UserUuid): String {
        val issuedAt = clock.now()
        val expiresAt = issuedAt + config.accessTokenTtl
        return JWT.create()
            .withSubject(userUuid.value.toString())
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withIssuedAt(Date.from(JavaInstant.parse(issuedAt.toString())))
            .withExpiresAt(Date.from(JavaInstant.parse(expiresAt.toString())))
            .sign(algorithm)
    }

    private companion object {
        const val REFRESH_TOKEN_BYTES = 32

        fun secureRandomBytes(): (Int) -> ByteArray {
            val secureRandom = SecureRandom()
            return { size -> ByteArray(size).also(secureRandom::nextBytes) }
        }
    }
}

private const val MIN_SECRET_BYTES = 32
private const val BYTE_MASK = 0xff
