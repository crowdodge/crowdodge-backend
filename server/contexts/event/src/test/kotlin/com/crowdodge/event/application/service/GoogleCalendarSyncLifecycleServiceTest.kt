package com.crowdodge.event.application.service

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
import com.crowdodge.event.application.port.CalendarWatchRegistrationGateway
import com.crowdodge.event.application.port.GoogleCalendarEventsGateway
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.GoogleEventId.Companion.googleEventId
import com.crowdodge.event.domain.model.Schedule.Companion.schedule
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GoogleCalendarSyncLifecycleServiceTest : FunSpec({
    test("provisionSyncはwatch登録後に90日窓の同期状態を保存する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val states = LifecycleStatePort()
        val watches = RecordingCalendarWatchRegistrationGateway()

        val result = lifecycle(states = states, watches = watches)
            .provisionSync(ProvisionGoogleCalendarSync(calendarUuid, connection))
            .shouldBeRight()

        watches.startedConnections shouldContainExactly listOf(connection)
        states.saved shouldContainExactly listOf(
            CalendarSyncState(
                userCalendarUuid = calendarUuid,
                syncToken = null,
                materializedUntil = LifecycleClock.now() + 90.days,
                watchChannelId = "channel-1",
                watchResourceId = "resource-1",
                watchChannelToken = "channel-token",
                watchExpiration = Instant.parse("2026-07-02T00:00:00Z"),
            ),
        )
        result.userCalendarUuid shouldBe calendarUuid
        result.channelId shouldBe "channel-1"
        result.resourceId shouldBe "resource-1"
    }

    test("rollbackProvisioningは一致するchannelだけを削除する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val states = LifecycleStatePort()
        val watches = RecordingCalendarWatchRegistrationGateway()
        val provisioned = ProvisionedGoogleCalendarSync(
            userCalendarUuid = calendarUuid,
            connection = connection,
            channelId = "channel-1",
            resourceId = "resource-1",
        )

        lifecycle(states = states, watches = watches).rollbackProvisioning(provisioned)

        states.deletedIfChannelMatches shouldContainExactly listOf(calendarUuid to "channel-1")
        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
    }

    test("deprovisionSyncはwatch停止失敗でも予定と同期状態を削除する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val eventUuid = EventUuid.new()
        val states = LifecycleStatePort(
            initial = state(calendarUuid, channelId = "channel-1", resourceId = "resource-1"),
        )
        val watches = RecordingCalendarWatchRegistrationGateway(
            stopResult = EventError.ExternalError.GoogleCalendarError.left(),
        )
        val events = LifecycleEventRepository(existingEventUuids = listOf(eventUuid))

        lifecycle(states = states, watches = watches, events = events)
            .deprovisionSync(DeprovisionGoogleCalendarSync(calendarUuid, connection))

        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
        events.deletedEvents shouldContainExactly listOf(calendarUuid to eventUuid)
        states.deleted shouldContainExactly listOf(calendarUuid)
    }

    test("reconcileは同期状態一覧をreadOnlyトランザクション内で取得する") {
        val transactions = LifecycleTransactionGuard()
        val states = LifecycleStatePort(listAllGuard = transactions::requireReadOnly)

        lifecycle(states = states, transactions = transactions).reconcile(emptyList())
    }

    test("deprovisionSyncは予定取得と全削除と同期状態削除を同じwriteトランザクションで行う") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val transactions = LifecycleTransactionGuard()
        val transactionIds = mutableListOf<Int>()
        val states = LifecycleStatePort(
            initial = state(calendarUuid, channelId = "channel-1", resourceId = "resource-1"),
            deleteGuard = { transactionIds += transactions.requireWrite() },
        )
        val events = LifecycleEventRepository(
            existingEventUuids = listOf(EventUuid.new(), EventUuid.new()),
            findAllGuard = { transactionIds += transactions.requireWrite() },
            deleteGuard = { transactionIds += transactions.requireWrite() },
        )

        lifecycle(states = states, events = events, transactions = transactions)
            .deprovisionSync(DeprovisionGoogleCalendarSync(calendarUuid, connection))

        transactionIds.size shouldBe 4
        transactionIds.distinct().size shouldBe 1
    }

    test("provisionSyncは状態保存例外時に開始済みwatchを停止して例外を再送出する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val states = LifecycleStatePort(saveFailures = setOf(calendarUuid))
        val watches = RecordingCalendarWatchRegistrationGateway()

        shouldThrow<IllegalStateException> {
            lifecycle(states = states, watches = watches)
                .provisionSync(ProvisionGoogleCalendarSync(calendarUuid, connection))
        }

        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
    }

    test("deprovisionSyncは予定削除失敗時に同期状態を残す") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val eventUuid = EventUuid.new()
        val states = LifecycleStatePort(
            initial = state(calendarUuid, channelId = "channel-1", resourceId = "resource-1"),
        )
        val events = LifecycleEventRepository(
            existingEventUuids = listOf(eventUuid),
            deleteFailure = IllegalStateException("delete failed"),
        )

        lifecycle(states = states, events = events)
            .deprovisionSync(DeprovisionGoogleCalendarSync(calendarUuid, connection))

        states.deleted.shouldBeEmpty()
    }

    test("watch更新失敗時は古いwatchを維持する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val states = LifecycleStatePort(
            allStates = listOf(
                state(
                    calendarUuid,
                    channelId = "old-channel",
                    resourceId = "old-resource",
                    expiration = LifecycleClock.now() + 1.days,
                ),
            ),
        )
        val watches = RecordingCalendarWatchRegistrationGateway(
            startResult = EventError.ExternalError.GoogleCalendarError.left(),
        )

        val result = lifecycle(states = states, watches = watches).reconcile(
            selected = listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)),
        )

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 0, failed = 1)
        states.replacedWatches shouldBe emptyList()
        states.deletedIfChannelMatches shouldBe emptyList()
    }

    test("initialSync失敗をカレンダー単位の失敗として集計する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val current = state(
            calendarUuid,
            channelId = "channel-1",
            resourceId = "resource-1",
            syncToken = null,
        )
        val states = LifecycleStatePort(initial = current, allStates = listOf(current))

        val result = lifecycle(states = states).reconcile(
            selected = listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)),
        )

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 0, failed = 1)
    }

    test("watch更新のfullSync失敗時は新watchだけを停止する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val current = state(
            calendarUuid,
            channelId = "old-channel",
            resourceId = "old-resource",
            expiration = LifecycleClock.now() + 1.days,
        )
        val states = LifecycleStatePort(initial = current, allStates = listOf(current))
        val watches = RecordingCalendarWatchRegistrationGateway()

        val result = lifecycle(states = states, watches = watches).reconcile(
            selected = listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)),
        )

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 0, failed = 1)
        states.replacedWatches.shouldBeEmpty()
        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
    }

    test("watch更新のfullSyncが通常例外を送出しても新watchを停止して古い状態を維持する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val current = state(
            calendarUuid,
            channelId = "old-channel",
            resourceId = "old-resource",
            expiration = LifecycleClock.now() + 1.days,
        )
        val states = LifecycleStatePort(initial = current, allStates = listOf(current))
        val watches = RecordingCalendarWatchRegistrationGateway()

        val result = lifecycle(
            states = states,
            watches = watches,
            fullSyncFailure = IllegalStateException("full sync failed"),
        ).reconcile(selected = listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)))

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 0, failed = 1)
        states.replacedWatches.shouldBeEmpty()
        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
    }

    test("watch更新のfullSyncがキャンセルされたら新watchを停止してCancellationExceptionを再送出する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val current = state(
            calendarUuid,
            channelId = "old-channel",
            resourceId = "old-resource",
            expiration = LifecycleClock.now() + 1.days,
        )
        val states = LifecycleStatePort(initial = current, allStates = listOf(current))
        val watches = RecordingCalendarWatchRegistrationGateway()

        shouldThrow<CancellationException> {
            lifecycle(
                states = states,
                watches = watches,
                fullSyncFailure = CancellationException("cancelled"),
            ).reconcile(selected = listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)))
        }

        states.replacedWatches.shouldBeEmpty()
        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
    }

    test("watch更新のreplaceWatch失敗時は新watchだけを停止する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val current = state(
            calendarUuid,
            channelId = "old-channel",
            resourceId = "old-resource",
            expiration = LifecycleClock.now() + 1.days,
        )
        val states = LifecycleStatePort(initial = current, allStates = listOf(current), replaceResult = false)
        val watches = RecordingCalendarWatchRegistrationGateway()

        val result = lifecycle(
            states = states,
            watches = watches,
            fullSyncResult = CalendarSyncBatch(emptyList(), emptyList(), "next-token").right(),
        ).reconcile(selected = listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)))

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 0, failed = 1)
        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
    }

    test("watch更新成功後の古いwatch停止失敗は更新成功として集計する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val current = state(
            calendarUuid,
            channelId = "old-channel",
            resourceId = "old-resource",
            expiration = LifecycleClock.now() + 1.days,
        )
        val states = LifecycleStatePort(initial = current, allStates = listOf(current))
        val watches = RecordingCalendarWatchRegistrationGateway(
            stopResult = EventError.ExternalError.GoogleCalendarError.left(),
        )

        val result = lifecycle(
            states = states,
            watches = watches,
            fullSyncResult = CalendarSyncBatch(emptyList(), emptyList(), "next-token").right(),
        ).reconcile(selected = listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)))

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 1, failed = 0)
        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "old-channel", "old-resource"))
    }

    test("watch更新のreplaceWatch例外時は新watchだけを停止する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val current = state(
            calendarUuid,
            channelId = "old-channel",
            resourceId = "old-resource",
            expiration = LifecycleClock.now() + 1.days,
        )
        val states = LifecycleStatePort(
            initial = current,
            allStates = listOf(current),
            replaceFailure = IllegalStateException("replace failed"),
        )
        val watches = RecordingCalendarWatchRegistrationGateway()

        val result = lifecycle(
            states = states,
            watches = watches,
            fullSyncResult = CalendarSyncBatch(emptyList(), emptyList(), "next-token").right(),
        ).reconcile(selected = listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)))

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 0, failed = 1)
        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
    }

    test("orphanはconnectionを解決できる場合にwatchを停止して予定削除後に状態を削除する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val current = state(calendarUuid, channelId = "orphan-channel", resourceId = "orphan-resource")
        val order = mutableListOf<String>()
        val states = LifecycleStatePort(allStates = listOf(current), deletionOrder = order)
        val events = LifecycleEventRepository(existingEventUuids = listOf(EventUuid.new()), deletionOrder = order)
        val watches = RecordingCalendarWatchRegistrationGateway()
        val connections = RecordingLifecycleConnectionProvider(mapOf(calendarUuid to connection))

        val result = lifecycle(states, watches, events, connections = connections).reconcile(emptyList())

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 1, failed = 0)
        connections.requested shouldContainExactly listOf(calendarUuid)
        watches.stopped shouldContainExactly listOf(
            StoppedWatch(connection, "orphan-channel", "orphan-resource"),
        )
        order shouldContainExactly listOf("event", "state")
    }

    test("reconcileは1カレンダーの例外後も後続カレンダーを処理する") {
        val first = UserCalendarUuid(Uuid.random())
        val second = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val states = LifecycleStatePort(saveFailures = setOf(first))
        val watches = RecordingCalendarWatchRegistrationGateway()

        val result = lifecycle(states = states, watches = watches).reconcile(
            listOf(
                ReconcileGoogleCalendarSync(first, connection),
                ReconcileGoogleCalendarSync(second, connection),
            ),
        )

        result shouldBe ReconcileGoogleCalendarSyncResult(succeeded = 1, failed = 1)
        watches.startedConnections shouldContainExactly listOf(connection, connection)
        states.saved.map { it.userCalendarUuid } shouldContainExactly listOf(second)
    }

    test("reconcileはCancellationExceptionを通常失敗として継続せず再送出する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val states = LifecycleStatePort(
            saveFailureByUuid = mapOf(calendarUuid to CancellationException("cancelled")),
        )
        val watches = RecordingCalendarWatchRegistrationGateway()

        shouldThrow<CancellationException> {
            lifecycle(states = states, watches = watches).reconcile(
                listOf(ReconcileGoogleCalendarSync(calendarUuid, connection)),
            )
        }

        watches.stopped shouldContainExactly listOf(StoppedWatch(connection, "channel-1", "resource-1"))
    }

    test("deprovisionSyncは状態取得のCancellationExceptionを再送出する") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val connection = CalendarConnection(calendarId = "calendar-id", accessToken = "access-token")
        val states = LifecycleStatePort(findFailure = CancellationException("cancelled"))

        shouldThrow<CancellationException> {
            lifecycle(states = states)
                .deprovisionSync(DeprovisionGoogleCalendarSync(calendarUuid, connection))
        }

        states.deleted.shouldBeEmpty()
    }

    test("watch開始と停止で明示的なCalendarConnectionを使う") {
        val calendarUuid = UserCalendarUuid(Uuid.random())
        val provisionConnection = CalendarConnection(
            calendarId = "provision-calendar",
            accessToken = "provision-token",
        )
        val deprovisionConnection = CalendarConnection(
            calendarId = "deprovision-calendar",
            accessToken = "deprovision-token",
        )
        val states = LifecycleStatePort(
            initial = state(calendarUuid, channelId = "channel-1", resourceId = "resource-1"),
        )
        val watches = RecordingCalendarWatchRegistrationGateway()

        val service = lifecycle(states = states, watches = watches)
        service.provisionSync(ProvisionGoogleCalendarSync(calendarUuid, provisionConnection)).shouldBeRight()
        service.deprovisionSync(DeprovisionGoogleCalendarSync(calendarUuid, deprovisionConnection))

        watches.startedConnections shouldContainExactly listOf(provisionConnection)
        watches.stopped shouldContainExactly listOf(
            StoppedWatch(deprovisionConnection, "channel-1", "resource-1"),
        )
    }
})

