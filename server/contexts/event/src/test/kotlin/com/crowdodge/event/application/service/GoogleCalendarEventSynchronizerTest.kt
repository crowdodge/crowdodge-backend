package com.crowdodge.event.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.port.CalendarConnectionProvider
import com.crowdodge.event.application.port.CalendarSyncBatch
import com.crowdodge.event.application.port.CalendarSyncFetchResult
import com.crowdodge.event.application.port.CalendarSyncState
import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.port.CalendarWatchRegistration
import com.crowdodge.event.application.port.GoogleCalendarEventsGateway
import com.crowdodge.event.application.port.IncomingCalendarEvent
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.event.EventCancelled
import com.crowdodge.event.domain.event.EventNotificationTimingChanged
import com.crowdodge.event.domain.event.EventRescheduled
import com.crowdodge.event.domain.event.EventScheduled
import com.crowdodge.event.domain.event.NotificationTimingChangeReason
import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.EventContent
import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.GoogleEventId.Companion.googleEventId
import com.crowdodge.event.domain.model.RemindTiming
import com.crowdodge.event.domain.model.RemindTiming.Companion.remindTiming
import com.crowdodge.event.domain.model.Schedule
import com.crowdodge.event.domain.model.Schedule.Companion.schedule
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GoogleCalendarEventSynchronizerTest : FunSpec({
    test("新規予定は保存し EventScheduled を発行し next sync token も同じ transaction で保存する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val transactionRunner = RecordingTransactionRunner()
        val repository = RecordingEventRepository(transactionRunner = transactionRunner)
        val states = RecordingCalendarSyncStatePort(
            transactionRunner = transactionRunner,
            initialState = state(calendarUuid, syncToken = "sync-token"),
        )
        val publisher = RecordingDomainEventPublisher(transactionRunner)
        val gateway = RecordingGoogleCalendarEventsGateway(
            incrementalResults = listOf(
                CalendarSyncFetchResult.Events(
                    batch(
                        upserts = listOf(incoming("new-event")),
                        nextSyncToken = "next-token",
                    ),
                ).right(),
            ),
        )

        synchronizer(gateway, states, repository, transactionRunner, publisher)
            .incrementalSync(calendarUuid)
            .shouldBeRight()

        repository.upserted.map { it.googleEventId } shouldContainExactly listOf(gid("new-event"))
        publisher.published.map { it::class } shouldContainExactly listOf(EventScheduled::class)
        states.updatedTokens shouldContainExactly listOf("next-token")
        val writeTransactionIds = repository.upsertTransactionIds + publisher.publishTransactionIds +
            states.updateTransactionIds
        writeTransactionIds.distinct().size shouldBe 1
        gateway.incrementalCalls.map { it.syncToken } shouldContainExactly listOf("sync-token")
    }

    test("予定日時とリマインド時刻の変更は変更理由付き通知イベントを発行する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val eventUuid = EventUuid.new()
        val googleEventId = gid("changed-event")
        val oldEvent = event(
            eventUuid = eventUuid,
            userCalendarUuid = calendarUuid,
            googleEventId = googleEventId,
            content = content(
                schedule = schedule("2099-07-01T01:00:00Z", "2099-07-01T02:00:00Z"),
                remind = rt(10.minutes),
            ),
        )
        val repository = RecordingEventRepository(existing = mutableListOf(oldEvent))
        val publisher = RecordingDomainEventPublisher()
        val gateway = RecordingGoogleCalendarEventsGateway(
            incrementalResults = listOf(
                CalendarSyncFetchResult.Events(
                    batch(
                        upserts = listOf(
                            incoming(
                                value = "changed-event",
                                content = content(
                                    schedule = schedule("2099-07-01T03:00:00Z", "2099-07-01T04:00:00Z"),
                                    remind = rt(20.minutes),
                                ),
                            ),
                        ),
                    ),
                ).right(),
            ),
        )

        synchronizer(
            gateway = gateway,
            states = RecordingCalendarSyncStatePort(initialState = state(calendarUuid)),
            repository = repository,
            publisher = publisher,
        ).incrementalSync(calendarUuid).shouldBeRight()

        repository.upserted.map { it.eventUuid } shouldContainExactly listOf(eventUuid)
        publisher.published.map { it::class }.shouldContainExactlyInAnyOrder(
            EventRescheduled::class,
            EventNotificationTimingChanged::class,
        )
        publisher.published.map { it.targetEventUuid() }.shouldContainExactlyInAnyOrder(eventUuid, eventUuid)
        publisher.published.filterIsInstance<EventNotificationTimingChanged>().single().reason shouldBe
            NotificationTimingChangeReason.ScheduleAndRemindTimingChanged
    }

    test("Google 側削除は保存予定を削除し EventCancelled を発行する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val googleEventId = gid("cancelled-event")
        val existing = event(userCalendarUuid = calendarUuid, googleEventId = googleEventId)
        val repository = RecordingEventRepository(existing = mutableListOf(existing))
        val publisher = RecordingDomainEventPublisher()
        val gateway = RecordingGoogleCalendarEventsGateway(
            incrementalResults = listOf(
                CalendarSyncFetchResult.Events(
                    batch(cancellations = listOf(googleEventId)),
                ).right(),
            ),
        )

        synchronizer(
            gateway = gateway,
            states = RecordingCalendarSyncStatePort(initialState = state(calendarUuid)),
            repository = repository,
            publisher = publisher,
        ).incrementalSync(calendarUuid).shouldBeRight()

        repository.deletedGoogleEventIds shouldContainExactly listOf(googleEventId)
        publisher.published shouldContainExactly listOf(
            EventCancelled(existing.eventUuid, FixedClock.now()),
        )
    }

    test("変更のない予定は更新せず Domain Event を発行しない") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val googleEventId = gid("unchanged-event")
        val eventContent = content(
            schedule = schedule("2099-07-01T01:00:00Z", "2099-07-01T02:00:00Z"),
            remind = rt(10.minutes),
        )
        val repository = RecordingEventRepository(
            existing = mutableListOf(
                event(
                    userCalendarUuid = calendarUuid,
                    googleEventId = googleEventId,
                    content = eventContent,
                ),
            ),
        )
        val publisher = RecordingDomainEventPublisher()
        val gateway = RecordingGoogleCalendarEventsGateway(
            incrementalResults = listOf(
                CalendarSyncFetchResult.Events(
                    batch(upserts = listOf(incoming("unchanged-event", content = eventContent))),
                ).right(),
            ),
        )

        synchronizer(
            gateway = gateway,
            states = RecordingCalendarSyncStatePort(initialState = state(calendarUuid)),
            repository = repository,
            publisher = publisher,
        ).incrementalSync(calendarUuid).shouldBeRight()

        repository.upserted.shouldBeEmpty()
        publisher.published.shouldBeEmpty()
    }

    test("取得開始後に sync state が変わった場合は結果を破棄して最新状態から再実行する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val staleState = state(calendarUuid, syncToken = "old-token")
        val updatedState = state(calendarUuid, syncToken = "new-token")
        val states = RecordingCalendarSyncStatePort(
            initialState = staleState,
            lockedStates = ArrayDeque(listOf(updatedState, updatedState)),
        )
        val repository = RecordingEventRepository()
        val gateway = RecordingGoogleCalendarEventsGateway(
            incrementalResults = listOf(
                CalendarSyncFetchResult.Events(batch(upserts = listOf(incoming("stale-result")))).right(),
                CalendarSyncFetchResult.Events(batch(upserts = listOf(incoming("fresh-result")))).right(),
            ),
        )

        synchronizer(gateway, states, repository).incrementalSync(calendarUuid).shouldBeRight()

        gateway.incrementalCalls.map { it.syncToken } shouldContainExactly listOf("old-token", "new-token")
        repository.upserted.map { it.googleEventId } shouldContainExactly listOf(gid("fresh-result"))
    }

    test("同期状態が削除済みなら取得結果を破棄し予定と状態を再作成しない") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val states = RecordingCalendarSyncStatePort(
            initialState = state(calendarUuid),
            lockedStates = ArrayDeque(listOf(null)),
        )
        val repository = RecordingEventRepository()
        val publisher = RecordingDomainEventPublisher()
        val gateway = RecordingGoogleCalendarEventsGateway(
            incrementalResults = listOf(
                CalendarSyncFetchResult.Events(batch(upserts = listOf(incoming("discarded-event")))).right(),
            ),
        )

        synchronizer(gateway, states, repository, publisher = publisher)
            .incrementalSync(calendarUuid)
            .shouldBeRight()

        repository.upserted.shouldBeEmpty()
        publisher.published.shouldBeEmpty()
        states.updatedTokens.shouldBeEmpty()
    }

    test("完全同期で Google に存在しない保存予定を削除する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val kept = event(userCalendarUuid = calendarUuid, googleEventId = gid("kept-event"))
        val removed = event(userCalendarUuid = calendarUuid, googleEventId = gid("removed-event"))
        val repository = RecordingEventRepository(existing = mutableListOf(kept, removed))
        val publisher = RecordingDomainEventPublisher()
        val gateway = RecordingGoogleCalendarEventsGateway(
            fullResults = listOf(
                batch(upserts = listOf(incoming("kept-event", content = kept.eventContent))).right(),
            ),
        )
        val windowStart = Instant.parse("2099-07-01T00:00:00Z")
        val windowEnd = Instant.parse("2099-10-01T00:00:00Z")

        synchronizer(
            gateway = gateway,
            states = RecordingCalendarSyncStatePort(initialState = state(calendarUuid, materializedUntil = windowEnd)),
            repository = repository,
            publisher = publisher,
        ).fullSync(calendarUuid, windowStart, windowEnd).shouldBeRight()

        repository.deletedEventUuids shouldContainExactly listOf(removed.eventUuid)
        publisher.published shouldContainExactly listOf(EventCancelled(removed.eventUuid, FixedClock.now()))
        gateway.fullCalls.map { it.windowStart to it.windowEnd } shouldContainExactly listOf(windowStart to windowEnd)
    }

    test("完全同期中に materialized window が変わった場合は最新 window で再実行する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val windowStart = Instant.parse("2099-07-01T00:00:00Z")
        val staleWindowEnd = Instant.parse("2099-10-01T00:00:00Z")
        val latestWindowEnd = Instant.parse("2099-11-01T00:00:00Z")
        val states = RecordingCalendarSyncStatePort(
            initialState = state(calendarUuid, materializedUntil = staleWindowEnd),
            lockedStates = ArrayDeque(
                listOf(
                    state(calendarUuid, materializedUntil = latestWindowEnd),
                    state(calendarUuid, materializedUntil = latestWindowEnd),
                ),
            ),
        )
        val repository = RecordingEventRepository()
        val gateway = RecordingGoogleCalendarEventsGateway(
            fullResults = listOf(
                batch(upserts = listOf(incoming("stale-full-result"))).right(),
                batch(upserts = listOf(incoming("fresh-full-result"))).right(),
            ),
        )

        synchronizer(gateway, states, repository)
            .fullSync(calendarUuid, windowStart, staleWindowEnd)
            .shouldBeRight()

        gateway.fullCalls.map { it.windowStart to it.windowEnd } shouldContainExactly
            listOf(windowStart to staleWindowEnd, windowStart to latestWindowEnd)
        repository.upserted.map { it.googleEventId } shouldContainExactly listOf(gid("fresh-full-result"))
    }

    test("増分同期が410 Goneなら現在の materialized window で完全同期する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val materializedUntil = Instant.parse("2099-10-01T00:00:00Z")
        val gateway = RecordingGoogleCalendarEventsGateway(
            incrementalResults = listOf(CalendarSyncFetchResult.SyncTokenExpired.right()),
            fullResults = listOf(batch(upserts = listOf(incoming("full-event"))).right()),
        )
        val repository = RecordingEventRepository()

        synchronizer(
            gateway = gateway,
            states = RecordingCalendarSyncStatePort(
                initialState = state(calendarUuid, syncToken = "expired-token", materializedUntil = materializedUntil),
            ),
            repository = repository,
        ).incrementalSync(calendarUuid).shouldBeRight()

        gateway.incrementalCalls.map { it.syncToken } shouldContainExactly listOf("expired-token")
        gateway.fullCalls.map { it.windowStart to it.windowEnd } shouldContainExactly
            listOf(FixedClock.now() to materializedUntil)
        repository.upserted.map { it.googleEventId } shouldContainExactly listOf(gid("full-event"))
    }

    test("initialSync は開始時刻を固定し保存済み materializedUntil までを完全同期する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val materializedUntil = Instant.parse("2099-10-01T00:00:00Z")
        val gateway = RecordingGoogleCalendarEventsGateway(
            fullResults = listOf(batch(upserts = listOf(incoming("initial-event"))).right()),
        )

        synchronizer(
            gateway = gateway,
            states = RecordingCalendarSyncStatePort(
                initialState = state(calendarUuid, syncToken = null, materializedUntil = materializedUntil),
            ),
        ).initialSync(calendarUuid).shouldBeRight()

        gateway.fullCalls.map { it.windowStart to it.windowEnd } shouldContainExactly
            listOf(FixedClock.now() to materializedUntil)
    }
})

