package com.crowdodge.event.application.service

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.port.CalendarConnectionProvider
import com.crowdodge.event.application.port.CalendarSyncState
import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.port.CalendarWatchRegistrationGateway
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.domain.repository.EventRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@Suppress("LongParameterList", "TooManyFunctions")
class GoogleCalendarSyncLifecycleService(
    private val watches: CalendarWatchRegistrationGateway,
    private val states: CalendarSyncStatePort,
    private val events: EventRepository,
    private val synchronizer: GoogleCalendarEventSynchronizer,
    private val connections: CalendarConnectionProvider,
    private val clock: Clock = Clock.System,
    private val materializationWindowDays: Int = DEFAULT_MATERIALIZATION_WINDOW_DAYS,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun provisionSync(request: ProvisionGoogleCalendarSync): Either<EventError, ProvisionedGoogleCalendarSync> =
        either {
            val registration = watches.startWatch(request.connection).bind()
            val materializedUntil = clock.now() + materializationWindowDays.days
            try {
                states.saveProvisioned(
                    CalendarSyncState(
                        userCalendarUuid = request.userCalendarUuid,
                        syncToken = null,
                        materializedUntil = materializedUntil,
                        watchChannelId = registration.channelId,
                        watchResourceId = registration.resourceId,
                        watchChannelToken = registration.channelToken,
                        watchExpiration = registration.expiration,
                    ),
                )
            } catch (error: Throwable) {
                stopWatchAfterFailure(
                    request.connection,
                    registration.channelId,
                    registration.resourceId,
                    error,
                )
                throw error
            }
            ProvisionedGoogleCalendarSync(
                userCalendarUuid = request.userCalendarUuid,
                connection = request.connection,
                channelId = registration.channelId,
                resourceId = registration.resourceId,
            )
        }.onLeft {
            logger.warn("Google Calendar sync provisioning failed: {}", it.code)
        }

    suspend fun rollbackProvisioning(provisioned: ProvisionedGoogleCalendarSync) {
        stopWatchBestEffort(provisioned.connection, provisioned.channelId, provisioned.resourceId)
        runCatchingPreservingCancellation {
            states.deleteIfChannelMatches(provisioned.userCalendarUuid, provisioned.channelId)
        }.onFailure { error ->
            logger.warn("Failed to rollback Google Calendar sync state", error)
        }
    }

    suspend fun deprovisionSync(request: DeprovisionGoogleCalendarSync) {
        val state = runCatchingPreservingCancellation { states.find(request.userCalendarUuid) }
            .onFailure { error -> logger.warn("Failed to load Google Calendar sync state", error) }
            .getOrNull()
        val channelId = state?.watchChannelId
        val resourceId = state?.watchResourceId
        if (channelId != null && resourceId != null) {
            stopWatchBestEffort(request.connection, channelId, resourceId)
        }
        if (deleteEventsBestEffort(request.userCalendarUuid)) {
            deleteStateBestEffort(request.userCalendarUuid)
        }
    }

    suspend fun reconcile(
        selected: List<ReconcileGoogleCalendarSync>,
        preservedUserCalendarUuids: Set<UserCalendarUuid> = emptySet(),
    ): ReconcileGoogleCalendarSyncResult {
        val selectedByUuid = selected.associateBy { it.userCalendarUuid }
        val currentStates = states.listAll().associateBy { it.userCalendarUuid }
        var succeeded = 0
        var failed = 0

        selected.forEach { selectedCalendar ->
            val reconciled = runCatchingPreservingCancellation {
                reconcileSelected(selectedCalendar, currentStates[selectedCalendar.userCalendarUuid])
            }.getOrElse { error ->
                logger.warn(
                    "Failed to reconcile selected Google Calendar: userCalendarUuid={}",
                    selectedCalendar.userCalendarUuid,
                    error,
                )
                false
            }
            if (reconciled) {
                succeeded += 1
            } else {
                failed += 1
                logger.warn(
                    "Google Calendar reconciliation failed: userCalendarUuid={}",
                    selectedCalendar.userCalendarUuid,
                )
            }
        }

        currentStates.values
            .filter { it.userCalendarUuid !in selectedByUuid && it.userCalendarUuid !in preservedUserCalendarUuids }
            .forEach { state ->
                val reconciled = runCatchingPreservingCancellation { deprovisionOrphan(state) }
                    .getOrElse { error ->
                        logger.warn(
                            "Failed to reconcile orphan Google Calendar sync: userCalendarUuid={}",
                            state.userCalendarUuid,
                            error,
                        )
                        false
                    }
                if (reconciled) {
                    succeeded += 1
                } else {
                    failed += 1
                    logger.warn(
                        "Google Calendar orphan reconciliation failed: userCalendarUuid={}",
                        state.userCalendarUuid,
                    )
                }
            }

        return ReconcileGoogleCalendarSyncResult(succeeded, failed)
    }

    private suspend fun reconcileSelected(
        selected: ReconcileGoogleCalendarSync,
        state: CalendarSyncState?,
    ): Boolean {
        if (state == null) {
            return provisionSync(ProvisionGoogleCalendarSync(selected.userCalendarUuid, selected.connection)).fold(
                ifLeft = {
                    logger.warn("Failed to provision Google Calendar sync: {}", it.code)
                    false
                },
                ifRight = {
                    synchronizer.initialSync(selected.userCalendarUuid).fold(
                        ifLeft = { error ->
                            logger.warn("Failed initial Google Calendar sync: {}", error.code)
                            false
                        },
                        ifRight = { true },
                    )
                },
            )
        }

        val initialSyncSucceeded =
            if (state.syncToken == null) {
                synchronizer.initialSync(selected.userCalendarUuid).fold(
                    ifLeft = { error ->
                        logger.warn("Failed initial Google Calendar sync: {}", error.code)
                        false
                    },
                    ifRight = { true },
                )
            } else {
                true
            }
        return if (!initialSyncSucceeded) {
            false
        } else if (state.watchExpiration != null &&
            state.watchExpiration <= clock.now() + WATCH_RENEWAL_THRESHOLD_HOURS.hours
        ) {
            renewWatch(selected, state)
        } else {
            true
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun renewWatch(
        selected: ReconcileGoogleCalendarSync,
        current: CalendarSyncState,
    ): Boolean {
        val oldChannelId = current.watchChannelId ?: return true
        val oldResourceId = current.watchResourceId ?: return true
        val newWatch = watches.startWatch(selected.connection).getOrElse {
            logger.warn("Failed to renew Google Calendar watch: {}", it.code)
            return false
        }
        val windowStart = clock.now()
        val windowEnd = windowStart + materializationWindowDays.days
        val fullSync = try {
            synchronizer.fullSync(selected.userCalendarUuid, windowStart, windowEnd)
        } catch (error: CancellationException) {
            stopWatchAfterFailure(selected.connection, newWatch.channelId, newWatch.resourceId, error)
            throw error
        } catch (error: Exception) {
            logger.warn("Full sync for renewed Google Calendar watch threw an exception", error)
            stopWatchBestEffort(selected.connection, newWatch.channelId, newWatch.resourceId)
            return false
        }
        fullSync.getOrElse {
            logger.warn("Failed full sync for renewed Google Calendar watch: {}", it.code)
            stopWatchBestEffort(selected.connection, newWatch.channelId, newWatch.resourceId)
            return false
        }
        val replaced = try {
            states.replaceWatch(selected.userCalendarUuid, oldChannelId, newWatch)
        } catch (error: CancellationException) {
            stopWatchAfterFailure(selected.connection, newWatch.channelId, newWatch.resourceId, error)
            throw error
        } catch (error: Exception) {
            logger.warn("Failed to replace Google Calendar watch state", error)
            stopWatchBestEffort(selected.connection, newWatch.channelId, newWatch.resourceId)
            return false
        }
        return if (replaced) {
            stopWatchBestEffort(selected.connection, oldChannelId, oldResourceId)
            true
        } else {
            stopWatchBestEffort(selected.connection, newWatch.channelId, newWatch.resourceId)
            false
        }
    }

    private suspend fun deprovisionOrphan(state: CalendarSyncState): Boolean {
        var succeeded = true
        val connection = connections.connection(state.userCalendarUuid).fold(
            ifLeft = {
                logger.warn("Failed to resolve orphan Google Calendar connection: {}", it.code)
                succeeded = false
                null
            },
            ifRight = { it },
        )
        val channelId = state.watchChannelId
        val resourceId = state.watchResourceId
        if (connection != null && channelId != null && resourceId != null) {
            succeeded = stopWatchBestEffort(connection, channelId, resourceId) && succeeded
        }
        if (!deleteEventsBestEffort(state.userCalendarUuid)) {
            return false
        }
        return deleteStateBestEffort(state.userCalendarUuid) && succeeded
    }

    private suspend fun deleteEventsBestEffort(userCalendarUuid: UserCalendarUuid): Boolean {
        val savedEvents = runCatchingPreservingCancellation { events.findAllByUserCalendarUuid(userCalendarUuid) }
            .onFailure { error -> logger.warn("Failed to list saved Google Calendar events", error) }
            .getOrElse { return false }
        var succeeded = true
        savedEvents.forEach { event ->
            runCatchingPreservingCancellation { events.delete(userCalendarUuid, event.eventUuid) }
                .onFailure { error ->
                    succeeded = false
                    logger.warn("Failed to delete saved Google Calendar event", error)
                }
        }
        return succeeded
    }

    private suspend fun deleteStateBestEffort(userCalendarUuid: UserCalendarUuid): Boolean =
        runCatchingPreservingCancellation { states.delete(userCalendarUuid) }
            .onFailure { error -> logger.warn("Failed to delete Google Calendar sync state", error) }
            .getOrDefault(false)

    private suspend fun stopWatchBestEffort(
        connection: CalendarConnection,
        channelId: String,
        resourceId: String,
    ): Boolean =
        runCatchingPreservingCancellation { watches.stopWatch(connection, channelId, resourceId) }
            .fold(
                onSuccess = { result ->
                    result.fold(
                        ifLeft = { error ->
                            logger.warn("Failed to stop Google Calendar watch: {}", error.code)
                            false
                        },
                        ifRight = { true },
                    )
                },
                onFailure = { error ->
                    logger.warn("Failed to stop Google Calendar watch", error)
                    false
                }
            )

    private suspend fun stopWatchAfterFailure(
        connection: CalendarConnection,
        channelId: String,
        resourceId: String,
        failure: Throwable,
    ) {
        if (failure !is CancellationException) {
            stopWatchBestEffort(connection, channelId, resourceId)
            return
        }
        try {
            withContext(NonCancellable) {
                stopWatchBestEffort(connection, channelId, resourceId)
            }
        } catch (cleanupCancellation: CancellationException) {
            failure.addSuppressed(cleanupCancellation)
        }
    }

    private inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> =
        runCatching(block).onFailure { error ->
            if (error is CancellationException) throw error
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(GoogleCalendarSyncLifecycleService::class.java)
        private const val DEFAULT_MATERIALIZATION_WINDOW_DAYS = 90
        private const val WATCH_RENEWAL_THRESHOLD_HOURS = 24
    }
}

data class ProvisionGoogleCalendarSync(
    val userCalendarUuid: UserCalendarUuid,
    val connection: CalendarConnection,
)

data class ProvisionedGoogleCalendarSync(
    val userCalendarUuid: UserCalendarUuid,
    val connection: CalendarConnection,
    val channelId: String,
    val resourceId: String,
)

data class DeprovisionGoogleCalendarSync(
    val userCalendarUuid: UserCalendarUuid,
    val connection: CalendarConnection,
)

data class ReconcileGoogleCalendarSync(
    val userCalendarUuid: UserCalendarUuid,
    val connection: CalendarConnection,
)

data class ReconcileGoogleCalendarSyncResult(
    val succeeded: Int,
    val failed: Int,
)