@Suppress("LongParameterList")
private fun lifecycle(
    states: LifecycleStatePort = LifecycleStatePort(),
    watches: RecordingCalendarWatchRegistrationGateway = RecordingCalendarWatchRegistrationGateway(),
    events: LifecycleEventRepository = LifecycleEventRepository(),
    connections: CalendarConnectionProvider = RecordingLifecycleConnectionProvider(),
    fullSyncResult: Either<EventError.ExternalError, CalendarSyncBatch> =
        EventError.ExternalError.GoogleCalendarError.left(),
    fullSyncFailure: Throwable? = null,
    transactions: TransactionRunner = DirectLifecycleTransactionRunner,
): GoogleCalendarSyncLifecycleService =
    GoogleCalendarSyncLifecycleService(
        watches = watches,
        states = states,
        events = events,
        synchronizer = unusedSynchronizer(states, events, fullSyncResult, fullSyncFailure),
        connections = connections,
        transactions = transactions,
        clock = LifecycleClock,
    )

private object LifecycleClock : Clock {
    override fun now(): Instant = Instant.parse("2026-07-01T00:00:00Z")
}

private data class StoppedWatch(
    val connection: CalendarConnection,
    val channelId: String,
    val resourceId: String,
)

private class RecordingCalendarWatchRegistrationGateway(
    private val startResult: Either<EventError.ExternalError, CalendarWatchRegistration> = CalendarWatchRegistration(
        channelId = "channel-1",
        resourceId = "resource-1",
        channelToken = "channel-token",
        expiration = Instant.parse("2026-07-02T00:00:00Z"),
    ).right(),
    private val stopResult: Either<EventError.ExternalError, Unit> = Unit.right(),
) : CalendarWatchRegistrationGateway {
    val startedConnections = mutableListOf<CalendarConnection>()
    val stopped = mutableListOf<StoppedWatch>()

    override suspend fun startWatch(
        connection: CalendarConnection,
    ): Either<EventError.ExternalError, CalendarWatchRegistration> {
        startedConnections += connection
        return startResult
    }

    override suspend fun stopWatch(
        connection: CalendarConnection,
        channelId: String,
        resourceId: String,
    ): Either<EventError.ExternalError, Unit> {
        stopped += StoppedWatch(connection, channelId, resourceId)
        return stopResult
    }
}