private data class IncrementalCall(val connection: CalendarConnection, val syncToken: String)

private data class FullCall(
    val connection: CalendarConnection,
    val windowStart: Instant,
    val windowEnd: Instant,
)

private class RecordingGoogleCalendarEventsGateway(
    incrementalResults: List<Either<EventError.ExternalError, CalendarSyncFetchResult>> = emptyList(),
    fullResults: List<Either<EventError.ExternalError, CalendarSyncBatch>> = emptyList(),
) : GoogleCalendarEventsGateway {
    private val incrementalResults = ArrayDeque(incrementalResults)
    private val fullResults = ArrayDeque(fullResults)
    val incrementalCalls = mutableListOf<IncrementalCall>()
    val fullCalls = mutableListOf<FullCall>()

    override suspend fun incrementalSync(
        connection: CalendarConnection,
        syncToken: String,
    ): Either<EventError.ExternalError, CalendarSyncFetchResult> {
        incrementalCalls += IncrementalCall(connection, syncToken)
        return incrementalResults.removeFirstOrNull()
            ?: EventError.ExternalError.GoogleCalendarError.left()
    }

    override suspend fun fullSync(
        connection: CalendarConnection,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError.ExternalError, CalendarSyncBatch> {
        fullCalls += FullCall(connection, windowStart, windowEnd)
        return fullResults.removeFirstOrNull()
            ?: EventError.ExternalError.GoogleCalendarError.left()
    }
}

private class RecordingConnectionProvider : CalendarConnectionProvider {
    override suspend fun connection(
        userCalendarUuid: UserCalendarUuid,
    ): Either<EventError.ExternalError, CalendarConnection> =
        CalendarConnection(calendarId = "primary", accessToken = "access-token").right()
}

private class RecordingCalendarSyncStatePort(
    private var initialState: CalendarSyncState?,
    private val lockedStates: ArrayDeque<CalendarSyncState?> = ArrayDeque(),
    private val transactionRunner: RecordingTransactionRunner? = null,
) : CalendarSyncStatePort {
    val updatedTokens = mutableListOf<String?>()
    val updateTransactionIds = mutableListOf<Int?>()

    override suspend fun find(userCalendarUuid: UserCalendarUuid): CalendarSyncState? = initialState

    override suspend fun findByChannelId(channelId: String): CalendarSyncState? = null

    override suspend fun lock(userCalendarUuid: UserCalendarUuid): CalendarSyncState? =
        if (lockedStates.isEmpty()) initialState else lockedStates.removeFirst()

    override suspend fun saveProvisioned(state: CalendarSyncState) {
        initialState = state
    }

    override suspend fun updateAfterSync(
        userCalendarUuid: UserCalendarUuid,
        nextSyncToken: String?,
        materializedUntil: Instant,
    ) {
        updatedTokens += nextSyncToken
        updateTransactionIds += transactionRunner?.currentTransactionId
        initialState = initialState?.copy(
            syncToken = nextSyncToken,
            materializedUntil = materializedUntil,
        )
    }

    override suspend fun replaceWatch(
        userCalendarUuid: UserCalendarUuid,
        expectedChannelId: String,
        watch: CalendarWatchRegistration,
    ): Boolean = false

    override suspend fun deleteIfChannelMatches(userCalendarUuid: UserCalendarUuid, channelId: String): Boolean = false

    override suspend fun delete(userCalendarUuid: UserCalendarUuid): Boolean {
        initialState = null
        return true
    }

    override suspend fun listAll(): List<CalendarSyncState> = listOfNotNull(initialState)
}

private class RecordingEventRepository(
    private val existing: MutableList<Event> = mutableListOf(),
    private val transactionRunner: RecordingTransactionRunner? = null,
) : EventRepository {
    val upserted = mutableListOf<Event>()
    val deletedGoogleEventIds = mutableListOf<GoogleEventId>()
    val deletedEventUuids = mutableListOf<EventUuid>()
    val upsertTransactionIds = mutableListOf<Int?>()

    override suspend fun upsertAll(events: List<Event>) {
        upserted += events
        upsertTransactionIds += events.map { transactionRunner?.currentTransactionId }
        existing.removeAll { current -> events.any { it.googleEventId == current.googleEventId } }
        existing += events
    }

    override suspend fun deleteByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ) {
        deletedGoogleEventIds += googleEventIds
        existing.removeAll { it.userCalendarUuid == userCalendarUuid && it.googleEventId in googleEventIds }
    }

    override suspend fun delete(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid) {
        deletedEventUuids += eventUuid
        existing.removeAll { it.userCalendarUuid == userCalendarUuid && it.eventUuid == eventUuid }
    }

    override suspend fun findByEventUuid(
        userCalendarUuid: UserCalendarUuid,
        eventUuid: EventUuid,
    ): Event? = existing.firstOrNull { it.userCalendarUuid == userCalendarUuid && it.eventUuid == eventUuid }

    override suspend fun findByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ): List<Event> =
        existing.filter { it.userCalendarUuid == userCalendarUuid && it.googleEventId in googleEventIds }

    override suspend fun findAllByUserCalendarUuid(userCalendarUuid: UserCalendarUuid): List<Event> =
        existing.filter { it.userCalendarUuid == userCalendarUuid }
}

