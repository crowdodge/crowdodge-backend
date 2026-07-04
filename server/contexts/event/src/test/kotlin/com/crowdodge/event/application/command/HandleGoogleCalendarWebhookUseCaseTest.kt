package com.crowdodge.event.application.command

import arrow.core.Either
import arrow.core.left
import arrow.core.right
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
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.time.Instant
import kotlin.uuid.Uuid

class HandleGoogleCalendarWebhookUseCaseTest : FunSpec({
    test("sync は状態検索前に成功する") {
        val states = RecordingStatePort()
        val gateway = RecordingEventsGateway()

        useCase(states, gateway).execute("channel-1", "token", "sync").shouldBeRight()

        states.findByChannelIds.shouldBeEmpty()
        gateway.incrementalCalls.shouldBeEmpty()
    }

    test("登録済み exists は token を検証して incrementalSync を呼ぶ") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val states = RecordingStatePort(state = state(calendarUuid, channelToken = "token"))
        val gateway = RecordingEventsGateway(
            result = CalendarSyncFetchResult.Events(CalendarSyncBatch(emptyList(), emptyList(), "next-token")).right(),
        )

        useCase(states, gateway).execute("channel-1", "token", "exists").shouldBeRight()

        states.findByChannelIds shouldContainExactly listOf("channel-1")
        gateway.incrementalCalls shouldContainExactly listOf("sync-token")
    }

    test("未登録channelは同期せず成功する") {
        val gateway = RecordingEventsGateway()

        useCase(RecordingStatePort(state = null), gateway)
            .execute("unknown-channel", "token", "exists")
            .shouldBeRight()

        gateway.incrementalCalls.shouldBeEmpty()
    }

    test("token不一致は同期せず成功する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val gateway = RecordingEventsGateway()

        useCase(RecordingStatePort(state = state(calendarUuid, channelToken = "expected")), gateway)
            .execute("channel-1", "actual", "exists")
            .shouldBeRight()

        gateway.incrementalCalls.shouldBeEmpty()
    }

    test("保存tokenがnullで受信tokenがnonnullなら同期せず成功する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val gateway = RecordingEventsGateway()

        useCase(RecordingStatePort(state = state(calendarUuid, channelToken = null)), gateway)
            .execute("channel-1", "unexpected-token", "exists")
            .shouldBeRight()

        gateway.incrementalCalls.shouldBeEmpty()
    }

    test("未知のresource stateは状態検索も同期もせず成功する") {
        val states = RecordingStatePort()
        val gateway = RecordingEventsGateway()

        useCase(states, gateway)
            .execute("channel-1", "token", "not_exists")
            .shouldBeRight()

        states.findByChannelIds.shouldBeEmpty()
        gateway.incrementalCalls.shouldBeEmpty()
    }

    test("同期失敗はエラーを返す") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val gateway = RecordingEventsGateway(result = EventError.ExternalError.GoogleCalendarError.left())

        useCase(RecordingStatePort(state = state(calendarUuid, channelToken = null)), gateway)
            .execute("channel-1", null, "exists")
            .shouldBeLeft()
    }
})

private fun useCase(
    states: RecordingStatePort,
    gateway: RecordingEventsGateway,
): HandleGoogleCalendarWebhookUseCase =
    HandleGoogleCalendarWebhookUseCase(
        states = states,
        synchronizer = GoogleCalendarEventSynchronizer(
            gateway = gateway,
            connections = object : CalendarConnectionProvider {
                override suspend fun connection(
                    userCalendarUuid: UserCalendarUuid,
                ): Either<EventError.ExternalError, CalendarConnection> =
                    CalendarConnection(calendarId = "calendar-id", accessToken = "access-token").right()
            },
            states = states,
            events = EmptyEventRepository,
            transactions = ImmediateTransactionRunner,
            publisher = object : DomainEventPublisher {
                override suspend fun publish(event: DomainEvent) = Unit
            },
            clock = object : kotlin.time.Clock {
                override fun now(): Instant = Instant.parse("2026-07-01T00:00:00Z")
            },
        ),
    )

private class RecordingEventsGateway(
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

private class RecordingStatePort(
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

private object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private object EmptyEventRepository : EventRepository {
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

private fun state(userCalendarUuid: UserCalendarUuid, channelToken: String?): CalendarSyncState =
    CalendarSyncState(
        userCalendarUuid = userCalendarUuid,
        syncToken = "sync-token",
        materializedUntil = Instant.parse("2026-10-01T00:00:00Z"),
        watchChannelId = "channel-1",
        watchResourceId = "resource-1",
        watchChannelToken = channelToken,
        watchExpiration = null,
    )