@Suppress("LongParameterList")
private class LifecycleStatePort(
    private val initial: CalendarSyncState? = null,
    private val allStates: List<CalendarSyncState> = emptyList(),
    private val saveFailures: Set<UserCalendarUuid> = emptySet(),
    private val saveFailureByUuid: Map<UserCalendarUuid, Throwable> = emptyMap(),
    private val findFailure: Throwable? = null,
    private val replaceResult: Boolean = true,
    private val replaceFailure: Throwable? = null,
    private val deletionOrder: MutableList<String>? = null,
    private val listAllGuard: () -> Unit = {},
    private val deleteGuard: () -> Unit = {},
) : CalendarSyncStatePort {
    val saved = mutableListOf<CalendarSyncState>()
    val deletedIfChannelMatches = mutableListOf<Pair<UserCalendarUuid, String>>()
    val deleted = mutableListOf<UserCalendarUuid>()
    val replacedWatches = mutableListOf<Triple<UserCalendarUuid, String, CalendarWatchRegistration>>()

    override suspend fun find(userCalendarUuid: UserCalendarUuid): CalendarSyncState? {
        findFailure?.let { throw it }
        return initial
    }

    override suspend fun findByChannelId(channelId: String): CalendarSyncState? = null

    override suspend fun lock(userCalendarUuid: UserCalendarUuid): CalendarSyncState? = initial

    override suspend fun saveProvisioned(state: CalendarSyncState) {
        saveFailureByUuid[state.userCalendarUuid]?.let { throw it }
        if (state.userCalendarUuid in saveFailures) error("save failed")
        saved += state
    }

    override suspend fun updateAfterSync(
        userCalendarUuid: UserCalendarUuid,
        nextSyncToken: String?,
        materializedUntil: Instant,
    ) = Unit

    override suspend fun replaceWatch(
        userCalendarUuid: UserCalendarUuid,
        expectedChannelId: String,
        watch: CalendarWatchRegistration,
    ): Boolean {
        replaceFailure?.let { throw it }
        replacedWatches += Triple(userCalendarUuid, expectedChannelId, watch)
        return replaceResult
    }

    override suspend fun deleteIfChannelMatches(userCalendarUuid: UserCalendarUuid, channelId: String): Boolean {
        deletedIfChannelMatches += userCalendarUuid to channelId
        return true
    }

    override suspend fun delete(userCalendarUuid: UserCalendarUuid): Boolean {
        deleteGuard()
        deletionOrder?.add("state")
        deleted += userCalendarUuid
        return true
    }

    override suspend fun listAll(): List<CalendarSyncState> {
        listAllGuard()
        return allStates
    }
}

