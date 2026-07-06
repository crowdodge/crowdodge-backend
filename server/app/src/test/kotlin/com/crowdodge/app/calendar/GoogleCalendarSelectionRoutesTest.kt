package com.crowdodge.app.calendar

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.auth0.jwt.JWT
import com.crowdodge.app.plugins.configureAuthentication
import com.crowdodge.app.plugins.configureSerialization
import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.port.CalendarConnectionProvider
import com.crowdodge.event.application.port.CalendarSyncBatch
import com.crowdodge.event.application.port.CalendarSyncFetchResult
import com.crowdodge.event.application.port.CalendarSyncState
import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.port.CalendarWatchRegistration
import com.crowdodge.event.application.port.CalendarWatchRegistrationGateway
import com.crowdodge.event.application.port.GoogleCalendarEventsGateway
import com.crowdodge.event.application.service.GoogleCalendarEventSynchronizer
import com.crowdodge.event.application.service.GoogleCalendarSyncLifecycleService
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarAccessRole
import com.crowdodge.user.application.port.GoogleCalendarListGateway
import com.crowdodge.user.application.port.GoogleCalendarListItem
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.application.port.RefreshedGoogleToken
import com.crowdodge.user.application.service.GoogleAccessTokenProvider
import com.crowdodge.user.application.service.UserCalendarSelectionService
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.crowdodge.event.domain.model.UserCalendarUuid as EventUserCalendarUuid

