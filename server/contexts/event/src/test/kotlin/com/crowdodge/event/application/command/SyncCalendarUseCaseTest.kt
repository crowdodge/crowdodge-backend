package com.crowdodge.event.application.command

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.right
import com.crowdodge.event.application.port.CalendarSyncGateway
import com.crowdodge.event.application.port.CalendarSyncProgressPort
import com.crowdodge.event.application.port.CalendarSyncResult
import com.crowdodge.event.application.port.CalendarWatch
import com.crowdodge.event.application.port.CalendarWatchPort
import com.crowdodge.event.application.port.IncomingCalendarEvent
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.event.EventRemindTimingChanged
import com.crowdodge.event.domain.event.EventRescheduled
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SyncCalendarUseCaseTest : FunSpec({
    context("handle") {
        test("同期範囲より過去の予定更新は保存せずイベントも発行しない") {
            val calendarUuid = UserCalendarUuid(Uuid.random())
            val channelId = "channel-id"
            val googleEventId = gid("past-event")
            val repository = RecordingEventRepository()
            val publisher = RecordingDomainEventPublisher()
            val watch = InMemoryCalendarWatchPort(channelId = channelId, userCalendarUuid = calendarUuid)
            val progress = InMemoryCalendarSyncProgressPort(
                materializedUntil = Instant.parse("2100-01-01T00:00:00Z"),
            )
            val gateway = FixedCalendarSyncGateway(
                CalendarSyncResult(
                    upserts = listOf(
                        IncomingCalendarEvent(
                            googleEventId = googleEventId,
                            recurringEventId = null,
                            originalStart = null,
                            eventContent = content(
                                schedule = schedule(
                                    start = Instant.parse("2000-01-01T00:00:00Z"),
                                    end = Instant.parse("2000-01-01T01:00:00Z"),
                                ),
                            ),
                        ),
                    ),
                    cancellations = emptyList(),
                    nextSyncToken = "next-token",
                    isFullSync = false,
                ),
            )
            val useCase = SyncCalendarUseCase(
                gateway = gateway,
                watch = watch,
                events = repository,
                progress = progress,
                transactionRunner = DirectTransactionRunner,
                publisher = publisher,
            )

            useCase.handle(channelId).shouldBeRight()

            repository.upserted.shouldBeEmpty()
            repository.deletedGoogleEventIds.shouldBeEmpty()
            publisher.published.shouldBeEmpty()
            progress.savedSyncToken shouldBe "next-token"
        }

        test("予定日時変更は予定変更イベントとリマインド時刻変更イベントを両方発行する") {
            val calendarUuid = UserCalendarUuid(Uuid.random())
            val channelId = "channel-id"
            val googleEventId = gid("rescheduled-event")
            val oldSchedule = schedule(
                start = Instant.parse("2099-07-01T01:00:00Z"),
                end = Instant.parse("2099-07-01T02:00:00Z"),
            )
            val newSchedule = schedule(
                start = Instant.parse("2099-07-01T03:00:00Z"),
                end = Instant.parse("2099-07-01T04:00:00Z"),
            )
            val existing = event(calendarUuid, googleEventId, content(schedule = oldSchedule))
            val repository = RecordingEventRepository(existing = listOf(existing))
            val publisher = RecordingDomainEventPublisher()
            val progress = InMemoryCalendarSyncProgressPort(
                materializedUntil = Instant.parse("2100-01-01T00:00:00Z"),
            )
            val gateway = FixedCalendarSyncGateway(
                result = syncResult(
                    IncomingCalendarEvent(
                        googleEventId = googleEventId,
                        recurringEventId = null,
                        originalStart = null,
                        eventContent = content(schedule = newSchedule),
                    ),
                ),
            )

            useCase(
                gateway = gateway,
                repository = repository,
                progress = progress,
                publisher = publisher,
                watch = InMemoryCalendarWatchPort(channelId, calendarUuid),
            ).handle(channelId).shouldBeRight()

            repository.upserted.map { it.eventContent.schedule } shouldBe listOf(newSchedule)
            publisher.published.map { it::class }.shouldContainExactlyInAnyOrder(
                EventRescheduled::class,
                EventRemindTimingChanged::class,
            )
        }

        test("変更がない予定更新は upsert せずイベントも発行しない") {
            val calendarUuid = UserCalendarUuid(Uuid.random())
            val channelId = "channel-id"
            val googleEventId = gid("unchanged-event")
            val eventContent = content(
                schedule = schedule(
                    start = Instant.parse("2099-07-01T01:00:00Z"),
                    end = Instant.parse("2099-07-01T02:00:00Z"),
                ),
                remind = rt(10.minutes),
            )
            val repository = RecordingEventRepository(
                existing = listOf(event(calendarUuid, googleEventId, eventContent)),
            )
            val publisher = RecordingDomainEventPublisher()
            val progress = InMemoryCalendarSyncProgressPort(
                materializedUntil = Instant.parse("2100-01-01T00:00:00Z"),
            )
            val gateway = FixedCalendarSyncGateway(
                result = syncResult(
                    IncomingCalendarEvent(
                        googleEventId = googleEventId,
                        recurringEventId = null,
                        originalStart = null,
                        eventContent = eventContent,
                    ),
                ),
            )

            useCase(
                gateway = gateway,
                repository = repository,
                progress = progress,
                publisher = publisher,
                watch = InMemoryCalendarWatchPort(channelId, calendarUuid),
            ).handle(channelId).shouldBeRight()

            repository.upserted.shouldBeEmpty()
            publisher.published.shouldBeEmpty()
            progress.savedSyncToken shouldBe "next-token"
        }

        test("materializedUntil が未初期化なら Google API を呼ばず何もしない") {
            val calendarUuid = UserCalendarUuid(Uuid.random())
            val channelId = "channel-id"
            val repository = RecordingEventRepository()
            val publisher = RecordingDomainEventPublisher()
            val progress = InMemoryCalendarSyncProgressPort(materializedUntil = null)
            val gateway = FixedCalendarSyncGateway(result = syncResult())

            useCase(
                gateway = gateway,
                repository = repository,
                progress = progress,
                publisher = publisher,
                watch = InMemoryCalendarWatchPort(channelId, calendarUuid),
            ).handle(channelId).shouldBeRight()

            gateway.fetchCount shouldBe 0
            repository.upserted.shouldBeEmpty()
            repository.deletedGoogleEventIds.shouldBeEmpty()
            publisher.published.shouldBeEmpty()
            progress.savedSyncToken shouldBe null
        }

        test("未知の channelId は Google API を呼ばず何もしない") {
            val repository = RecordingEventRepository()
            val publisher = RecordingDomainEventPublisher()
            val progress = InMemoryCalendarSyncProgressPort(
                materializedUntil = Instant.parse("2100-01-01T00:00:00Z"),
            )
            val gateway = FixedCalendarSyncGateway(result = syncResult())

            useCase(
                gateway = gateway,
                repository = repository,
                progress = progress,
                publisher = publisher,
                watch = InMemoryCalendarWatchPort(
                    channelId = "known-channel-id",
                    userCalendarUuid = UserCalendarUuid(Uuid.random()),
                ),
            ).handle("unknown-channel-id").shouldBeRight()

            gateway.fetchCount shouldBe 0
            repository.upserted.shouldBeEmpty()
            repository.deletedGoogleEventIds.shouldBeEmpty()
            publisher.published.shouldBeEmpty()
            progress.savedSyncToken shouldBe null
        }
    }
})

