package com.crowdodge.app.plugins

import arrow.core.Either
import arrow.core.left
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.command.AuthenticateWithGoogleUseCase
import com.crowdodge.user.application.command.LogoutUseCase
import com.crowdodge.user.application.command.RefreshSessionUseCase
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.application.port.GoogleAuthorization
import com.crowdodge.user.application.port.GoogleIdentity
import com.crowdodge.user.application.port.GoogleOAuthGateway
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.AuthRefreshTokenHash
import com.crowdodge.user.domain.model.GoogleId
import com.crowdodge.user.domain.model.User
import com.crowdodge.user.domain.model.UserAuthRefreshToken
import com.crowdodge.user.domain.model.UserAuthRefreshTokenUuid
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import com.crowdodge.user.domain.repository.UserRepository
import com.crowdodge.user.infrastructure.security.JwtAppTokenAdapter
import com.crowdodge.user.presentation.APP_JWT_AUTH_NAME
import com.crowdodge.user.presentation.configureUserRouting
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import java.time.Clock as JavaClock
import java.time.Instant as JavaInstant

class AuthenticationTest : FunSpec({
    val json = Json { ignoreUnknownKeys = true }
    val now = Instant.parse("2026-06-28T00:00:00Z")
    val jwtConfig = JwtAppTokenConfig(
        issuer = "crowdodge-api",
        audience = "crowdodge-app",
        secret = "12345678901234567890123456789012",
        accessTokenTtl = 15.minutes,
        refreshTokenTtl = 30.days,
    )

    test("POST /auth/google は Google 認証成功時に 200 とアプリ token を返す") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "authorizationCode": "ok-code",
                      "redirectUri": "com.crowdodge:/oauth2redirect",
                      "codeVerifier": "pkce-verifier"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString(TokenResponseBody.serializer(), response.bodyAsText())
            body.accessToken shouldNotBe ""
            body.refreshToken shouldNotBe ""
            body.tokenType shouldBe "Bearer"
            body.expiresIn shouldBe 900
        }
    }

    test("POST /auth/google は redirectUri と codeVerifier を省略した serverAuthCode フローでも 200 を返す") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "authorizationCode": "ok-code"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString(TokenResponseBody.serializer(), response.bodyAsText())
            body.accessToken shouldNotBe ""
            body.refreshToken shouldNotBe ""
        }
    }

    test("POST /auth/google は空文字の redirectUri を 400 Problem で拒否する") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "authorizationCode": "ok-code",
                      "redirectUri": " ",
                      "codeVerifier": "pkce-verifier"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
            response.bodyAsText() shouldContain "\"field\":\"redirectUri\""
        }
    }

    test("POST /auth/google は不正 code を 401 Problem へ変換する") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "authorizationCode": "bad-code",
                      "redirectUri": "com.crowdodge:/oauth2redirect",
                      "codeVerifier": "pkce-verifier"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
            response.bodyAsText() shouldContain "\"code\":\"INVALID_GOOGLE_TOKEN\""
            response.bodyAsText() shouldContain
                "\"type\":\"https://crowdodge.grfsv.net/problems/INVALID_GOOGLE_TOKEN\""
        }
    }

    test("POST /auth/google は request body 受信中のキャンセルを 400 Problem に変換しない") {
        val cancellation = CancellationException("request receive cancelled")

        testApplication {
            application {
                receivePipeline.intercept(ApplicationReceivePipeline.Transform) {
                    throw cancellation
                }
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "authorizationCode": "ok-code",
                      "redirectUri": "com.crowdodge:/oauth2redirect",
                      "codeVerifier": "pkce-verifier"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.InternalServerError
        }
    }

    test("POST /auth/google は scope 不足を 403 Problem へ変換する") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "authorizationCode": "missing-scope",
                      "redirectUri": "com.crowdodge:/oauth2redirect",
                      "codeVerifier": "pkce-verifier"
                    }
                    """.trimIndent(),
                )
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
            response.bodyAsText() shouldContain "\"code\":\"MISSING_GOOGLE_SCOPE\""
            response.bodyAsText() shouldContain
                "\"type\":\"https://crowdodge.grfsv.net/problems/MISSING_GOOGLE_SCOPE\""
        }
    }

    test("POST /auth/google は空文字と過大入力を 400 Problem で拒否する") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val blankResponse = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "authorizationCode": "",
                      "redirectUri": "com.crowdodge:/oauth2redirect",
                      "codeVerifier": "pkce-verifier"
                    }
                    """.trimIndent(),
                )
            }
            val oversizedResponse = client.post("/auth/google") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "authorizationCode": "${"a".repeat(4097)}",
                      "redirectUri": "com.crowdodge:/oauth2redirect",
                      "codeVerifier": "pkce-verifier"
                    }
                    """.trimIndent(),
                )
            }

            blankResponse.status shouldBe HttpStatusCode.BadRequest
            blankResponse.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
            blankResponse.bodyAsText() shouldContain "\"code\":\"VALIDATION_ERROR\""
            blankResponse.bodyAsText() shouldContain "\"message\":\"MUST_NOT_BE_BLANK\""
            oversizedResponse.status shouldBe HttpStatusCode.BadRequest
            oversizedResponse.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
            oversizedResponse.bodyAsText() shouldContain "\"code\":\"VALIDATION_ERROR\""
            oversizedResponse.bodyAsText() shouldContain "\"message\":\"MUST_BE_AT_MOST_2048_CHARS\""
        }
    }

    test("POST /auth/refresh は refresh token をローテーションする") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val login = login(json)
            val firstRefreshToken = login.refreshToken

            val response = client.post("/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"$firstRefreshToken"}""")
            }

            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString(TokenResponseBody.serializer(), response.bodyAsText())
            body.accessToken shouldNotBe ""
            body.refreshToken shouldNotBe firstRefreshToken
            body.expiresIn shouldBe 900
        }
    }

    test("POST /auth/logout は 204 を返す") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val login = login(json)
            val response = client.post("/auth/logout") {
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"${login.refreshToken}"}""")
            }

            response.status shouldBe HttpStatusCode.NoContent
        }
    }

    test("保護 route は JWT なしで 401 Problem を返す") {
        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.get("/auth/me")

            response.status shouldBe HttpStatusCode.Unauthorized
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
        }
    }

    test("保護 route は正しい JWT の principal UserUuid を返す") {
        val userUuid = UserUuid.new()
        val token = JwtAppTokenAdapter(jwtConfig, FixedClock(now)).issueAccessToken(userUuid)

        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.get("/auth/me") {
                bearerAuth(token)
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain userUuid.value.toString()
        }
    }

    test("保護 route は期限切れ JWT を 401 Problem で拒否する") {
        val userUuid = UserUuid.new()
        val token = appJwt(
            jwtConfig = jwtConfig,
            subject = userUuid.value.toString(),
            expiresAt = now - 1.minutes,
        )

        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.get("/auth/me") {
                bearerAuth(token)
            }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
        }
    }

    test("保護 route は署名違い JWT を 401 Problem で拒否する") {
        val userUuid = UserUuid.new()
        val token = appJwt(
            jwtConfig = jwtConfig.copy(secret = "abcdefghijklmnopqrstuvwxyz123456"),
            subject = userUuid.value.toString(),
            expiresAt = now + 15.minutes,
        )

        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.get("/auth/me") {
                bearerAuth(token)
            }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
        }
    }

    test("保護 route は issuer audience 不一致 JWT を 401 Problem で拒否する") {
        val userUuid = UserUuid.new()
        val token = appJwt(
            jwtConfig = jwtConfig.copy(issuer = "other-issuer", audience = "other-audience"),
            subject = userUuid.value.toString(),
            expiresAt = now + 15.minutes,
        )

        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.get("/auth/me") {
                bearerAuth(token)
            }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
        }
    }

    test("保護 route は UUID ではない sub の JWT を 401 Problem で拒否する") {
        val token = appJwt(
            jwtConfig = jwtConfig,
            subject = "not-a-uuid",
            expiresAt = now + 15.minutes,
        )

        testApplication {
            application {
                configureAuthenticationTestApp(
                    now = now,
                    jwtConfig = jwtConfig,
                    googleOAuthGateway = FakeGoogleOAuthGateway(),
                )
            }

            val response = client.get("/auth/me") {
                bearerAuth(token)
            }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
        }
    }

    test("auth plugin は短い JWT secret では起動しない") {
        shouldThrow<IllegalArgumentException> {
            testApplication {
                application {
                    install(Koin) {
                        modules(
                            module {
                                single {
                                    jwtConfig.copy(secret = "short-secret")
                                }
                            },
                        )
                    }
                    configureAuthentication(now.toJavaClock())
                    routing {
                        authenticate(APP_JWT_AUTH_NAME) {
                            get("/protected") { call.respondText("ok") }
                        }
                    }
                }

                client.get("/protected")
            }
        }
    }
})

private fun Application.configureAuthenticationTestApp(
    now: Instant,
    jwtConfig: JwtAppTokenConfig,
    googleOAuthGateway: GoogleOAuthGateway,
) {
    val appTokenPort = JwtAppTokenAdapter(jwtConfig, FixedClock(now))
    install(Koin) {
        modules(
            module {
                single<JwtAppTokenConfig> { jwtConfig }
                single<GoogleOAuthGateway> { googleOAuthGateway }
                single<UserRepository> { InMemoryUserRepository() }
                single<UserCalendarRepository> { InMemoryUserCalendarRepository() }
                single<UserGoogleCredentialRepository> { InMemoryUserGoogleCredentialRepository() }
                single<UserAuthRefreshTokenRepository> { InMemoryUserAuthRefreshTokenRepository() }
                single<TransactionRunner> { ImmediateTransactionRunner() }
                single<AppTokenPort> { appTokenPort }
                single { AuthenticateWithGoogleUseCase(get(), get(), get(), get(), get(), get(), get()) }
                single { RefreshSessionUseCase(get(), get(), get(), clock = FixedClock(now)) }
                single { LogoutUseCase(get(), get(), get(), clock = FixedClock(now)) }
            },
        )
    }
    configureSerialization()
    configureAuthentication(now.toJavaClock())
    configureUserRouting()
}

private fun appJwt(
    jwtConfig: JwtAppTokenConfig,
    subject: String,
    expiresAt: Instant,
): String =
    JWT.create()
        .withSubject(subject)
        .withIssuer(jwtConfig.issuer)
        .withAudience(jwtConfig.audience)
        .withIssuedAt(JavaInstant.parse("2026-06-28T00:00:00Z"))
        .withExpiresAt(JavaInstant.parse(expiresAt.toString()))
        .sign(Algorithm.HMAC256(jwtConfig.secret))

private fun Instant.toJavaClock(): JavaClock =
    JavaClock.fixed(JavaInstant.parse(toString()), ZoneOffset.UTC)

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.login(json: Json): TokenResponseBody {
    val response = client.post("/auth/google") {
        contentType(ContentType.Application.Json)
        setBody(
            """
            {
              "authorizationCode": "ok-code",
              "redirectUri": "com.crowdodge:/oauth2redirect",
              "codeVerifier": "pkce-verifier"
            }
            """.trimIndent(),
        )
    }
    response.status shouldBe HttpStatusCode.OK
    return json.decodeFromString(TokenResponseBody.serializer(), response.bodyAsText())
}

@Serializable
private data class TokenResponseBody(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Long,
)

private class ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class FixedClock(private val now: Instant) : Clock {
    override fun now(): Instant = now
}

private class FakeGoogleOAuthGateway : GoogleOAuthGateway {
    override suspend fun exchange(
        authorizationCode: String,
        redirectUri: String?,
        codeVerifier: String?,
    ): Either<UserError, GoogleAuthorization> = when (authorizationCode) {
        "bad-code" -> UserError.AuthenticationError.InvalidGoogleToken.left()
        "missing-scope" -> UserError.AuthenticationError.MissingGoogleScope.left()
        else -> Either.Right(
            GoogleAuthorization(
                identity = GoogleIdentity(
                    googleSubject = "google-subject-1",
                    email = "user@example.com",
                ),
                accessToken = "google-access-token",
                refreshToken = "google-refresh-token",
                expiresAt = Instant.parse("2026-06-28T01:00:00Z"),
                grantedScopes = setOf(
                    "openid",
                    "email",
                    "profile",
                    "https://www.googleapis.com/auth/calendar.events",
                    "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                ),
            ),
        )
    }
}

private class InMemoryUserRepository : UserRepository {
    private val usersByUuid = linkedMapOf<UserUuid, User>()
    private val usersByGoogleId = linkedMapOf<String, User>()

    override suspend fun create(user: User): Either<UserError.ConflictError.DuplicateEmail, Unit> {
        usersByUuid[user.userUuid] = user
        usersByGoogleId[user.googleId.value] = user
        return Either.Right(Unit)
    }

    override suspend fun update(user: User): Either<UserError.ConflictError.DuplicateEmail, Unit> {
        usersByUuid[user.userUuid] = user
        usersByGoogleId[user.googleId.value] = user
        return Either.Right(Unit)
    }

    override suspend fun findByUserUuid(userUuid: UserUuid): User? = usersByUuid[userUuid]

    override suspend fun findByGoogleId(googleId: GoogleId): User? = usersByGoogleId[googleId.value]
}

private class InMemoryUserCalendarRepository : UserCalendarRepository {
    private val calendarsByUser = linkedMapOf<UserUuid, MutableList<UserCalendar>>()

    override suspend fun create(userCalendar: UserCalendar): Either<UserError.ConflictError.DuplicateCalendar, Unit> {
        val calendars = calendarsByUser.getOrPut(userCalendar.userUuid) { mutableListOf() }
        if (calendars.any { it.googleCalendarId == userCalendar.googleCalendarId }) {
            return UserError.ConflictError.DuplicateCalendar.left()
        }
        calendars += userCalendar
        return Either.Right(Unit)
    }

    override suspend fun delete(userUuid: UserUuid, userCalendarUuid: UserCalendarUuid) {
        calendarsByUser[userUuid]?.removeIf { it.userCalendarUuid == userCalendarUuid }
    }

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserCalendar> = calendarsByUser[userUuid].orEmpty()
}

private class InMemoryUserGoogleCredentialRepository : UserGoogleCredentialRepository {
    private val credentials = linkedMapOf<UserUuid, UserGoogleCredential>()

    override suspend fun findByUserUuid(userUuid: UserUuid): UserGoogleCredential? = credentials[userUuid]

    override suspend fun upsert(credential: UserGoogleCredential) {
        credentials[credential.userUuid] = credential
    }

    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: com.crowdodge.user.domain.model.GoogleAccessToken,
        accessTokenExpiresAt: Instant,
    ) {
        val current = credentials[userUuid] ?: return
        current.reauthorize(
            newAccessToken = accessToken,
            newRefreshToken = current.refreshToken,
            newExpiresAt = accessTokenExpiresAt,
            scopes = current.grantedScopes,
        )
    }
}

private class InMemoryUserAuthRefreshTokenRepository : UserAuthRefreshTokenRepository {
    private val tokens = linkedMapOf<UserAuthRefreshTokenUuid, UserAuthRefreshToken>()

    override suspend fun create(refreshToken: UserAuthRefreshToken) {
        tokens[refreshToken.refreshTokenUuid] = refreshToken
    }

    override suspend fun findByHash(tokenHash: AuthRefreshTokenHash): UserAuthRefreshToken? =
        tokens.values.firstOrNull { it.tokenHash == tokenHash }

    override suspend fun consumeUsableByHash(
        tokenHash: AuthRefreshTokenHash,
        now: Instant,
    ): UserAuthRefreshToken? {
        val current = tokens.values.firstOrNull { it.tokenHash == tokenHash && it.isUsable(now) } ?: return null
        current.revoke(now)
        tokens[current.refreshTokenUuid] = current
        return current
    }

    override suspend fun revoke(refreshTokenUuid: UserAuthRefreshTokenUuid, revokedAt: Instant) {
        val current = tokens[refreshTokenUuid] ?: return
        current.revoke(revokedAt)
        tokens[refreshTokenUuid] = current
    }
}
