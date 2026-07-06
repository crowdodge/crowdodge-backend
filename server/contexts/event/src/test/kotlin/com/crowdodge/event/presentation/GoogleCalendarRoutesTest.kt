package com.crowdodge.event.presentation

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.event.application.command.HandleGoogleCalendarWebhookUseCase
import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.port.CalendarConnectionProvider
import com.crowdodge.event.application.port.CalendarSyncBatch
import com.crowdodge.event.application.port.CalendarSyncFetchResult
import com.crowdodge.event.application.port.CalendarSyncState
import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.port.CalendarWatchRegistration
import com.crowdodge.event.application.port.GoogleCalendarEventsGateway
import com.crowdodge.event.application.service.GoogleCalendarEventSynchronizer
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GoogleCalendarRoutesTest : FunSpec({
    test("sync通知は状態検索せず204を返す") {
        val states = RouteStatePort()
        val gateway = RouteEventsGateway()

        testApplication {
            application { configureForRouteTest(states, gateway) }

            client.post("/webhooks/google-calendar") {
                header("X-Goog-Channel-ID", "channel-1")
                header("X-Goog-Resource-State", "sync")
            }.status shouldBe HttpStatusCode.NoContent
        }

        states.findByChannelIds.shouldBeEmpty()
        gateway.incrementalCalls.shouldBeEmpty()
    }

    test("登録済みexistsは同期して204を返す") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val states = RouteStatePort(state = routeState(calendarUuid, channelToken = "token"))
        val gateway = RouteEventsGateway()

        testApplication {
            application { configureForRouteTest(states, gateway) }

            client.post("/webhooks/google-calendar") {
                header("X-Goog-Channel-ID", "channel-1")
                header("X-Goog-Channel-Token", "token")
                header("X-Goog-Resource-State", "exists")
            }.status shouldBe HttpStatusCode.NoContent
        }

        gateway.incrementalCalls shouldContainExactly listOf("sync-token")
    }

    test("未登録channelとtoken不一致は204を返す") {
        val calendarUuid = UserCalendarUuid(Uuid.random())

        testApplication {
            application { configureForRouteTest(RouteStatePort(state = null), RouteEventsGateway()) }
            client.post("/webhooks/google-calendar") {
                header("X-Goog-Channel-ID", "unknown")
                header("X-Goog-Resource-State", "exists")
            }.status shouldBe HttpStatusCode.NoContent
        }

        testApplication {
            application {
                configureForRouteTest(
                    RouteStatePort(state = routeState(calendarUuid, channelToken = "expected")),
                    RouteEventsGateway(),
                )
            }
            client.post("/webhooks/google-calendar") {
                header("X-Goog-Channel-ID", "channel-1")
                header("X-Goog-Channel-Token", "actual")
                header("X-Goog-Resource-State", "exists")
            }.status shouldBe HttpStatusCode.NoContent
        }
    }

    test("必須header不足は400を返す") {
        testApplication {
            application { configureForRouteTest(RouteStatePort(), RouteEventsGateway()) }

            client.post("/webhooks/google-calendar") {
                header("X-Goog-Resource-State", "exists")
            }.status shouldBe HttpStatusCode.BadRequest

            client.post("/webhooks/google-calendar") {
                header("X-Goog-Channel-ID", "channel-1")
            }.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("同期失敗は502を返す") {
        val calendarUuid = UserCalendarUuid(Uuid.random())

        testApplication {
            application {
                configureForRouteTest(
                    RouteStatePort(routeState(calendarUuid, channelToken = null)),
                    RouteEventsGateway(result = EventError.ExternalError.GoogleCalendarError.left()),
                )
            }

            client.post("/webhooks/google-calendar") {
                header("X-Goog-Channel-ID", "channel-1")
                header("X-Goog-Resource-State", "exists")
            }.status shouldBe HttpStatusCode.BadGateway
        }
    }
})

private fun Application.configureForRouteTest(
    states: RouteStatePort,
    gateway: RouteEventsGateway,
) {
    install(Koin) {
        modules(
            module {
                single {
                    HandleGoogleCalendarWebhookUseCase(
                        states = states,
                        synchronizer = GoogleCalendarEventSynchronizer(
                            gateway = gateway,
                            connections = object : CalendarConnectionProvider {
                                override suspend fun connection(
                                    userCalendarUuid: UserCalendarUuid,
                                ): Either<EventError.ExternalError, CalendarConnection> =
                                    CalendarConnection("calendar-id", "access-token").right()
                            },
                            states = states,
                            events = RouteEventRepository,
                            transactions = RouteTransactionRunner,
                            publisher = object : DomainEventPublisher {
                                override suspend fun publish(event: DomainEvent) = Unit
                            },
                            clock = object : Clock {
                                override fun now(): Instant = Instant.parse("2026-07-01T00:00:00Z")
                            },
                        ),
                        transactions = RouteTransactionRunner,
                    )
                }
            },
        )
    }
    configureEventRouting()
}

private class RouteEventsGateway(
    private val result: Either<EventError.ExternalError, CalendarSyncFetchResult> =
        CalendarSyncFetchResult.Events(CalendarSyncBatch(emptyList(), emptyList(), "next-token")).right(),
) : GoogleCalendarEventsGateway {
    val incrementalCalls = mutableListOf<String>()

    override suspend fun incrementalSync(
        connection: CalendarConnection,
        syncToken: String,
    ): Either<EventError.ExternalError, CalendarSyncFetchResult> {
        incrementalCalls += syncToken
        return result
    }

    override suspend fun fullSync(
        connection: CalendarConnection,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError.ExternalError, CalendarSyncBatch> =
        EventError.ExternalError.GoogleCalendarError.left()
}

private class RouteStatePort(
    private val state: CalendarSyncState? = null,
) : CalendarSyncStatePort {
    val findByChannelIds = mutableListOf<String>()

    override suspend fun find(userCalendarUuid: UserCalendarUuid): CalendarSyncState? = state

    override suspend fun findByChannelId(channelId: String): CalendarSyncState? {
        findByChannelIds += channelId
        return state
    }

    override suspend fun lock(userCalendarUuid: UserCalendarUuid): CalendarSyncState? = state

    override suspend fun saveProvisioned(state: CalendarSyncState) = Unit

    override suspend fun updateAfterSync(
        userCalendarUuid: UserCalendarUuid,
        nextSyncToken: String?,
        materializedUntil: Instant,
    ) = Unit

    override suspend fun replaceWatch(
        userCalendarUuid: UserCalendarUuid,
        expectedChannelId: String,
        watch: CalendarWatchRegistration,
    ): Boolean = true

    override suspend fun deleteIfChannelMatches(userCalendarUuid: UserCalendarUuid, channelId: String): Boolean = true

    override suspend fun delete(userCalendarUuid: UserCalendarUuid): Boolean = true

    override suspend fun listAll(): List<CalendarSyncState> = state?.let(::listOf).orEmpty()
}

private object RouteTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private object RouteEventRepository : EventRepository {
    override suspend fun upsertAll(events: List<Event>) = Unit

    override suspend fun deleteByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ) = Unit

    override suspend fun delete(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid) = Unit

    override suspend fun findByEventUuid(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid): Event? = null

    override suspend fun findByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ): List<Event> = emptyList()

    override suspend fun findAllByUserCalendarUuid(userCalendarUuid: UserCalendarUuid): List<Event> = emptyList()
}

private fun routeState(userCalendarUuid: UserCalendarUuid, channelToken: String?): CalendarSyncState =
    CalendarSyncState(
        userCalendarUuid = userCalendarUuid,
        syncToken = "sync-token",
        materializedUntil = Instant.parse("2026-10-01T00:00:00Z"),
        watchChannelId = "channel-1",
        watchResourceId = "resource-1",
        watchChannelToken = channelToken,
        watchExpiration = null,
    )