private object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class FixedCalendarSyncGateway(
    private val result: CalendarSyncResult,
) : CalendarSyncGateway {
    var fetchCount = 0

    override suspend fun fetchUpdatedEvents(
        userCalendarUuid: UserCalendarUuid,
        syncToken: String?,
    ): Either<EventError.ExternalError, CalendarSyncResult> {
        fetchCount += 1
        return result.right()
    }
}

private class InMemoryCalendarSyncProgressPort(
    private val syncToken: String? = null,
    private val materializedUntil: Instant?,
) : CalendarSyncProgressPort {
    var savedSyncToken: String? = null

    override suspend fun loadSyncToken(userCalendarUuid: UserCalendarUuid): String? = syncToken

    override suspend fun saveSyncToken(userCalendarUuid: UserCalendarUuid, syncToken: String?) {
        savedSyncToken = syncToken
    }

    override suspend fun materializedUntil(userCalendarUuid: UserCalendarUuid): Instant? = materializedUntil
}

private class InMemoryCalendarWatchPort(
    private val channelId: String,
    private val userCalendarUuid: UserCalendarUuid,
) : CalendarWatchPort {
    override suspend fun findByChannelId(channelId: String): CalendarWatch? =
        if (channelId == this.channelId) {
            CalendarWatch(
                userCalendarUuid = userCalendarUuid,
                resourceId = "resource-id",
                channelToken = "channel-token",
                expiration = null,
            )
        } else {
            null
        }
}