private class LifecycleEventRepository(
    existingEventUuids: List<EventUuid> = emptyList(),
    private val deleteFailure: Throwable? = null,
    private val deletionOrder: MutableList<String>? = null,
    private val findAllGuard: () -> Unit = {},
    private val deleteGuard: () -> Unit = {},
) : EventRepository {
    private val events = existingEventUuids.map {
        Event.reconstitute(
            eventUuid = it,
            userCalendarUuid = UserCalendarUuid(Uuid.random()),
            googleEventId = arrow.core.raise.either { googleEventId("google-${it.value}") }.getOrNull()!!,
            recurringEventId = null,
            originalStart = null,
            eventContent = arrow.core.raise.either {
                com.crowdodge.event.domain.model.EventContent(
                    title = null,
                    description = null,
                    location = null,
                    schedule = schedule(
                        startTime = LifecycleClock.now(),
                        endTime = LifecycleClock.now() + 1.days,
                    ),
                    remindTiming = null,
                )
            }.getOrNull()!!,
        )
    }
    val deletedEvents = mutableListOf<Pair<UserCalendarUuid, EventUuid>>()

    override suspend fun upsertAll(events: List<Event>) = Unit

    override suspend fun deleteByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ) = Unit

    override suspend fun delete(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid) {
        deleteGuard()
        deleteFailure?.let { throw it }
        deletionOrder?.add("event")
        deletedEvents += userCalendarUuid to eventUuid
    }

    override suspend fun findByEventUuid(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid): Event? = null

    override suspend fun findByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ): List<Event> = emptyList()

    override suspend fun findAllByUserCalendarUuid(userCalendarUuid: UserCalendarUuid): List<Event> {
        findAllGuard()
        return events.map { it.copyFor(userCalendarUuid) }
    }

    private fun Event.copyFor(userCalendarUuid: UserCalendarUuid): Event =
        Event.reconstitute(eventUuid, userCalendarUuid, googleEventId, recurringEventId, originalStart, eventContent)
}

