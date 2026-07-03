package com.crowdodge.user.infrastructure.google

import arrow.core.Either
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.crowdodge.user.application.port.GoogleAuthorization
import com.crowdodge.user.application.port.GoogleIdentity
import com.crowdodge.user.domain.error.UserError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import java.time.Instant as JavaInstant

class GoogleOAuthGatewayTest : FunSpec({
    test("GoogleOAuthConfig は blank clientId を拒否する") {
        shouldThrow<IllegalArgumentException> {
            GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = " ",
                clientSecret = "",
            )
        }
    }

    test("予定操作とCalendar List取得scopeが揃っていれば交換に成功する") {
        val keys = rsaKeyPair()
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/token" -> {
                    request.method shouldBe HttpMethod.Post
                    val form = request.body as FormDataContent
                    val parameters: Parameters = form.formData
                    parameters.getAll("grant_type") shouldBe listOf("authorization_code")
                    parameters.getAll("code") shouldBe listOf("code")
                    parameters.getAll("client_id") shouldBe listOf("google-client-id")
                    parameters.getAll("redirect_uri") shouldBe listOf("app:/callback")
                    parameters.getAll("code_verifier") shouldBe listOf("verifier")
                    parameters.get("client_secret") shouldBe null

                    respond(
                        content = """
                            {
                              "access_token":"google-access",
                              "refresh_token":"google-refresh",
                              "expires_in":3600,
                              "scope":"openid email profile https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                              "id_token":"${idToken(keys, audience = "google-client-id")}"
                            }
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

                "/jwks" -> {
                    request.method shouldBe HttpMethod.Get
                    respond(
                        content = jwks(keys),
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                            HttpHeaders.CacheControl to listOf(CacheControl.MaxAge(maxAgeSeconds = 300).toString()),
                        ),
                    )
                }

                else -> error("unexpected request: ${request.method.value} ${request.url}")
            }
        }

        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(engine),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        val result = gateway.exchange("code", "app:/callback", "verifier")

        result shouldBe Either.Right(
            GoogleAuthorization(
                identity = GoogleIdentity(
                    googleSubject = "google-sub",
                    email = "user@example.com",
                ),
                accessToken = "google-access",
                refreshToken = "google-refresh",
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
        result.getOrNull()!!.identity.googleSubject shouldBe "google-sub"
        result.getOrNull()!!.identity.email shouldBe "user@example.com"
        requests shouldBe listOf("POST /token", "GET /jwks")
    }

    test("audience不一致のID tokenを拒否する") {
        val keys = rsaKeyPair()
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/token" -> respond(
                            content = """
                                {
                                  "access_token":"google-access",
                                  "refresh_token":"google-refresh",
                                  "expires_in":3600,
                                  "scope":"openid email https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                                  "id_token":"${idToken(keys, audience = "different-client-id")}"
                                }
                            """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                        "/jwks" -> respond(
                            content = jwks(keys),
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                                HttpHeaders.CacheControl to listOf(CacheControl.MaxAge(maxAgeSeconds = 300).toString()),
                            ),
                        )

                        else -> error("unexpected request: ${request.url}")
                    }
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.exchange("code", "app:/callback", "verifier").leftOrNull() shouldBe
            UserError.AuthenticationError.InvalidGoogleToken
    }

    test("scopeに calendar.events がなければ拒否する") {
        val keys = rsaKeyPair()
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/token" -> respond(
                            content = """
                                {
                                  "access_token":"google-access",
                                  "refresh_token":"google-refresh",
                                  "expires_in":3600,
                                  "scope":"openid email profile https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                                  "id_token":"${idToken(keys, audience = "google-client-id")}"
                                }
                            """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                        "/jwks" -> respond(
                            content = jwks(keys),
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                                HttpHeaders.CacheControl to listOf(CacheControl.MaxAge(maxAgeSeconds = 300).toString()),
                            ),
                        )

                        else -> error("unexpected request: ${request.url}")
                    }
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.exchange("code", "app:/callback", "verifier").leftOrNull() shouldBe
            UserError.AuthenticationError.MissingGoogleScope
    }

    test("scopeに calendar.calendarlist.readonly がなければ拒否する") {
        val keys = rsaKeyPair()
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/token" -> respond(
                            content = """
                                {
                                  "access_token":"google-access",
                                  "refresh_token":"google-refresh",
                                  "expires_in":3600,
                                  "scope":"openid email profile https://www.googleapis.com/auth/calendar.events",
                                  "id_token":"${idToken(keys, audience = "google-client-id")}"
                                }
                            """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                        "/jwks" -> respond(
                            content = jwks(keys),
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                                HttpHeaders.CacheControl to listOf(CacheControl.MaxAge(maxAgeSeconds = 300).toString()),
                            ),
                        )

                        else -> error("unexpected request: ${request.url}")
                    }
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.exchange("code", "app:/callback", "verifier").leftOrNull() shouldBe
            UserError.AuthenticationError.MissingGoogleScope
    }

    test("token endpoint の 400 は GoogleOAuthError を返す") {
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":"invalid_client"}""",
                        status = io.ktor.http.HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.exchange("code", "app:/callback", "verifier").leftOrNull() shouldBe
            UserError.ExternalError.GoogleOAuthError
    }

    test("exchange は token endpoint 呼び出し中の CancellationException を再throwする") {
        val cancellation = CancellationException("exchange cancelled")
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine {
                    throw cancellation
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        shouldThrow<CancellationException> {
            gateway.exchange("code", "app:/callback", "verifier")
        } shouldBe cancellation
    }

    test("token endpoint の 401 は GoogleOAuthError を返す") {
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":"invalid_client"}""",
                        status = io.ktor.http.HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.exchange("code", "app:/callback", "verifier").leftOrNull() shouldBe
            UserError.ExternalError.GoogleOAuthError
    }

    test("exchange は JWKS 取得中の CancellationException を再throwする") {
        val keys = rsaKeyPair()
        val cancellation = CancellationException("jwks cancelled")
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/token" -> respond(
                            content = """
                                {
                                  "access_token":"google-access",
                                  "refresh_token":"google-refresh",
                                  "expires_in":3600,
                                  "scope":"openid email https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                                  "id_token":"${idToken(keys, audience = "google-client-id")}"
                                }
                            """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                        "/jwks" -> throw cancellation

                        else -> error("unexpected request: ${request.url}")
                    }
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        shouldThrow<CancellationException> {
            gateway.exchange("code", "app:/callback", "verifier")
        } shouldBe cancellation
    }

    test("JWKS取得失敗はGoogleOAuthErrorを返す") {
        val keys = rsaKeyPair()
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/token" -> respond(
                            content = """
                                {
                                  "access_token":"google-access",
                                  "refresh_token":"google-refresh",
                                  "expires_in":3600,
                                  "scope":"openid email https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                                  "id_token":"${idToken(keys, audience = "google-client-id")}"
                                }
                            """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                        "/jwks" -> respond(
                            content = "service unavailable",
                            status = io.ktor.http.HttpStatusCode.ServiceUnavailable,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                        )

                        else -> error("unexpected request: ${request.url}")
                    }
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.exchange("code", "app:/callback", "verifier").leftOrNull() shouldBe
            UserError.ExternalError.GoogleOAuthError
    }

    test("JWKSはCache-Controlの有効期限内で再利用する") {
        val keys = rsaKeyPair()
        var jwksRequests = 0
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/token" -> respond(
                            content = """
                                {
                                  "access_token":"google-access",
                                  "refresh_token":"google-refresh",
                                  "expires_in":3600,
                                  "scope":"openid email https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                                  "id_token":"${idToken(keys, audience = "google-client-id")}"
                                }
                            """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                        "/jwks" -> {
                            jwksRequests += 1
                            respond(
                                content = jwks(keys),
                                headers = headersOf(
                                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                                    HttpHeaders.CacheControl to listOf(
                                        CacheControl.MaxAge(maxAgeSeconds = 300).toString(),
                                    ),
                                ),
                            )
                        }

                        else -> error("unexpected request: ${request.url}")
                    }
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.exchange("code-1", "app:/callback", "verifier")
        gateway.exchange("code-2", "app:/callback", "verifier")

        jwksRequests shouldBe 1
    }

    test("refresh は client_secret が空なら送信しない") {
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine { request ->
                    request.method shouldBe HttpMethod.Post
                    val form = request.body as FormDataContent
                    val parameters: Parameters = form.formData
                    parameters.getAll("client_id") shouldBe listOf("google-client-id")
                    parameters.get("client_secret") shouldBe null
                    parameters.getAll("refresh_token") shouldBe listOf("google-refresh")
                    parameters.getAll("grant_type") shouldBe listOf("refresh_token")

                    respond(
                        content = """
                            {
                              "access_token":"refreshed-access",
                              "expires_in":3600
                            }
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.refresh("google-refresh") shouldBe
            Either.Right(
                com.crowdodge.user.application.port.RefreshedGoogleToken(
                    accessToken = "refreshed-access",
                    expiresAt = Instant.parse("2026-06-28T01:00:00Z"),
                ),
            )
    }

    test("refresh はinvalid_grant拒否をGoogleOAuthErrorへ変換する") {
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":"invalid_grant"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.refresh("google-refresh").leftOrNull() shouldBe UserError.ExternalError.GoogleOAuthError
    }

    test("refresh はinvalid_grant以外の400をGoogleOAuthErrorへ変換する") {
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":"invalid_client"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.refresh("google-refresh").leftOrNull() shouldBe UserError.ExternalError.GoogleOAuthError
    }

    test("refresh は5xxをGoogleOAuthErrorへ変換する") {
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine {
                    respond("""{"error":"server_error"}""", HttpStatusCode.ServiceUnavailable)
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.refresh("google-refresh").leftOrNull() shouldBe UserError.ExternalError.GoogleOAuthError
    }

    test("refresh は token endpoint 呼び出し中の CancellationException を再throwする") {
        val cancellation = CancellationException("cancelled")
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine {
                    throw cancellation
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        shouldThrow<CancellationException> {
            gateway.refresh("google-refresh")
        } shouldBe cancellation
    }

    test("refresh は token endpoint 呼び出し中の通常例外を GoogleOAuthError に変換する") {
        val gateway = GoogleOAuthTokenGateway(
            config = GoogleOAuthConfig(
                tokenUrl = "https://oauth.example/token",
                jwksUrl = "https://oauth.example/jwks",
                clientId = "google-client-id",
                clientSecret = "",
            ),
            httpClient = HttpClient(
                MockEngine {
                    throw IllegalStateException("request failed")
                },
            ),
            clock = fixedClock("2026-06-28T00:00:00Z"),
        )

        gateway.refresh("google-refresh") shouldBe
            Either.Left(UserError.ExternalError.GoogleOAuthError)
    }
})

private fun fixedClock(value: String): Clock = object : Clock {
    override fun now(): Instant = Instant.parse(value)
}

private fun rsaKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").run {
        initialize(2048)
        generateKeyPair()
    }

private fun idToken(keys: KeyPair, audience: String): String =
    JWT.create()
        .withKeyId("kid-1")
        .withIssuer("https://accounts.google.com")
        .withAudience(audience)
        .withSubject("google-sub")
        .withClaim("email", "user@example.com")
        .withClaim("email_verified", true)
        .withExpiresAt(JavaInstant.parse("2030-01-01T00:00:00Z"))
        .sign(Algorithm.RSA256(keys.public as RSAPublicKey, keys.private as RSAPrivateKey))

private fun jwks(keys: KeyPair): String {
    val publicKey = keys.public as RSAPublicKey
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val modulus = encoder.encodeToString(publicKey.modulus.toByteArray().stripLeadingZero())
    val exponent = encoder.encodeToString(publicKey.publicExponent.toByteArray().stripLeadingZero())
    return """
        {
          "keys":[
            {
              "kid":"kid-1",
              "kty":"RSA",
              "alg":"RS256",
              "use":"sig",
              "n":"$modulus",
              "e":"$exponent"
            }
          ]
        }
    """.trimIndent()
}

private fun ByteArray.stripLeadingZero(): ByteArray =
    if (size > 1 && first() == 0.toByte()) copyOfRange(1, size) else this
