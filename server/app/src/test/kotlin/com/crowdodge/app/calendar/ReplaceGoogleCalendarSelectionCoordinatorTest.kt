package com.crowdodge.app.calendar

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
import com.crowdodge.user.application.service.GoogleAccessTokenProvider
import com.crowdodge.user.application.service.UserCalendarSelectionService
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.event.CalendarInitialSyncRequested
import com.crowdodge.user.domain.model.GoogleAccessToken
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.GoogleSubject.Companion.googleSubject
import com.crowdodge.user.domain.model.GrantedGoogleScopes.Companion.grantedGoogleScopes
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.crowdodge.event.domain.model.UserCalendarUuid as EventUserCalendarUuid
import com.crowdodge.user.domain.model.UserCalendarUuid as UserUserCalendarUuid

class ReplaceGoogleCalendarSelectionCoordinatorTest : FunSpec({
    test("全追加watch成功後に選択を確定する") {
        val fixture = coordinatorFixture()

        val result = fixture.coordinator.execute(fixture.userUuid, listOf("work", "private"))

        result shouldBe Unit.right()
        fixture.watches.started.map { it.calendarId } shouldContainExactly listOf("work", "private")
        fixture.repository.selectedIds() shouldContainExactly listOf("work", "private")
        fixture.operations shouldContainExactly
            listOf("start:work", "save:work", "start:private", "save:private", "replace")
    }

    test("途中のwatch失敗時は作成済みwatchを補償して以前の選択を保つ") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        fixture.watches.failStartFor = "private"

        val result = fixture.coordinator.execute(fixture.userUuid, listOf("work", "private"))

        result shouldBe GoogleCalendarSelectionError.Event(EventError.ExternalError.GoogleCalendarError).left()
        fixture.repository.selectedIds() shouldContainExactly listOf("old")
        fixture.watches.stopped.map { it.channelId } shouldContainExactly listOf("channel-work")
        fixture.states.states.keys shouldContainExactly listOf(
            EventUserCalendarUuid(fixture.repository.singleUuid("old").value),
        )
    }

    test("選択確定失敗時は作成済みwatchを補償する") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        fixture.repository.failReplace = true

        val result = fixture.coordinator.execute(fixture.userUuid, listOf("work"))

        result shouldBe GoogleCalendarSelectionError.User(UserError.ConflictError.DuplicateCalendar).left()
        fixture.repository.selectedIds() shouldContainExactly listOf("old")
        fixture.watches.stopped.map { it.channelId } shouldContainExactly listOf("channel-work")
        fixture.states.states.keys shouldContainExactly listOf(
            EventUserCalendarUuid(fixture.repository.singleUuid("old").value),
        )
    }

    test("後続provisionがthrowしたら先行provision済みwatchを補償して例外を再throwする") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        val failure = IllegalStateException("save failed")
        fixture.states.saveFailureFor = "channel-private"
        fixture.states.saveFailure = failure

        shouldThrow<IllegalStateException> {
            fixture.coordinator.execute(fixture.userUuid, listOf("work", "private"))
        } shouldBe failure

        fixture.repository.selectedIds() shouldContainExactly listOf("old")
        fixture.watches.stopped.map { it.channelId } shouldContainExactly
            listOf("channel-private", "channel-work")
    }

    test("選択確定がthrowしたら全provision済みwatchを補償して例外を再throwする") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        val failure = IllegalStateException("replace failed")
        fixture.repository.replaceFailure = failure

        shouldThrow<IllegalStateException> {
            fixture.coordinator.execute(fixture.userUuid, listOf("work", "private"))
        } shouldBe failure

        fixture.repository.selectedIds() shouldContainExactly listOf("old")
        fixture.watches.stopped.map { it.channelId } shouldContainExactly
            listOf("channel-private", "channel-work")
    }

    test("選択確定のCancellationExceptionでも補償後に同じ例外を再throwする") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        val cancellation = CancellationException("replace cancelled")
        fixture.repository.replaceFailure = cancellation
        fixture.repository.cancelContextOnReplace = true

        val thrown = shouldThrow<CancellationException> {
            withContext(Job()) {
                fixture.coordinator.execute(fixture.userUuid, listOf("work", "private"))
            }
        }

        thrown shouldBe cancellation
        fixture.repository.selectedIds() shouldContainExactly listOf("old")
        fixture.watches.stopped.map { it.channelId } shouldContainExactly
            listOf("channel-private", "channel-work")
    }

    test("解除のwatch停止失敗は204応答を妨げない") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        fixture.watches.failStopFor = "channel-old"

        val result = fixture.coordinator.execute(fixture.userUuid, emptyList())

        result shouldBe Unit.right()
        fixture.repository.selectedIds() shouldBe emptyList()
        fixture.watches.stopped.map { it.channelId } shouldContainExactly listOf("channel-old")
    }

    test("解除cleanupのCancellationExceptionでも後続解除を続けて成功する") {
        val fixture = coordinatorFixture(existingIds = listOf("old", "private"))
        fixture.watches.stopFailureFor = "channel-old"
        fixture.watches.stopFailure = CancellationException("stop cancelled")

        val result = fixture.coordinator.execute(fixture.userUuid, emptyList())

        result shouldBe Unit.right()
        fixture.repository.selectedIds() shouldBe emptyList()
        fixture.watches.stopped.map { it.channelId } shouldContainExactly listOf("channel-old", "channel-private")
    }

    test("解除の同期状態削除失敗でも204を返してログへ記録する") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        fixture.states.failDelete = true

        val result = fixture.coordinator.execute(fixture.userUuid, emptyList())

        result shouldBe Unit.right()
        fixture.repository.selectedIds() shouldBe emptyList()
    }

    test("解除の予定削除失敗でも204を返してログへ記録する") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        fixture.events.failFindAllByUserCalendar = true

        val result = fixture.coordinator.execute(fixture.userUuid, emptyList())

        result shouldBe Unit.right()
        fixture.repository.selectedIds() shouldBe emptyList()
    }

    test("補償は別channelのwatchを削除しない") {
        val fixture = coordinatorFixture(existingIds = listOf("old"))
        fixture.watches.failStartFor = "private"
        fixture.states.states[EventUserCalendarUuid(fixture.repository.singleUuid("old").value)] =
            syncState(EventUserCalendarUuid(fixture.repository.singleUuid("old").value), "channel-old")

        fixture.coordinator.execute(fixture.userUuid, listOf("old", "work", "private"))

        fixture.watches.stopped.map { it.channelId } shouldContainExactly listOf("channel-work")
        fixture.states.states.keys shouldContainExactly
            listOf(EventUserCalendarUuid(fixture.repository.singleUuid("old").value))
    }

    test("CalendarInitialSyncRequestedをEvent BCのUUIDへ変換して初回同期する") {
        val userCalendarUuid = UserUserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000101"))
        val eventUserCalendarUuid = EventUserCalendarUuid(userCalendarUuid.value)
        val states = InMemoryCalendarSyncStatePort().also {
            it.states[eventUserCalendarUuid] = syncState(eventUserCalendarUuid, "channel-initial")
        }
        val gateway = RecordingEventsGateway()
        val synchronizer = recordingSynchronizer(gateway = gateway, states = states)
        val handler = CalendarInitialSyncRequestedHandler(synchronizer)

        handler.handle(CalendarInitialSyncRequested(userCalendarUuid, FixedClock.now()))

        gateway.fullSynced.map { it.calendarId } shouldContainExactly listOf("calendar")
    }
})