private class RecordingDomainEventPublisher(
    private val transactionRunner: RecordingTransactionRunner? = null,
) : DomainEventPublisher {
    val published = mutableListOf<DomainEvent>()
    val publishTransactionIds = mutableListOf<Int?>()

    override suspend fun publish(event: DomainEvent) {
        published += event
        publishTransactionIds += transactionRunner?.currentTransactionId
    }
}

private class RecordingTransactionRunner : TransactionRunner {
    private var nextTransactionId = 0
    var currentTransactionId: Int? = null
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        val id = ++nextTransactionId
        currentTransactionId = id
        return try {
            block()
        } finally {
            currentTransactionId = null
        }
    }

    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private object FixedClock : Clock {
    override fun now(): Instant = Instant.parse("2099-07-01T00:00:00Z")
}

private fun synchronizer(
    gateway: GoogleCalendarEventsGateway,
    states: CalendarSyncStatePort,
    repository: EventRepository = RecordingEventRepository(),
    transactionRunner: TransactionRunner = DirectTransactionRunner,
    publisher: DomainEventPublisher = RecordingDomainEventPublisher(),
): GoogleCalendarEventSynchronizer =
    GoogleCalendarEventSynchronizer(
        gateway = gateway,
        connections = RecordingConnectionProvider(),
        states = states,
        events = repository,
        transactions = transactionRunner,
        publisher = publisher,
        clock = FixedClock,
    )