class GoogleCalendarSelectionRoutesTest : FunSpec({
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

    test("GET selection listはselected突合済みの候補一覧を返す") {
        val fixture = SelectionRouteFixture(userUuid)
        fixture.repository.add("work")

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.get("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain """"id":"work""""
            response.bodyAsText() shouldContain """"selected":true"""
            response.bodyAsText() shouldContain """"id":"private""""
            response.bodyAsText() shouldContain """"selected":false"""
        }
    }

    test("PUT selectionはJWT UserUuidとbodyのcalendar IDsだけで置換し204を返す") {
        val fixture = SelectionRouteFixture(userUuid)

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.put("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"calendarIds":["work"]}""")
            }

            response.status shouldBe HttpStatusCode.NoContent
        }

        fixture.repository.selectedIds() shouldBe listOf("work")
        fixture.watches.started.map { it.calendarId } shouldBe listOf("work")
    }

    test("PUT selectionの重複IDは400") {
        val fixture = SelectionRouteFixture(userUuid)

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.put("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"calendarIds":["work","work"]}""")
            }

            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("PUT selectionの権限不足は403") {
        val fixture = SelectionRouteFixture(userUuid)

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.put("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"calendarIds":["readonly"]}""")
            }

            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("Google再認可が必要なら401とGOOGLE_REAUTH_REQUIREDを返す") {
        val fixture = SelectionRouteFixture(
            userUuid = userUuid,
            credentialScopes = "https://www.googleapis.com/auth/calendar.events",
        )

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.get("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "\"code\":\"GOOGLE_REAUTH_REQUIRED\""
            response.bodyAsText() shouldContain
                "\"type\":\"https://crowdodge.grfsv.net/problems/GOOGLE_REAUTH_REQUIRED\""
        }
    }

    test("保存済みGoogle scope不足ならPUTも401とGOOGLE_REAUTH_REQUIREDを返す") {
        val fixture = SelectionRouteFixture(
            userUuid = userUuid,
            credentialScopes = "https://www.googleapis.com/auth/calendar.events",
        )

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.put("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"calendarIds":["work"]}""")
            }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "\"code\":\"GOOGLE_REAUTH_REQUIRED\""
            response.bodyAsText() shouldContain
                "\"type\":\"https://crowdodge.grfsv.net/problems/GOOGLE_REAUTH_REQUIRED\""
        }
    }

    listOf(
        UserError.AuthenticationError.InvalidRefreshToken to HttpStatusCode.Unauthorized,
        UserError.ExternalError.GoogleOAuthError to HttpStatusCode.BadGateway,
        UserError.ExternalError.GoogleCalendarTimeoutError to HttpStatusCode.GatewayTimeout,
    ).forEach { (refreshFailure, expectedStatus) ->
        test("GET selection listはrefreshの${refreshFailure.code}を${expectedStatus.value}へ変換する") {
            val fixture = SelectionRouteFixture(
                userUuid = userUuid,
                accessTokenExpiresAt = RouteFixedClock.now(),
                refreshFailure = refreshFailure,
            )

            testApplication {
                application { configureSelectionTest(jwtConfig, fixture) }

                val response = client.get("/users/me/google-calendars") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }

                response.status shouldBe expectedStatus
                if (expectedStatus == HttpStatusCode.Unauthorized) {
                    response.bodyAsText() shouldContain "\"code\":\"GOOGLE_REAUTH_REQUIRED\""
                }
            }
        }
    }

    test("GET selection listのGoogle障害は502") {
        val fixture = SelectionRouteFixture(
            userUuid = userUuid,
            listFailure = UserError.ExternalError.GoogleOAuthError,
        )

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.get("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }

            response.status shouldBe HttpStatusCode.BadGateway
        }
    }

    test("GET selection listのGoogle timeoutは504") {
        val fixture = SelectionRouteFixture(
            userUuid = userUuid,
            listFailure = UserError.ExternalError.GoogleCalendarTimeoutError,
        )

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.get("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }

            response.status shouldBe HttpStatusCode.GatewayTimeout
            response.bodyAsText() shouldContain "\"code\":\"GOOGLE_CALENDAR_TIMEOUT\""
            response.bodyAsText() shouldContain
                "\"type\":\"https://crowdodge.grfsv.net/problems/GOOGLE_CALENDAR_TIMEOUT\""
        }
    }

    test("watch登録失敗は502") {
        val fixture = SelectionRouteFixture(userUuid)
        fixture.watches.failStartFor = "work"

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.put("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"calendarIds":["work"]}""")
            }

            response.status shouldBe HttpStatusCode.BadGateway
        }
    }

    test("PUT selectionのwatch登録timeoutは504") {
        val fixture = SelectionRouteFixture(userUuid)
        fixture.watches.failStartFor = "work"
        fixture.watches.failStartWith = EventError.ExternalError.GoogleCalendarTimeoutError

        testApplication {
            application { configureSelectionTest(jwtConfig, fixture) }

            val response = client.put("/users/me/google-calendars") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"calendarIds":["work"]}""")
            }

            response.status shouldBe HttpStatusCode.GatewayTimeout
            response.bodyAsText() shouldContain "\"code\":\"GOOGLE_CALENDAR_TIMEOUT\""
            response.bodyAsText() shouldContain
                "\"type\":\"https://crowdodge.grfsv.net/problems/GOOGLE_CALENDAR_TIMEOUT\""
        }
    }
})

private fun Application.configureSelectionTest(
    jwtConfig: JwtAppTokenConfig,
    fixture: SelectionRouteFixture,
) {
    install(Koin) {
        modules(
            module {
                single { jwtConfig }
                single<UserCalendarSelectionService> { fixture.selectionService }
                single<ReplaceGoogleCalendarSelectionCoordinator> { fixture.coordinator }
            },
        )
    }
    configureSerialization()
    configureAuthentication()
    configureGoogleCalendarSelectionRouting()
}

private class SelectionRouteFixture(
    val userUuid: UserUuid,
    private val listFailure: UserError? = null,
    credentialScopes: String =
        "https://www.googleapis.com/auth/calendar.events " +
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
    accessTokenExpiresAt: Instant = RouteFixedClock.now() + 1.hours,
    refreshFailure: UserError? = null,
) {
    val repository = RouteUserCalendarRepository(userUuid)
    val watches = RouteWatchGateway()
    private val states = RouteCalendarSyncStatePort()
    private val transactions = RouteImmediateTransactionRunner()
    private val tokenProvider = GoogleAccessTokenProvider(
        credentials = RouteCredentialRepository(userUuid, credentialScopes, accessTokenExpiresAt),
        refreshGateway = GoogleOAuthTokenRefreshGateway {
            refreshFailure?.left()
                ?: RefreshedGoogleToken("refreshed-access-token", RouteFixedClock.now() + 1.hours).right()
        },
        transactions = transactions,
        clock = RouteFixedClock,
    )
    private val listGateway = GoogleCalendarListGateway { requestUserUuid ->
        tokenProvider.get(requestUserUuid).fold(
            { it.left() },
            {
                listFailure?.left() ?: listOf(
                    GoogleCalendarListItem("work", "Work", "#111111", false, GoogleCalendarAccessRole.OWNER),
                    GoogleCalendarListItem("private", "Private", "#222222", false, GoogleCalendarAccessRole.WRITER),
                    GoogleCalendarListItem("readonly", "Read Only", "#333333", false, GoogleCalendarAccessRole.READER),
                ).right()
            },
        )
    }
    val selectionService = UserCalendarSelectionService(
        calendarList = listGateway,
        accessTokens = tokenProvider,
        calendars = repository,
        transactions = transactions,
        publisher = DomainEventPublisher { },
        clock = RouteFixedClock,
    )
    private val synchronizer = GoogleCalendarEventSynchronizer(
        gateway = RouteEventsGateway(),
        connections = CalendarConnectionProvider { CalendarConnection("calendar", "token").right() },
        states = states,
        events = RouteEventRepository(),
        transactions = transactions,
        publisher = DomainEventPublisher { },
        clock = RouteFixedClock,
    )
    private val lifecycle = GoogleCalendarSyncLifecycleService(
        watches = watches,
        states = states,
        events = RouteEventRepository(),
        synchronizer = synchronizer,
        connections = CalendarConnectionProvider { CalendarConnection(it.value.toString(), "token").right() },
        transactions = transactions,
    )
    val coordinator = ReplaceGoogleCalendarSelectionCoordinator(selectionService, lifecycle)
}

private class RouteWatchGateway : CalendarWatchRegistrationGateway {
    var failStartFor: String? = null
    var failStartWith: EventError.ExternalError = EventError.ExternalError.GoogleCalendarError
    val started = mutableListOf<CalendarConnection>()

    override suspend fun startWatch(
        connection: CalendarConnection,
    ): Either<EventError.ExternalError, CalendarWatchRegistration> {
        if (connection.calendarId == failStartFor) return failStartWith.left()
        started += connection
        return CalendarWatchRegistration(
            channelId = "channel-${connection.calendarId}",
            resourceId = "resource-${connection.calendarId}",
            channelToken = "token-${connection.calendarId}",
            expiration = RouteFixedClock.now() + 1.hours,
        ).right()
    }

    override suspend fun stopWatch(
        connection: CalendarConnection,
        channelId: String,
        resourceId: String,
    ): Either<EventError.ExternalError, Unit> = Unit.right()
}

private class RouteCalendarSyncStatePort : CalendarSyncStatePort {
    private val states = mutableMapOf<EventUserCalendarUuid, CalendarSyncState>()
    override suspend fun find(userCalendarUuid: EventUserCalendarUuid): CalendarSyncState? = states[userCalendarUuid]
    override suspend fun findByChannelId(channelId: String): CalendarSyncState? =
        states.values.firstOrNull { it.watchChannelId == channelId }

    override suspend fun lock(userCalendarUuid: EventUserCalendarUuid): CalendarSyncState? = states[userCalendarUuid]
    override suspend fun saveProvisioned(state: CalendarSyncState) {
        states[state.userCalendarUuid] = state
    }

    override suspend fun updateAfterSync(
        userCalendarUuid: EventUserCalendarUuid,
        nextSyncToken: String?,
        materializedUntil: Instant,
    ) = Unit

    override suspend fun replaceWatch(
        userCalendarUuid: EventUserCalendarUuid,
        expectedChannelId: String,
        watch: CalendarWatchRegistration,
    ): Boolean = false

    override suspend fun deleteIfChannelMatches(userCalendarUuid: EventUserCalendarUuid, channelId: String): Boolean {
        states.remove(userCalendarUuid)
        return true
    }

    override suspend fun delete(userCalendarUuid: EventUserCalendarUuid): Boolean {
        states.remove(userCalendarUuid)
        return true
    }

    override suspend fun listAll(): List<CalendarSyncState> = states.values.toList()
}

private class RouteUserCalendarRepository(
    private val userUuid: UserUuid,
) : UserCalendarRepository {
    private val selected = mutableListOf<UserCalendar>()

    fun add(id: String) {
        selected += calendar(id)
    }

    fun selectedIds(): List<String> = selected.map { it.googleCalendarId.value }

    override suspend fun create(userCalendar: UserCalendar): Either<UserError.ConflictError.DuplicateCalendar, Unit> {
        selected += userCalendar
        return Unit.right()
    }

    override suspend fun delete(userUuid: UserUuid, userCalendarUuid: UserCalendarUuid) {
        selected.removeIf { it.userUuid == userUuid && it.userCalendarUuid == userCalendarUuid }
    }

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserCalendar> =
        selected.filter { it.userUuid == userUuid }

    override suspend fun replaceForUser(
        userUuid: UserUuid,
        calendars: List<UserCalendar>,
    ): Either<UserError.ConflictError.DuplicateCalendar, Unit> {
        selected.removeIf { it.userUuid == userUuid }
        selected += calendars
        return Unit.right()
    }

    private fun calendar(id: String): UserCalendar = either {
        UserCalendar.reconstitute(UserCalendarUuid.new(), userUuid, googleCalendarId(id))
    }.getOrNull()!!
}

private class RouteCredentialRepository(
    userUuid: UserUuid,
    credentialScopes: String,
    accessTokenExpiresAt: Instant,
) : UserGoogleCredentialRepository {
    private val credential = either {
        UserGoogleCredential(
            userUuid = userUuid,
            googleSubject = googleSubject("google-subject"),
            accessToken = googleAccessToken("access-token"),
            refreshToken = googleRefreshToken("refresh-token"),
            accessTokenExpiresAt = accessTokenExpiresAt,
            grantedScopes = grantedGoogleScopes(credentialScopes),
        )
    }.getOrNull()!!

    override suspend fun findByUserUuid(userUuid: UserUuid): UserGoogleCredential = credential
    override suspend fun upsert(credential: UserGoogleCredential) = Unit
    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: GoogleAccessToken,
        accessTokenExpiresAt: Instant,
    ) = Unit
}

private class RouteEventRepository : EventRepository {
    override suspend fun upsertAll(events: List<Event>) = Unit
    override suspend fun deleteByGoogleEventIds(
        userCalendarUuid: EventUserCalendarUuid,
        googleEventIds: List<com.crowdodge.event.domain.model.GoogleEventId>,
    ) = Unit

    override suspend fun delete(userCalendarUuid: EventUserCalendarUuid, eventUuid: EventUuid) = Unit
    override suspend fun findByEventUuid(userCalendarUuid: EventUserCalendarUuid, eventUuid: EventUuid): Event? = null
    override suspend fun findByGoogleEventIds(
        userCalendarUuid: EventUserCalendarUuid,
        googleEventIds: List<com.crowdodge.event.domain.model.GoogleEventId>,
    ): List<Event> = emptyList()

    override suspend fun findAllByUserCalendarUuid(userCalendarUuid: EventUserCalendarUuid): List<Event> = emptyList()
}

private class RouteEventsGateway : GoogleCalendarEventsGateway {
    override suspend fun incrementalSync(
        connection: CalendarConnection,
        syncToken: String,
    ): Either<EventError.ExternalError, CalendarSyncFetchResult> =
        CalendarSyncFetchResult.Events(CalendarSyncBatch(emptyList(), emptyList(), null)).right()

    override suspend fun fullSync(
        connection: CalendarConnection,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError.ExternalError, CalendarSyncBatch> =
        CalendarSyncBatch(emptyList(), emptyList(), null).right()
}

private class RouteImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private object RouteFixedClock : Clock {
    private val instant = Instant.parse("2026-01-01T00:00:00Z")
    override fun now(): Instant = instant
}
