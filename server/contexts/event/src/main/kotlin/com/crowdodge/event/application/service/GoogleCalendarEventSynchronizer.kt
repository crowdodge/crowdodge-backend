package com.crowdodge.event.application.service

import arrow.core.Either
import arrow.core.raise.either
import com.crowdodge.event.application.port.CalendarConnectionProvider
import com.crowdodge.event.application.port.CalendarSyncBatch
import com.crowdodge.event.application.port.CalendarSyncFetchResult
import com.crowdodge.event.application.port.CalendarSyncState
import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.port.GoogleCalendarEventsGateway
import com.crowdodge.event.application.port.IncomingCalendarEvent
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.event.EventCancelled
import com.crowdodge.event.domain.event.EventRemindTimingChanged
import com.crowdodge.event.domain.event.EventRescheduled
import com.crowdodge.event.domain.event.EventScheduled
import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import kotlin.time.Clock
import kotlin.time.Instant

@Suppress("LongParameterList", "TooManyFunctions")
class GoogleCalendarEventSynchronizer(
    private val gateway: GoogleCalendarEventsGateway,
    private val connections: CalendarConnectionProvider,
    private val states: CalendarSyncStatePort,
    private val events: EventRepository,
    private val transactions: TransactionRunner,
    private val publisher: DomainEventPublisher,
    private val clock: Clock = Clock.System,
) {
    suspend fun initialSync(userCalendarUuid: UserCalendarUuid): Either<EventError, Unit> =
        either {
            val state = transactions.readOnly { states.find(userCalendarUuid) } ?: return@either
            val windowEnd = state.materializedUntil ?: return@either
            fullSyncFromState(state, clock.now(), windowEnd).bind()
        }

    suspend fun incrementalSync(userCalendarUuid: UserCalendarUuid): Either<EventError, Unit> =
        either {
            var state = transactions.readOnly { states.find(userCalendarUuid) } ?: return@either
            while (true) {
                val windowEnd = state.materializedUntil ?: return@either
                val syncToken = state.syncToken
                    ?: return@either fullSyncFromState(state, clock.now(), windowEnd).bind()
                val connection = connections.connection(userCalendarUuid).bind()
                when (val result = gateway.incrementalSync(connection, syncToken).bind()) {
                    is CalendarSyncFetchResult.Events -> {
                        when (
                            val persistResult = persistIfCurrent(
                                state,
                                result.batch,
                                isFullSync = false,
                                windowStart = clock.now(),
                                windowEnd,
                            )
                        ) {
                            PersistResult.Persisted -> return@either
                            PersistResult.Deleted -> return@either
                            is PersistResult.Stale -> state = persistResult.current
                        }
                    }

                    CalendarSyncFetchResult.SyncTokenExpired -> {
                        fullSyncFromState(state, clock.now(), windowEnd).bind()
                        return@either
                    }
                }
            }
        }

    suspend fun fullSync(
        userCalendarUuid: UserCalendarUuid,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError, Unit> =
        either {
            val state = transactions.readOnly { states.find(userCalendarUuid) } ?: return@either
            fullSyncFromState(state, windowStart, windowEnd).bind()
        }

    private suspend fun fullSyncFromState(
        state: CalendarSyncState,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError, Unit> =
        either {
            var expected = state
            var currentWindowEnd = windowEnd
            while (true) {
                val connection = connections.connection(expected.userCalendarUuid).bind()
                val batch = gateway.fullSync(connection, windowStart, currentWindowEnd).bind()
                when (
                    val persistResult = persistIfCurrent(
                        expected,
                        batch,
                        isFullSync = true,
                        windowStart,
                        currentWindowEnd,
                    )
                ) {
                    PersistResult.Persisted -> return@either
                    PersistResult.Deleted -> return@either
                    is PersistResult.Stale -> {
                        expected = persistResult.current
                        currentWindowEnd = persistResult.current.materializedUntil ?: return@either
                    }
                }
            }
        }

    private suspend fun persistIfCurrent(
        expected: CalendarSyncState,
        batch: CalendarSyncBatch,
        isFullSync: Boolean,
        windowStart: Instant,
        windowEnd: Instant,
    ): PersistResult =
        transactions.inTransaction {
            val current = states.lock(expected.userCalendarUuid) ?: return@inTransaction PersistResult.Deleted
            if (!current.sameFetchBasisAs(expected)) return@inTransaction PersistResult.Stale(current)

            applyBatch(expected.userCalendarUuid, batch, isFullSync, windowStart, windowEnd)
            states.updateAfterSync(expected.userCalendarUuid, batch.nextSyncToken, windowEnd)
            PersistResult.Persisted
        }

    private suspend fun applyBatch(
        userCalendarUuid: UserCalendarUuid,
        batch: CalendarSyncBatch,
        isFullSync: Boolean,
        windowStart: Instant,
        windowEnd: Instant,
    ) {
        val (inWindow, outOfWindow) = batch.upserts
            .partition { it.isInMaterializationWindow(windowStart, windowEnd) }
        val existing = loadExisting(userCalendarUuid, batch, isFullSync).associateBy { it.googleEventId }

        val (toUpsert, upsertEvents) = classifyInWindow(
            userCalendarUuid = userCalendarUuid,
            incomingEvents = inWindow,
            existing = existing,
            cancelledIds = batch.cancellations.toSet(),
            occurredAt = windowStart,
        )
        val deletionEvents = deleteMissingEvents(
            userCalendarUuid = userCalendarUuid,
            batch = batch,
            isFullSync = isFullSync,
            outOfWindow = outOfWindow,
            existing = existing,
            occurredAt = windowStart,
        )

        events.upsertAll(toUpsert)
        (upsertEvents + deletionEvents).forEach { publisher.publish(it) }
    }

    private suspend fun loadExisting(
        userCalendarUuid: UserCalendarUuid,
        batch: CalendarSyncBatch,
        isFullSync: Boolean,
    ): List<Event> =
        if (isFullSync) {
            events.findAllByUserCalendarUuid(userCalendarUuid)
        } else {
            val touched = (batch.upserts.map { it.googleEventId } + batch.cancellations).distinct()
            events.findByGoogleEventIds(userCalendarUuid, touched)
        }

    private fun classifyInWindow(
        userCalendarUuid: UserCalendarUuid,
        incomingEvents: List<IncomingCalendarEvent>,
        existing: Map<GoogleEventId, Event>,
        cancelledIds: Set<GoogleEventId>,
        occurredAt: Instant,
    ): Pair<List<Event>, List<DomainEvent>> {
        val toUpsert = mutableListOf<Event>()
        val emitted = mutableListOf<DomainEvent>()

        incomingEvents.forEach { incoming ->
            if (incoming.googleEventId in cancelledIds) return@forEach

            val prior = existing[incoming.googleEventId]
            if (prior == null) {
                val created = Event.schedule(
                    userCalendarUuid = userCalendarUuid,
                    googleEventId = incoming.googleEventId,
                    recurringEventId = incoming.recurringEventId,
                    originalStart = incoming.originalStart,
                    eventContent = incoming.eventContent,
                )
                toUpsert += created
                emitted += EventScheduled(created.eventUuid, occurredAt)
                return@forEach
            }

            val contentChanged = prior.eventContent != incoming.eventContent
            if (!contentChanged) return@forEach

            toUpsert += prior.reproject(incoming.eventContent)

            val predictionChanged =
                prior.eventContent.copy(remindTiming = null) != incoming.eventContent.copy(remindTiming = null)
            val remindChanged = prior.eventContent.remindTiming != incoming.eventContent.remindTiming
            val scheduleChanged = prior.eventContent.schedule != incoming.eventContent.schedule

            if (predictionChanged) emitted += EventRescheduled(prior.eventUuid, occurredAt)
            if (remindChanged || scheduleChanged) {
                emitted += EventRemindTimingChanged(prior.eventUuid, occurredAt)
            }
        }

        return toUpsert to emitted
    }

    @Suppress("LongParameterList")
    private suspend fun deleteMissingEvents(
        userCalendarUuid: UserCalendarUuid,
        batch: CalendarSyncBatch,
        isFullSync: Boolean,
        outOfWindow: List<IncomingCalendarEvent>,
        existing: Map<GoogleEventId, Event>,
        occurredAt: Instant,
    ): List<DomainEvent> {
        val emitted = mutableListOf<DomainEvent>()
        val googleIdsToDelete = LinkedHashSet<GoogleEventId>()

        outOfWindow.forEach { incoming ->
            if (existing.containsKey(incoming.googleEventId)) googleIdsToDelete += incoming.googleEventId
        }
        batch.cancellations.forEach { googleEventId ->
            if (existing.containsKey(googleEventId)) googleIdsToDelete += googleEventId
        }

        if (googleIdsToDelete.isNotEmpty()) {
            events.deleteByGoogleEventIds(userCalendarUuid, googleIdsToDelete.toList())
            emitted += googleIdsToDelete.mapNotNull { googleEventId ->
                existing[googleEventId]?.let { EventCancelled(it.eventUuid, occurredAt) }
            }
        }

        if (isFullSync) {
            val present = batch.upserts.map { it.googleEventId }.toSet()
            existing.values
                .filter { it.googleEventId !in present && it.googleEventId !in googleIdsToDelete }
                .forEach { event ->
                    events.delete(userCalendarUuid, event.eventUuid)
                    emitted += EventCancelled(event.eventUuid, occurredAt)
                }
        }

        return emitted
    }

    private fun CalendarSyncState.sameFetchBasisAs(other: CalendarSyncState): Boolean =
        syncToken == other.syncToken && materializedUntil == other.materializedUntil

    private fun IncomingCalendarEvent.isInMaterializationWindow(
        windowStart: Instant,
        windowEnd: Instant,
    ): Boolean =
        eventContent.schedule.start() < windowEnd && eventContent.schedule.end() > windowStart

    private sealed interface PersistResult {
        data object Persisted : PersistResult

        data class Stale(val current: CalendarSyncState) : PersistResult

        data object Deleted : PersistResult
    }
}
