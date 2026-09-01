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
import com.crowdodge.event.domain.model.EventContent
import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.GoogleEventId.Companion.googleEventId
import com.crowdodge.event.domain.model.Schedule.Companion.schedule
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.crowdodge.event.domain.model.UserCalendarUuid as EventUserCalendarUuid
import com.crowdodge.user.domain.model.UserCalendarUuid as UserUserCalendarUuid

class MaintainGoogleCalendarSyncCoordinatorTest : FunSpec({
    test("選択済みで同期状態がない場合はprovisionする") {
        val fixture = maintenanceFixture(selectedIds = listOf("work"))

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 1, failed = 0)
        fixture.watches.started.map { it.calendarId } shouldContainExactly listOf("work")
        fixture.states.saved.map { it.userCalendarUuid } shouldContainExactly listOf(fixture.eventUuid("work"))
    }

    test("sync tokenがnullなら初回同期する") {
        val fixture = maintenanceFixture(selectedIds = listOf("work"))
        fixture.states.states[fixture.eventUuid("work")] =
            syncState(fixture.eventUuid("work"), "channel-work", syncToken = null)

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 1, failed = 0)
        fixture.eventsGateway.fullSynced.map { it.calendarId } shouldContainExactly listOf("work")
    }

    test("未選択の同期状態をdeprovisionする") {
        val fixture = maintenanceFixture(selectedIds = emptyList())
        val orphan = EventUserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000909"))
        fixture.states.states[orphan] = syncState(orphan, "channel-orphan")
        fixture.connections.connections[orphan] = CalendarConnection("orphan", "orphan-token")

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 1, failed = 0)
        fixture.watches.stopped.map { it.channelId } shouldContainExactly listOf("channel-orphan")
        fixture.states.states.keys.shouldBeEmpty()
    }

    test("ownerまたはwriter権限を失った選択を解除する") {
        val fixture = maintenanceFixture(
            selectedIds = listOf("lost"),
            listItems = listOf(GoogleCalendarListItem("lost", "Lost", null, false, GoogleCalendarAccessRole.READER)),
        )
        fixture.states.states[fixture.eventUuid("lost")] = syncState(fixture.eventUuid("lost"), "channel-lost")

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 1, failed = 0)
        fixture.operations shouldContainExactly listOf("delete:lost", "stop:channel-lost", "state-delete:lost")
        fixture.repository.selectedIds().shouldBeEmpty()
    }

    test("期限24時間以内のwatchを更新して完全同期する") {
        val fixture = maintenanceFixture(selectedIds = listOf("work"))
        fixture.states.states[fixture.eventUuid("work")] = syncState(
            fixture.eventUuid("work"),
            "old-channel",
            syncToken = "sync-token",
            expiration = MaintenanceClock.now() + 1.hours,
        )

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 1, failed = 0)
        fixture.eventsGateway.fullSynced.map { it.calendarId } shouldContainExactly listOf("work")
        fixture.states.replacedWatches.map { it.second } shouldContainExactly listOf("old-channel")
        fixture.watches.stopped.map { it.channelId } shouldContainExactly listOf("old-channel")
    }

    test("新watch失敗時は旧watchを維持して他カレンダーを続行する") {
        val fixture = maintenanceFixture(selectedIds = listOf("expiring", "work"))
        fixture.states.states[fixture.eventUuid("expiring")] = syncState(
            fixture.eventUuid("expiring"),
            "old-channel",
            syncToken = "sync-token",
            expiration = MaintenanceClock.now() + 1.hours,
        )
        fixture.watches.failStartFor = "expiring"

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 1, failed = 1)
        fixture.states.states[fixture.eventUuid("expiring")]?.watchChannelId shouldBe "old-channel"
        fixture.states.saved.map { it.userCalendarUuid } shouldContainExactly listOf(fixture.eventUuid("work"))
    }

    test("並行PUTで生じた欠落と孤児を次回実行で修復する") {
        val fixture = maintenanceFixture(selectedIds = listOf("work"))
        val orphan = EventUserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000808"))
        fixture.states.states[orphan] = syncState(orphan, "channel-orphan")
        fixture.connections.connections[orphan] = CalendarConnection("orphan", "orphan-token")

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 2, failed = 0)
        fixture.states.saved.map { it.userCalendarUuid } shouldContainExactly listOf(fixture.eventUuid("work"))
        fixture.watches.stopped.map { it.channelId } shouldContainExactly listOf("channel-orphan")
    }

    test("カレンダー単位の失敗があってもJobは成功終了する") {
        val exitCode = runGoogleCalendarWatchRenewal {
            MaintenanceResult(succeeded = 1, failed = 1)
        }

        exitCode shouldBe 0
    }

    test("DB接続不能などJob全体を開始できない場合は失敗終了する") {
        val failure = IllegalStateException("database unavailable")

        val exitCode = runGoogleCalendarWatchRenewal {
            throw failure
        }

        exitCode shouldBe 1
    }

    test("User BCの選択解除に失敗した場合は同期状態と予定を残す") {
        val fixture = maintenanceFixture(
            selectedIds = listOf("lost"),
            listItems = listOf(GoogleCalendarListItem("lost", "Lost", null, false, GoogleCalendarAccessRole.READER)),
        )
        fixture.states.states[fixture.eventUuid("lost")] = syncState(fixture.eventUuid("lost"), "channel-lost")
        fixture.repository.deleteFailureFor = fixture.userUuid("lost")

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 0, failed = 1)
        fixture.watches.stopped.shouldBeEmpty()
        fixture.states.states.keys shouldContainExactly listOf(fixture.eventUuid("lost"))
    }

    test("選択一覧取得不能はJob全体の失敗として送出する") {
        val fixture = maintenanceFixture(selectedIds = listOf("work"))
        fixture.repository.findAllFailure = IllegalStateException("database unavailable")

        shouldThrow<IllegalStateException> {
            fixture.coordinator.execute()
        }
    }

    test("資格情報を取得できないユーザーの同期状態を保持して失敗件数へ加える") {
        val fixture = maintenanceFixture(
            selectedIds = listOf("work"),
            tokenFailure = UserError.AuthenticationError.InvalidRefreshToken,
        )
        fixture.states.states[fixture.eventUuid("work")] =
            syncState(fixture.eventUuid("work"), "channel-work")

        val result = fixture.coordinator.execute()

        result shouldBe MaintenanceResult(succeeded = 0, failed = 1)
        fixture.states.states.keys shouldContainExactly listOf(fixture.eventUuid("work"))
        fixture.watches.started.shouldBeEmpty()
        fixture.watches.stopped.shouldBeEmpty()
    }
})

