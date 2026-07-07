package com.crowdodge.app.plugins

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.auth0.jwt.JWT
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.CalendarProxyRequest
import com.crowdodge.user.application.port.CalendarProxyResponse
import com.crowdodge.user.application.port.GoogleCalendarProxyGateway
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.application.port.RefreshedGoogleToken
import com.crowdodge.user.application.query.ProxyGoogleCalendarUseCase
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleAccessToken
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.GoogleRefreshToken.Companion.googleRefreshToken
import com.crowdodge.user.domain.model.GoogleSubject.Companion.googleSubject
import com.crowdodge.user.domain.model.GrantedGoogleScopes.Companion.grantedGoogleScopes
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import com.crowdodge.user.infrastructure.security.hmacAlgorithm
import com.crowdodge.user.presentation.configureGoogleCalendarProxyRouting
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GoogleCalendarProxyRoutingTest : FunSpec({
    val userUuid = UserUuid(Uuid.parse("00000000-0000-0000-0000-000000000001"))
    val jwtConfig = JwtAppTokenConfig(
        issuer = "issuer",
        audience = "audience",
        secret = "01234567890123456789012345678901",
        accessTokenTtl = 1.hours,
        refreshTokenTtl = 1.hours,
    )

    fun token(): String = JWT.create()
        .withIssuer(jwtConfig.issuer)
        .withAudience(jwtConfig.audience)
        .withSubject(userUuid.value.toString())
        .sign(jwtConfig.hmacAlgorithm())

    test("primary省略routeは404になる") {
        val fake = RecordingGateway()
        testApplication {
            application { configureForProxyTest(jwtConfig, fake, userUuid) }

            client.get("/v1/events?timeMin=2026-01-01T00%3A00%3A00Z") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.status shouldBe HttpStatusCode.NotFound
        }

        fake.requests shouldBe emptyList()
    }

    test("calendar routeは指定calendar/event IDと未解釈bodyを転送する") {
        val fake = RecordingGateway()
        val body = """{"summary":"raw"}"""
        testApplication {
            application { configureForProxyTest(jwtConfig, fake, userUuid) }

            client.patch("/v1/calendars/cal%2Fid/events/event%2Fid") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }.status shouldBe HttpStatusCode.OK
        }

        fake.request?.calendarId shouldBe "cal/id"
        fake.request?.eventId shouldBe "event/id"
        fake.request?.body?.decodeToString() shouldBe body
    }

    test("primary detail routesは404になる") {
        val fake = RecordingGateway()
        testApplication {
            application { configureForProxyTest(jwtConfig, fake, userUuid) }

            client.get("/v1/events/event-id") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.status shouldBe HttpStatusCode.NotFound
            client.patch("/v1/events/event-id") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody("{}")
            }.status shouldBe HttpStatusCode.NotFound
            client.delete("/v1/events/event-id") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.status shouldBe HttpStatusCode.NotFound
        }

        fake.requests shouldBe emptyList()
    }

    test("未選択Calendar IDはGoogleへ転送せず403にする") {
        val fake = RecordingGateway()
        testApplication {
            application { configureForProxyTest(jwtConfig, fake, userUuid, selectedCalendarIds = listOf("selected")) }

            client.get("/v1/calendars/unselected/events") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.status shouldBe HttpStatusCode.Forbidden
        }

        fake.requests shouldBe emptyList()
    }

    test("Googleの全4xxと5xxはstatusを維持し機密bodyをProblemへ変換する") {
        listOf(400, 401, 403, 404, 409, 410, 412, 429, 500, 502, 503, 504).forEach { status ->
            val fake = RecordingGateway(
                CalendarProxyResponse(
                    status = status,
                    contentType = "application/json",
                    body = """{"error":{"message":"secret-token-value","internal":"credential"}}"""
                        .encodeToByteArray(),
                ),
            )
            testApplication {
                application { configureForProxyTest(jwtConfig, fake, userUuid) }

                val response = client.get("/v1/calendars/cal%2Fid/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

                response.status.value shouldBe status
                response.contentType().toString() shouldBe "application/problem+json"
                response.bodyAsText() shouldNotContain "secret-token-value"
                response.bodyAsText() shouldNotContain "credential"
            }
        }
    }

    test("JWTなしは401") {
        testApplication {
            application { configureForProxyTest(jwtConfig, RecordingGateway(), userUuid) }
            client.get("/v1/calendars/cal%2Fid/events").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("allowlist外queryは400") {
        testApplication {
            application { configureForProxyTest(jwtConfig, RecordingGateway(), userUuid) }
            client.get("/v1/calendars/cal%2Fid/events?sendUpdates=all") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("空または過大なcalendar IDとevent IDは400") {
        val invalidPaths = listOf(
            "/v1/calendars/%20/events",
            "/v1/calendars/${"c".repeat(2049)}/events",
            "/v1/calendars/cal%2Fid/events/${"e".repeat(2049)}",
        )

        invalidPaths.forEach { path ->
            testApplication {
                application { configureForProxyTest(jwtConfig, RecordingGateway(), userUuid) }
                client.get(path) {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    test("queryの件数 各値長 合計長が上限を超えると400") {
        val invalidQueries = listOf(
            List(51) { "maxResults=$it" }.joinToString("&"),
            "q=${"v".repeat(4097)}",
            List(5) { "q=${"v".repeat(4000)}" }.joinToString("&"),
        )

        invalidQueries.forEach { query ->
            testApplication {
                application { configureForProxyTest(jwtConfig, RecordingGateway(), userUuid) }
                client.get("/v1/calendars/cal%2Fid/events?$query") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    test("POST PATCHはapplication json以外またはContent-Type欠落を400にする") {
        val requests = listOf<suspend (io.ktor.client.HttpClient) -> io.ktor.client.statement.HttpResponse>(
            { client ->
                client.post("/v1/calendars/cal%2Fid/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                    setBody(ByteArray(0))
                }
            },
            { client ->
                client.post("/v1/calendars/cal%2Fid/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                    header(HttpHeaders.ContentType, "text/plain")
                    setBody("{}")
                }
            },
            { client ->
                client.patch("/v1/calendars/cal%2Fid/events/event") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                    header(HttpHeaders.ContentType, "application/xml")
                    setBody("<event/>")
                }
            },
        )

        requests.forEach { request ->
            val fake = RecordingGateway()
            testApplication {
                application { configureForProxyTest(jwtConfig, fake, userUuid) }
                request(client).status shouldBe HttpStatusCode.BadRequest
            }
            fake.requests shouldBe emptyList()
        }
    }

    test("1MiBを超えるbodyは413") {
        testApplication {
            application { configureForProxyTest(jwtConfig, RecordingGateway(), userUuid) }
            client.patch("/v1/calendars/cal%2Fid/events/event") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody(ByteArray(1024 * 1024 + 1))
            }.status shouldBe HttpStatusCode.PayloadTooLarge
        }
    }

    listOf(
        HttpStatusCode.BadGateway to "GOOGLE_CALENDAR_ERROR",
        HttpStatusCode.GatewayTimeout to "GOOGLE_CALENDAR_TIMEOUT",
    ).forEach { (status, code) ->
        test("Google gateway由来の${status.value}はProblemへ変換する") {
            testApplication {
                application {
                    configureForProxyTest(
                        jwtConfig,
                        RecordingGateway(CalendarProxyResponse(status.value, null, byteArrayOf())),
                        userUuid,
                    )
                }

                val response = client.get("/v1/calendars/cal%2Fid/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

                response.status shouldBe status
                response.bodyAsText() shouldContain "\"code\":\"$code\""
                response.bodyAsText() shouldContain "\"type\":\"https://crowdodge.grfsv.net/problems/$code\""
            }
        }
    }

    listOf(
        RefreshFailureCase(
            failure = UserError.AuthenticationError.InvalidRefreshToken,
            status = HttpStatusCode.Unauthorized,
            code = "GOOGLE_REAUTH_REQUIRED",
        ),
        RefreshFailureCase(
            failure = UserError.ExternalError.GoogleOAuthError,
            status = HttpStatusCode.BadGateway,
            code = "GOOGLE_CALENDAR_ERROR",
        ),
        RefreshFailureCase(
            failure = UserError.ExternalError.GoogleCalendarTimeoutError,
            status = HttpStatusCode.GatewayTimeout,
            code = "GOOGLE_CALENDAR_TIMEOUT",
        ),
    ).forEach { case ->
        test("期限切れtokenのrefresh ${case.failure.code}は${case.status.value}を返す") {
            testApplication {
                application {
                    configureForProxyTest(
                        jwtConfig = jwtConfig,
                        gateway = RecordingGateway(),
                        userUuid = userUuid,
                        accessTokenExpiresAt = Clock.System.now(),
                        refreshFailure = case.failure,
                    )
                }

                val response = client.get("/v1/calendars/cal%2Fid/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

                response.status shouldBe case.status
                response.bodyAsText() shouldContain "\"code\":\"${case.code}\""
            }
        }

        test("Google 401後のrefresh ${case.failure.code}は${case.status.value}を返す") {
            testApplication {
                application {
                    configureForProxyTest(
                        jwtConfig = jwtConfig,
                        gateway = RecordingGateway(refreshBeforeResponse = true),
                        userUuid = userUuid,
                        refreshFailure = case.failure,
                    )
                }

                val response = client.get("/v1/calendars/cal%2Fid/events") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

                response.status shouldBe case.status
                response.bodyAsText() shouldContain "\"code\":\"${case.code}\""
            }
        }
    }
})

private data class RefreshFailureCase(
    val failure: UserError,
    val status: HttpStatusCode,
    val code: String,
)

private class RecordingGateway(
    private val response: CalendarProxyResponse =
        CalendarProxyResponse(200, "application/json", "{}".encodeToByteArray()),
    private val refreshBeforeResponse: Boolean = false,
) : GoogleCalendarProxyGateway {
    var requestedUserUuid: UserUuid? = null
    var request: CalendarProxyRequest? = null
    val requests = mutableListOf<CalendarProxyRequest>()

    override suspend fun proxy(
        request: CalendarProxyRequest,
        accessToken: String,
        refreshAccessToken: suspend () -> String?,
    ): CalendarProxyResponse {
        this.request = request
        requests += request
        if (refreshBeforeResponse && refreshAccessToken() == null) {
            return CalendarProxyResponse(401, null, byteArrayOf())
        }
        return response
    }
}

@Suppress("LongParameterList")
private fun Application.configureForProxyTest(
    jwtConfig: JwtAppTokenConfig,
    gateway: RecordingGateway,
    userUuid: UserUuid,
    selectedCalendarIds: List<String> = listOf("cal/id"),
    accessTokenExpiresAt: Instant = Clock.System.now() + 1.hours,
    refreshFailure: UserError? = null,
) {
    val repository = FakeCredentialRepository(userUuid, accessTokenExpiresAt) {
        gateway.requestedUserUuid = it
    }
    install(Koin) {
        modules(
            module {
                single { jwtConfig }
                single<GoogleCalendarProxyGateway> { gateway }
                single<UserGoogleCredentialRepository> { repository }
                single<UserCalendarRepository> { FakeUserCalendarRepository(userUuid, selectedCalendarIds) }
                single<GoogleOAuthTokenRefreshGateway> {
                    GoogleOAuthTokenRefreshGateway {
                        refreshFailure?.left()
                            ?: RefreshedGoogleToken("refreshed-access", Clock.System.now() + 1.hours).right()
                    }
                }
                single<TransactionRunner> { ProxyImmediateTransactionRunner() }
                single { ProxyGoogleCalendarUseCase(get(), get(), get(), get(), get()) }
            },
        )
    }
    configureSerialization()
    configureAuthentication()
    configureGoogleCalendarProxyRouting()
}

private class FakeCredentialRepository(
    userUuid: UserUuid,
    accessTokenExpiresAt: Instant,
    private val onFind: (UserUuid) -> Unit,
) : UserGoogleCredentialRepository {
    private val credential = arrow.core.raise.either {
        UserGoogleCredential(
            userUuid = userUuid,
            googleSubject = googleSubject("google-sub"),
            accessToken = googleAccessToken("access-token"),
            refreshToken = googleRefreshToken("refresh-token"),
            accessTokenExpiresAt = accessTokenExpiresAt,
            grantedScopes = grantedGoogleScopes(
                "https://www.googleapis.com/auth/calendar.events " +
                    "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
            ),
        )
    }.getOrNull()!!

    override suspend fun findByUserUuid(userUuid: UserUuid): UserGoogleCredential {
        onFind(userUuid)
        return credential
    }
    override suspend fun upsert(credential: UserGoogleCredential) = Unit
    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: GoogleAccessToken,
        accessTokenExpiresAt: kotlin.time.Instant,
    ) = Unit
}

private class FakeUserCalendarRepository(
    private val userUuid: UserUuid,
    selectedCalendarIds: List<String>,
) : UserCalendarRepository {
    private val selected = selectedCalendarIds.map { id ->
        either {
            UserCalendar.reconstitute(
                UserCalendarUuid.new(),
                userUuid,
                googleCalendarId(id),
            )
        }.getOrNull()!!
    }

    override suspend fun create(
        userCalendar: UserCalendar,
    ): Either<com.crowdodge.user.domain.error.UserError.ConflictError.DuplicateCalendar, Unit> =
        Unit.right()

    override suspend fun delete(userUuid: UserUuid, userCalendarUuid: UserCalendarUuid) = Unit

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserCalendar> =
        selected.filter { it.userUuid == userUuid }
}

private class ProxyImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}