private fun batch(
    upserts: List<IncomingCalendarEvent> = emptyList(),
    cancellations: List<GoogleEventId> = emptyList(),
    nextSyncToken: String? = "next-token",
): CalendarSyncBatch =
    CalendarSyncBatch(
        upserts = upserts,
        cancellations = cancellations,
        nextSyncToken = nextSyncToken,
    )

private fun incoming(
    value: String,
    content: EventContent = content(schedule = schedule("2099-07-01T01:00:00Z", "2099-07-01T02:00:00Z")),
): IncomingCalendarEvent =
    IncomingCalendarEvent(
        googleEventId = gid(value),
        recurringEventId = null,
        originalStart = null,
        eventContent = content,
    )

private fun state(
    userCalendarUuid: UserCalendarUuid,
    syncToken: String? = "sync-token",
    materializedUntil: Instant? = Instant.parse("2099-10-01T00:00:00Z"),
): CalendarSyncState =
    CalendarSyncState(
        userCalendarUuid = userCalendarUuid,
        syncToken = syncToken,
        materializedUntil = materializedUntil,
        watchChannelId = "channel-id",
        watchResourceId = "resource-id",
        watchChannelToken = "channel-token",
        watchExpiration = null,
    )

private fun gid(value: String): GoogleEventId = either { googleEventId(value) }.getOrNull()!!