private data class MaintenanceFixture(
    val coordinator: MaintainGoogleCalendarSyncCoordinator,
    val repository: MaintenanceUserCalendarRepository,
    val states: MaintenanceCalendarSyncStatePort,
    val watches: MaintenanceWatchGateway,
    val eventsGateway: MaintenanceEventsGateway,
    val connections: MaintenanceConnectionProvider,
    val operations: MutableList<String>,
) {
    fun userUuid(calendarId: String): UserUserCalendarUuid = repository.singleUuid(calendarId)
    fun eventUuid(calendarId: String): EventUserCalendarUuid = EventUserCalendarUuid(userUuid(calendarId).value)
}

private fun maintenanceFixture(
    selectedIds: List<String>,
    listItems: List<GoogleCalendarListItem> = selectedIds.map {
        GoogleCalendarListItem(it, it, null, false, GoogleCalendarAccessRole.OWNER)
    },
    tokenFailure: UserError? = null,
): MaintenanceFixture {
    val userUuid = UserUuid(Uuid.parse("00000000-0000-0000-0000-000000000001"))
    val operations = mutableListOf<String>()
    val transactions = MaintenanceTransactionRunner()
    val repository = MaintenanceUserCalendarRepository(operations)
    selectedIds.forEach { repository.add(userUuid, it) }
    val calendarList = GoogleCalendarListGateway { listItems.right() }
    val tokenProvider = maintenanceTokenProvider(userUuid, transactions, tokenFailure)
    val selections = UserCalendarSelectionService(
        calendarList = calendarList,
        accessTokens = tokenProvider,
        calendars = repository,
        transactions = transactions,
        publisher = DomainEventPublisher { },
    )
    val states = MaintenanceCalendarSyncStatePort(operations)
    val watches = MaintenanceWatchGateway(operations)
    val events = MaintenanceEventRepository()
    val connections = MaintenanceConnectionProvider()
    selectedIds.forEach {
        connections.connections[EventUserCalendarUuid(repository.singleUuid(it).value)] =
            CalendarConnection(it, "access-token")
    }
    val eventsGateway = MaintenanceEventsGateway()
    val synchronizer = GoogleCalendarEventSynchronizer(
        gateway = eventsGateway,
        connections = CalendarConnectionProvider { uuid ->
            connections.connection(uuid)
        },
        states = states,
        events = events,
        transactions = transactions,
        publisher = DomainEventPublisher { },
        clock = MaintenanceClock,
    )
    val syncs = GoogleCalendarSyncLifecycleService(
        watches = watches,
        states = states,
        events = events,
        synchronizer = synchronizer,
        connections = connections,
        transactions = transactions,
        clock = MaintenanceClock,
    )
    return MaintenanceFixture(
        coordinator = MaintainGoogleCalendarSyncCoordinator(selections, syncs),
        repository = repository,
        states = states,
        watches = watches,
        eventsGateway = eventsGateway,
        connections = connections,
        operations = operations,
    )
}

