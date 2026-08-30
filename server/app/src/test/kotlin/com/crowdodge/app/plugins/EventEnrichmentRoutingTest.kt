package com.crowdodge.app.plugins

import com.auth0.jwt.JWT
import com.crowdodge.app.configureEventEnrichmentRouting
import com.crowdodge.event.application.port.CalendarEventEnrichments
import com.crowdodge.event.application.port.EventEnrichment
import com.crowdodge.event.application.port.EventEnrichmentCongestion
import com.crowdodge.event.application.port.EventEnrichmentDestination
import com.crowdodge.event.application.port.EventEnrichmentReadModel
import com.crowdodge.event.application.query.ListEventEnrichmentsUseCase
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.infrastructure.security.hmacAlgorithm
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class EventEnrichmentRoutingTest : FunSpec({
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

    test("GET /v1/eventsはOpenAPI契約のカレンダー別付加情報を返す") {
        val eventUuid = Uuid.parse("00000000-0000-0000-0000-000000000010")
        val readModel = RoutingEventEnrichmentReadModel(
            listOf(
                CalendarEventEnrichments(
                    googleCalendarId = "calendar-a",
                    events = listOf(
                        EventEnrichment(
                            googleEventId = "google-event-a",
                            eventUuid = eventUuid,
                            destination = EventEnrichmentDestination("会場", 35.6, 139.7),
                            congestions = listOf(
                                EventEnrichmentCongestion(
                                    Instant.parse("2026-07-21T01:00:00Z"),
                                    Instant.parse("2026-07-21T02:00:00Z"),
                                    "駅前",
                                    "イベント開催のため混雑",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        testApplication {
            application { configureEventEnrichmentTest(jwtConfig, readModel) }

            val response = client.get("/v1/events?calendarId=calendar-a") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }

            response.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(response.bodyAsText()) shouldBe Json.parseToJsonElement(
                """
                {
                  "calendars": [{
                    "calendarId": "calendar-a",
                    "events": [{
                      "googleEventId": "google-event-a",
                      "eventId": "$eventUuid",
                      "destination": {"name": "会場", "latitude": 35.6, "longitude": 139.7},
                      "congestions": [{
                        "congestionStartTime": "2026-07-21T01:00:00Z",
                        "congestionEndTime": "2026-07-21T02:00:00Z",
                        "area": "駅前",
                        "description": "イベント開催のため混雑",
                        "stores": []
                      }]
                    }]
                  }]
                }
                """.trimIndent(),
            )
            readModel.requests shouldBe listOf(userUuid to setOf("calendar-a"))
        }
    }

    test("未選択calendarId、空値、重複値は400 Problem Detailsを返す") {
        testApplication {
            application {
                configureEventEnrichmentTest(jwtConfig, RoutingEventEnrichmentReadModel(emptyList()))
            }

            listOf(
                "/v1/events?calendarId=unknown",
                "/v1/events?calendarId=",
                "/v1/events?calendarId=calendar-a,calendar-a",
            ).forEach { path ->
                val response = client.get(path) {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "\"code\":\"VALIDATION_ERROR\""
            }
        }
    }

    test("Bearer認証がなければ401を返す") {
        testApplication {
            application {
                configureEventEnrichmentTest(jwtConfig, RoutingEventEnrichmentReadModel(emptyList()))
            }

            client.get("/v1/events").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})

private fun Application.configureEventEnrichmentTest(
    jwtConfig: JwtAppTokenConfig,
    readModel: EventEnrichmentReadModel,
) {
    install(Koin) {
        modules(
            module {
                single { jwtConfig }
                single { ListEventEnrichmentsUseCase(readModel) }
            },
        )
    }
    configureSerialization()
    configureAuthentication()
    configureEventEnrichmentRouting()
}
private class RoutingEventEnrichmentReadModel(
    private val calendars: List<CalendarEventEnrichments>,
) : EventEnrichmentReadModel {
    val requests = mutableListOf<Pair<UserUuid, Set<String>?>>()

    override suspend fun findCalendars(
        userUuid: UserUuid,
        googleCalendarIds: Set<String>?,
    ): List<CalendarEventEnrichments> {
        requests += userUuid to googleCalendarIds
        return calendars
    }
}