private class RecordingEventRepository(
    private val existing: List<Event> = emptyList(),
) : EventRepository {
    val upserted = mutableListOf<Event>()
    val deletedGoogleEventIds = mutableListOf<GoogleEventId>()

    override suspend fun upsertAll(events: List<Event>) {
        upserted += events
    }

    override suspend fun deleteByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ) {
        deletedGoogleEventIds += googleEventIds
    }

    override suspend fun delete(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid) {
        error("not used")
    }

    override suspend fun findByEventUuid(
        userCalendarUuid: UserCalendarUuid,
        eventUuid: EventUuid,
    ): Event? = null

    override suspend fun findByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ): List<Event> =
        existing.filter { it.userCalendarUuid == userCalendarUuid && it.googleEventId in googleEventIds }

    override suspend fun findAllByUserCalendarUuid(userCalendarUuid: UserCalendarUuid): List<Event> =
        existing.filter { it.userCalendarUuid == userCalendarUuid }
}

private class RecordingDomainEventPublisher : DomainEventPublisher {
    val published = mutableListOf<DomainEvent>()

    override suspend fun publish(event: DomainEvent) {
        published += event
    }
}

private fun gid(value: String): GoogleEventId = either { googleEventId(value) }.getOrNull()!!

private fun rt(duration: kotlin.time.Duration): RemindTiming = either { remindTiming(duration) }.getOrNull()!!

private fun schedule(start: Instant, end: Instant): Schedule =
    either { schedule(startTime = start, endTime = end) }.getOrNull()!!

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
    userCalendarUuid: UserCalendarUuid,
    googleEventId: GoogleEventId,
    eventContent: EventContent,
): Event =
    Event.schedule(
        userCalendarUuid = userCalendarUuid,
        googleEventId = googleEventId,
        recurringEventId = null,
        originalStart = null,
        eventContent = eventContent,
    )

private fun syncResult(vararg upserts: IncomingCalendarEvent): CalendarSyncResult =
    CalendarSyncResult(
        upserts = upserts.toList(),
        cancellations = emptyList(),
        nextSyncToken = "next-token",
        isFullSync = false,
    )

private fun useCase(
    gateway: CalendarSyncGateway,
    repository: EventRepository,
    progress: CalendarSyncProgressPort,
    publisher: DomainEventPublisher,
    watch: CalendarWatchPort,
): SyncCalendarUseCase =
    SyncCalendarUseCase(
        gateway = gateway,
        watch = watch,
        events = repository,
        progress = progress,
        transactionRunner = DirectTransactionRunner,
        publisher = publisher,
    )