private fun maintenanceTokenProvider(
    userUuid: UserUuid,
    transactions: TransactionRunner,
    tokenFailure: UserError?,
) = object : GoogleAccessTokenProvider(
    credentials = MaintenanceCredentialRepository(userUuid),
    refreshGateway = GoogleOAuthTokenRefreshGateway { error("not called") },
    transactions = transactions,
    clock = MaintenanceClock,
) {
    override suspend fun get(userUuid: UserUuid): Either<UserError, String> =
        tokenFailure?.left() ?: super.get(userUuid)
}

private class MaintenanceWatchGateway(
    private val operations: MutableList<String>,
) : CalendarWatchRegistrationGateway {
    var failStartFor: String? = null
    val started = mutableListOf<CalendarConnection>()
    val stopped = mutableListOf<MaintenanceStoppedWatch>()

    override suspend fun startWatch(
        connection: CalendarConnection,
    ): Either<EventError.ExternalError, CalendarWatchRegistration> {
        if (connection.calendarId == failStartFor) return EventError.ExternalError.GoogleCalendarError.left()
        started += connection
        return CalendarWatchRegistration(
            channelId = "channel-${connection.calendarId}-${started.size}",
            resourceId = "resource-${connection.calendarId}-${started.size}",
            channelToken = "channel-token",
            expiration = MaintenanceClock.now() + 7.days,
        ).right()
    }

    override suspend fun stopWatch(
        connection: CalendarConnection,
        channelId: String,
        resourceId: String,
    ): Either<EventError.ExternalError, Unit> {
        operations += "stop:$channelId"
        stopped += MaintenanceStoppedWatch(connection, channelId, resourceId)
        return Unit.right()
    }
}

private data class MaintenanceStoppedWatch(
    val connection: CalendarConnection,
    val channelId: String,
    val resourceId: String,
)

private class MaintenanceCalendarSyncStatePort(
    private val operations: MutableList<String>,
) : CalendarSyncStatePort {
    val states = linkedMapOf<EventUserCalendarUuid, CalendarSyncState>()
    val saved = mutableListOf<CalendarSyncState>()
    val replacedWatches = mutableListOf<Pair<EventUserCalendarUuid, String>>()

    override suspend fun find(userCalendarUuid: EventUserCalendarUuid): CalendarSyncState? = states[userCalendarUuid]
    override suspend fun findByChannelId(channelId: String): CalendarSyncState? =
        states.values.firstOrNull { it.watchChannelId == channelId }

    override suspend fun lock(userCalendarUuid: EventUserCalendarUuid): CalendarSyncState? = states[userCalendarUuid]

    override suspend fun saveProvisioned(state: CalendarSyncState) {
        saved += state
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
    ): Boolean {
        replacedWatches += userCalendarUuid to expectedChannelId
        states[userCalendarUuid] = states.getValue(userCalendarUuid).copy(
            watchChannelId = watch.channelId,
            watchResourceId = watch.resourceId,
            watchChannelToken = watch.channelToken,
            watchExpiration = watch.expiration,
        )
        return true
    }

    override suspend fun deleteIfChannelMatches(userCalendarUuid: EventUserCalendarUuid, channelId: String): Boolean {
        if (states[userCalendarUuid]?.watchChannelId == channelId) {
            states.remove(userCalendarUuid)
            return true
        }
        return false
    }

    override suspend fun delete(userCalendarUuid: EventUserCalendarUuid): Boolean {
        operations += "state-delete:${states[userCalendarUuid]?.watchChannelId?.removePrefix("channel-")}"
        return states.remove(userCalendarUuid) != null
    }

    override suspend fun listAll(): List<CalendarSyncState> = states.values.toList()
}