private data class CoordinatorFixture(
    val userUuid: UserUuid,
    val coordinator: ReplaceGoogleCalendarSelectionCoordinator,
    val repository: InMemoryUserCalendarRepository,
    val watches: RecordingWatchGateway,
    val states: InMemoryCalendarSyncStatePort,
    val events: RecordingEventRepository,
    val operations: MutableList<String>,
)

private fun coordinatorFixture(existingIds: List<String> = emptyList()): CoordinatorFixture {
    val userUuid = UserUuid(Uuid.parse("00000000-0000-0000-0000-000000000001"))
    val operations = mutableListOf<String>()
    val transactions = ImmediateTransactionRunner()
    val repository = InMemoryUserCalendarRepository(userUuid, operations)
    existingIds.forEach { repository.add(userUuid, it) }
    val calendarList = GoogleCalendarListGateway {
        listOf(
            GoogleCalendarListItem("old", "Old", null, false, GoogleCalendarAccessRole.OWNER),
            GoogleCalendarListItem("work", "Work", "#111111", false, GoogleCalendarAccessRole.WRITER),
            GoogleCalendarListItem("private", "Private", "#222222", false, GoogleCalendarAccessRole.OWNER),
        ).right()
    }
    val tokenProvider = GoogleAccessTokenProvider(
        credentials = CredentialRepository(userUuid),
        refreshGateway = GoogleOAuthTokenRefreshGateway { error("not called") },
        transactions = transactions,
        clock = FixedClock,
    )
    val selectionService = UserCalendarSelectionService(
        calendarList = calendarList,
        accessTokens = tokenProvider,
        calendars = repository,
        transactions = transactions,
        publisher = DomainEventPublisher { },
        clock = FixedClock,
    )
    val watches = RecordingWatchGateway(operations)
    val states = InMemoryCalendarSyncStatePort(operations)
    existingIds.forEach {
        val uuid = EventUserCalendarUuid(repository.singleUuid(it).value)
        states.states[uuid] = syncState(uuid, "channel-$it")
    }
    val events = RecordingEventRepository()
    val lifecycle = GoogleCalendarSyncLifecycleService(
        watches = watches,
        states = states,
        events = events,
        synchronizer = recordingSynchronizer(states = states, events = events),
        connections = CalendarConnectionProvider {
            CalendarConnection(it.value.toString(), "access-token").right()
        },
        transactions = transactions,
    )
    return CoordinatorFixture(
        userUuid,
        ReplaceGoogleCalendarSelectionCoordinator(selectionService, lifecycle),
        repository,
        watches,
        states,
        events,
        operations,
    )
}