private fun unusedSynchronizer(
    states: CalendarSyncStatePort,
    events: EventRepository,
    fullSyncResult: Either<EventError.ExternalError, CalendarSyncBatch>,
    fullSyncFailure: Throwable?,
): GoogleCalendarEventSynchronizer =
    GoogleCalendarEventSynchronizer(
        gateway = object : GoogleCalendarEventsGateway {
            override suspend fun incrementalSync(
                connection: CalendarConnection,
                syncToken: String,
            ): Either<EventError.ExternalError, CalendarSyncFetchResult> =
                EventError.ExternalError.GoogleCalendarError.left()

            override suspend fun fullSync(
                connection: CalendarConnection,
                windowStart: Instant,
                windowEnd: Instant,
            ): Either<EventError.ExternalError, CalendarSyncBatch> {
                fullSyncFailure?.let { throw it }
                return fullSyncResult
            }
        },
        connections = object : CalendarConnectionProvider {
            override suspend fun connection(
                userCalendarUuid: UserCalendarUuid,
            ): Either<EventError.ExternalError, CalendarConnection> =
                CalendarConnection(calendarId = "unused", accessToken = "unused").right()
        },
        states = states,
        events = events,
        transactions = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

            override suspend fun <T> readOnly(block: suspend () -> T): T = block()
        },
        publisher = object : DomainEventPublisher {
            override suspend fun publish(event: DomainEvent) = Unit
        },
        clock = LifecycleClock,
    )