private fun rt(duration: kotlin.time.Duration): RemindTiming = either { remindTiming(duration) }.getOrNull()!!

private fun schedule(start: String, end: String): Schedule =
    either { schedule(startTime = Instant.parse(start), endTime = Instant.parse(end)) }.getOrNull()!!

private fun content(
    schedule: Schedule,
    remind: RemindTiming? = null,
    title: String = "title",
): EventContent =
    EventContent(
        title = title,
        description = null,
        location = null,
        schedule = schedule,
        remindTiming = remind,
    )

private fun event(
    eventUuid: EventUuid = EventUuid.new(),
    userCalendarUuid: UserCalendarUuid,
    googleEventId: GoogleEventId,
    content: EventContent = content(schedule = schedule("2099-07-01T01:00:00Z", "2099-07-01T02:00:00Z")),
): Event =
    Event.reconstitute(
        eventUuid = eventUuid,
        userCalendarUuid = userCalendarUuid,
        googleEventId = googleEventId,
        recurringEventId = null,
        originalStart = null,
        eventContent = content,
    )

private fun DomainEvent.targetEventUuid(): EventUuid =
    when (this) {
        is EventScheduled -> eventUuid
        is EventRescheduled -> eventUuid
        is EventNotificationTimingChanged -> eventUuid
        is EventCancelled -> eventUuid
        else -> error("unexpected domain event: $this")
    }