private class RecordingWatchGateway(
    private val operations: MutableList<String>,
) : CalendarWatchRegistrationGateway {
    var failStartFor: String? = null
    var failStopFor: String? = null
    var stopFailureFor: String? = null
    var stopFailure: Throwable? = null
    val started = mutableListOf<CalendarConnection>()
    val stopped = mutableListOf<StoppedWatch>()

    override suspend fun startWatch(
        connection: CalendarConnection,
    ): Either<EventError.ExternalError, CalendarWatchRegistration> {
        operations += "start:${connection.calendarId}"
        if (connection.calendarId == failStartFor) return EventError.ExternalError.GoogleCalendarError.left()
        started += connection
        return CalendarWatchRegistration(
            channelId = "channel-${connection.calendarId}",
            resourceId = "resource-${connection.calendarId}",
            channelToken = "token-${connection.calendarId}",
            expiration = FixedClock.now() + 1.hours,
        ).right()
    }

    override suspend fun stopWatch(
        connection: CalendarConnection,
        channelId: String,
        resourceId: String,
    ): Either<EventError.ExternalError, Unit> {
        yield()
        stopped += StoppedWatch(connection, channelId, resourceId)
        if (channelId == stopFailureFor) throw checkNotNull(stopFailure)
        return if (channelId == failStopFor) {
            EventError.ExternalError.GoogleCalendarError.left()
        } else {
            Unit.right()
        }
    }
}

private data class StoppedWatch(
    val connection: CalendarConnection,
    val channelId: String,
    val resourceId: String,
)

private class InMemoryCalendarSyncStatePort(
    private val operations: MutableList<String> = mutableListOf(),
) : CalendarSyncStatePort {
    val states = linkedMapOf<EventUserCalendarUuid, CalendarSyncState>()
    var failDelete = false
    var saveFailureFor: String? = null
    var saveFailure: Throwable? = null

    override suspend fun find(userCalendarUuid: EventUserCalendarUuid): CalendarSyncState? = states[userCalendarUuid]
    override suspend fun findByChannelId(channelId: String): CalendarSyncState? =
        states.values.firstOrNull { it.watchChannelId == channelId }

    override suspend fun lock(userCalendarUuid: EventUserCalendarUuid): CalendarSyncState? = states[userCalendarUuid]

    override suspend fun saveProvisioned(state: CalendarSyncState) {
        operations += "save:${state.watchChannelId?.removePrefix("channel-")}"
        if (state.watchChannelId == saveFailureFor) throw checkNotNull(saveFailure)
        states[state.userCalendarUuid] = state
    }

    override suspend fun updateAfterSync(
        userCalendarUuid: EventUserCalendarUuid,
        nextSyncToken: String?,
        materializedUntil: Instant,
    ) {
        states[userCalendarUuid] = states.getValue(userCalendarUuid).copy(
            syncToken = nextSyncToken,
            materializedUntil = materializedUntil,
        )
    }

    override suspend fun replaceWatch(
        userCalendarUuid: EventUserCalendarUuid,
        expectedChannelId: String,
        watch: CalendarWatchRegistration,
    ): Boolean = false

    override suspend fun deleteIfChannelMatches(userCalendarUuid: EventUserCalendarUuid, channelId: String): Boolean =
        states[userCalendarUuid]?.takeIf { it.watchChannelId == channelId }?.let {
            states.remove(userCalendarUuid)
            true
        } ?: false

    override suspend fun delete(userCalendarUuid: EventUserCalendarUuid): Boolean {
        if (failDelete) error("delete failed")
        return states.remove(userCalendarUuid) != null
    }

    override suspend fun listAll(): List<CalendarSyncState> = states.values.toList()
}