private class MaintenanceUserCalendarRepository(
    private val operations: MutableList<String>,
) : UserCalendarRepository {
    private val calendars = mutableListOf<UserCalendar>()
    var deleteFailureFor: UserUserCalendarUuid? = null
    var findAllFailure: Throwable? = null

    fun add(userUuid: UserUuid, id: String): UserCalendar {
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
        if (userCalendarUuid == deleteFailureFor) error("delete failed")
        val calendarId = calendars.single { it.userCalendarUuid == userCalendarUuid }.googleCalendarId.value
        operations += "delete:$calendarId"
        calendars.removeIf { it.userUuid == userUuid && it.userCalendarUuid == userCalendarUuid }
    }

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserCalendar> =
        calendars.filter { it.userUuid == userUuid }

    override suspend fun findAll(): List<UserCalendar> {
        findAllFailure?.let { throw it }
        return calendars.toList()
    }
}

private class MaintenanceEventRepository : EventRepository {
    override suspend fun upsertAll(events: List<Event>) = Unit

    override suspend fun deleteByGoogleEventIds(
        userCalendarUuid: EventUserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ) = Unit

    override suspend fun delete(userCalendarUuid: EventUserCalendarUuid, eventUuid: EventUuid) = Unit
    override suspend fun findByEventUuid(userCalendarUuid: EventUserCalendarUuid, eventUuid: EventUuid): Event? = null
    override suspend fun findByGoogleEventIds(
        userCalendarUuid: EventUserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ): List<Event> = emptyList()

    override suspend fun findAllByUserCalendarUuid(userCalendarUuid: EventUserCalendarUuid): List<Event> =
        listOf(
            Event.reconstitute(
                eventUuid = EventUuid.new(),
                userCalendarUuid = userCalendarUuid,
                googleEventId = either { googleEventId("google-${userCalendarUuid.value}") }.getOrNull()!!,
                recurringEventId = null,
                originalStart = null,
                eventContent = either {
                    EventContent(
                        title = null,
                        description = null,
                        location = null,
                        schedule = schedule(MaintenanceClock.now(), MaintenanceClock.now() + 1.hours),
                        remindTiming = null,
                    )
                }.getOrNull()!!,
            ),
        )
}

private class MaintenanceEventsGateway : GoogleCalendarEventsGateway {
    val fullSynced = mutableListOf<CalendarConnection>()

    override suspend fun incrementalSync(
        connection: CalendarConnection,
        syncToken: String,
    ): Either<EventError.ExternalError, CalendarSyncFetchResult> =
        CalendarSyncFetchResult.Events(CalendarSyncBatch(emptyList(), emptyList(), "next-token")).right()

    override suspend fun fullSync(
        connection: CalendarConnection,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError.ExternalError, CalendarSyncBatch> {
        fullSynced += connection
        return CalendarSyncBatch(emptyList(), emptyList(), "next-token").right()
    }
}

private class MaintenanceConnectionProvider : CalendarConnectionProvider {
    val connections = mutableMapOf<EventUserCalendarUuid, CalendarConnection>()

    override suspend fun connection(
        userCalendarUuid: EventUserCalendarUuid,
    ): Either<EventError.ExternalError, CalendarConnection> =
        connections[userCalendarUuid]?.right() ?: EventError.ExternalError.GoogleCalendarError.left()
}

private class MaintenanceCredentialRepository(userUuid: UserUuid) : UserGoogleCredentialRepository {
    private val credential = either {
        UserGoogleCredential(
            userUuid = userUuid,
            googleSubject = googleSubject("google-subject"),
            accessToken = googleAccessToken("access-token"),
            refreshToken = null,
            accessTokenExpiresAt = MaintenanceClock.now() + 1.hours,
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

private class MaintenanceTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private object MaintenanceClock : Clock {
    override fun now(): Instant = Instant.parse("2026-07-01T00:00:00Z")
}

private fun syncState(
    userCalendarUuid: EventUserCalendarUuid,
    channelId: String,
    syncToken: String? = "sync-token",
    expiration: Instant? = MaintenanceClock.now() + 7.days,
): CalendarSyncState =
    CalendarSyncState(
        userCalendarUuid = userCalendarUuid,
        syncToken = syncToken,
        materializedUntil = MaintenanceClock.now() + 90.days,
        watchChannelId = channelId,
        watchResourceId = channelId.replace("channel", "resource"),
        watchChannelToken = channelId.replace("channel", "token"),
        watchExpiration = expiration,
    )