private class RecordingLifecycleConnectionProvider(
    private val connections: Map<UserCalendarUuid, CalendarConnection> = emptyMap(),
) : CalendarConnectionProvider {
    val requested = mutableListOf<UserCalendarUuid>()

    override suspend fun connection(
        userCalendarUuid: UserCalendarUuid,
    ): Either<EventError.ExternalError, CalendarConnection> {
        requested += userCalendarUuid
        return connections[userCalendarUuid]?.right()
            ?: EventError.ExternalError.GoogleCalendarError.left()
    }
}

private object DirectLifecycleTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class LifecycleTransactionGuard : TransactionRunner {
    private var nextTransactionId = 0
    private var current: TransactionContext? = null

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        within(TransactionContext(++nextTransactionId, readOnly = false), block)

    override suspend fun <T> readOnly(block: suspend () -> T): T =
        within(TransactionContext(++nextTransactionId, readOnly = true), block)

    fun requireReadOnly() {
        check(current?.readOnly == true) { "read-only transaction required" }
    }

    fun requireWrite(): Int {
        val transaction = checkNotNull(current) { "write transaction required" }
        check(!transaction.readOnly) { "write transaction required" }
        return transaction.id
    }

    private suspend fun <T> within(context: TransactionContext, block: suspend () -> T): T {
        check(current == null) { "nested transaction is not supported" }
        current = context
        return try {
            block()
        } finally {
            current = null
        }
    }

    private data class TransactionContext(val id: Int, val readOnly: Boolean)
}

private fun state(
    userCalendarUuid: UserCalendarUuid,
    channelId: String,
    resourceId: String,
    expiration: Instant? = null,
    syncToken: String? = "sync-token",
): CalendarSyncState =
    CalendarSyncState(
        userCalendarUuid = userCalendarUuid,
        syncToken = syncToken,
        materializedUntil = LifecycleClock.now() + 90.days,
        watchChannelId = channelId,
        watchResourceId = resourceId,
        watchChannelToken = "channel-token",
        watchExpiration = expiration,
    )