private class InMemoryUserCalendarRepository(
    private val defaultUserUuid: UserUuid,
    private val operations: MutableList<String>,
) : UserCalendarRepository {
    var failReplace = false
    var replaceFailure: Throwable? = null
    var cancelContextOnReplace = false
    private val calendars = mutableListOf<UserCalendar>()

    fun add(userUuid: UserUuid = defaultUserUuid, id: String): UserCalendar {
        val calendar = either {
            UserCalendar.reconstitute(UserUserCalendarUuid.new(), userUuid, googleCalendarId(id))
        }.getOrNull()!!
        calendars += calendar
        return calendar
    }

    fun selectedIds(): List<String> = calendars.map { it.googleCalendarId.value }

    fun singleUuid(id: String): UserUserCalendarUuid =
        calendars.single { it.googleCalendarId.value == id }.userCalendarUuid

    override suspend fun create(userCalendar: UserCalendar): Either<UserError.ConflictError.DuplicateCalendar, Unit> {
        calendars += userCalendar
        return Unit.right()
    }

    override suspend fun delete(userUuid: UserUuid, userCalendarUuid: UserUserCalendarUuid) {
        calendars.removeIf { it.userUuid == userUuid && it.userCalendarUuid == userCalendarUuid }
    }

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserCalendar> =
        calendars.filter { it.userUuid == userUuid }

    override suspend fun replaceForUser(
        userUuid: UserUuid,
        calendars: List<UserCalendar>,
    ): Either<UserError.ConflictError.DuplicateCalendar, Unit> {
        operations += "replace"
        replaceFailure?.let { failure ->
            if (cancelContextOnReplace && failure is CancellationException) {
                currentCoroutineContext().cancel(failure)
            }
            throw failure
        }
        if (failReplace) return UserError.ConflictError.DuplicateCalendar.left()
        this.calendars.removeIf { it.userUuid == userUuid }
        this.calendars += calendars
        return Unit.right()
    }
}

private class RecordingEventRepository : EventRepository {
    var failFindAllByUserCalendar = false
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

    override suspend fun findAllByUserCalendarUuid(userCalendarUuid: EventUserCalendarUuid): List<Event> {
        if (failFindAllByUserCalendar) error("find failed")
        return emptyList()
    }
}

private fun recordingSynchronizer(
    gateway: RecordingEventsGateway = RecordingEventsGateway(),
    states: CalendarSyncStatePort = InMemoryCalendarSyncStatePort(),
    events: EventRepository = RecordingEventRepository(),
): GoogleCalendarEventSynchronizer = GoogleCalendarEventSynchronizer(
    gateway = gateway,
    connections = CalendarConnectionProvider { CalendarConnection("calendar", "token").right() },
    states = states,
    events = events,
    transactions = ImmediateTransactionRunner(),
    publisher = DomainEventPublisher { },
)

private class RecordingEventsGateway : GoogleCalendarEventsGateway {
    val fullSynced = mutableListOf<CalendarConnection>()

    override suspend fun incrementalSync(
        connection: CalendarConnection,
        syncToken: String,
    ): Either<EventError.ExternalError, CalendarSyncFetchResult> =
        CalendarSyncFetchResult.Events(CalendarSyncBatch(emptyList(), emptyList(), null)).right()

    override suspend fun fullSync(
        connection: CalendarConnection,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError.ExternalError, CalendarSyncBatch> {
        fullSynced += connection
        return CalendarSyncBatch(emptyList(), emptyList(), null).right()
    }
}

private class CredentialRepository(userUuid: UserUuid) : UserGoogleCredentialRepository {
    private val credential = either {
        UserGoogleCredential(
            userUuid = userUuid,
            googleSubject = googleSubject("google-subject"),
            accessToken = googleAccessToken("access-token"),
            refreshToken = null,
            accessTokenExpiresAt = FixedClock.now() + 1.hours,
            grantedScopes = grantedGoogleScopes(
                "https://www.googleapis.com/auth/calendar.events " +
                    "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
            ),
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

private class ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private object FixedClock : Clock {
    private val instant = Instant.parse("2026-01-01T00:00:00Z")
    override fun now(): Instant = instant
}

private fun syncState(userCalendarUuid: EventUserCalendarUuid, channelId: String): CalendarSyncState =
    CalendarSyncState(
        userCalendarUuid = userCalendarUuid,
        syncToken = null,
        materializedUntil = FixedClock.now() + 1.hours,
        watchChannelId = channelId,
        watchResourceId = channelId.replace("channel", "resource"),
        watchChannelToken = channelId.replace("channel", "token"),
        watchExpiration = FixedClock.now() + 1.hours,
    )
