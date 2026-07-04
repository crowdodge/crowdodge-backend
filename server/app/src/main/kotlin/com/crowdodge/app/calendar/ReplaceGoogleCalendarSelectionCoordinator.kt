package com.crowdodge.app.calendar

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.service.DeprovisionGoogleCalendarSync
import com.crowdodge.event.application.service.GoogleCalendarSyncLifecycleService
import com.crowdodge.event.application.service.ProvisionGoogleCalendarSync
import com.crowdodge.event.application.service.ProvisionedGoogleCalendarSync
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.service.SelectedCalendarConnection
import com.crowdodge.user.application.service.UserCalendarSelectionService
import com.crowdodge.user.domain.error.UserError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.crowdodge.event.domain.model.UserCalendarUuid as EventUserCalendarUuid

class ReplaceGoogleCalendarSelectionCoordinator(
    private val selections: UserCalendarSelectionService,
    private val syncs: GoogleCalendarSyncLifecycleService,
) {
    @Suppress("ReturnCount")
    suspend fun execute(
        userUuid: UserUuid,
        calendarIds: List<String>,
    ): Either<GoogleCalendarSelectionError, Unit> {
        val plan = selections.planReplacement(userUuid, calendarIds)
            .fold({ return GoogleCalendarSelectionError.User(it).left() }, { it })
        val provisioned = mutableListOf<ProvisionedGoogleCalendarSync>()
        for (addition in plan.additions) {
            val result = rollbackOnThrow(provisioned) {
                syncs.provisionSync(addition.toProvision())
            }
            result.fold(
                ifLeft = {
                    rollback(provisioned)
                    return GoogleCalendarSelectionError.Event(it).left()
                },
                ifRight = { provisioned += it },
            )
        }
        rollbackOnThrow(provisioned) {
            selections.commitReplacement(plan)
        }.fold(
            ifLeft = {
                rollback(provisioned)
                return GoogleCalendarSelectionError.User(it).left()
            },
            ifRight = {},
        )
        cleanupRemovals(plan.removals)
        return Unit.right()
    }

    private suspend fun cleanupRemovals(removals: List<SelectedCalendarConnection>) {
        withContext(NonCancellable) {
            removals.forEach { removal ->
                runCatching {
                    syncs.deprovisionSync(removal.toDeprovision())
                }.onFailure { error ->
                    logger.warn("Failed to deprovision removed Google Calendar sync", error)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> rollbackOnThrow(
        provisioned: List<ProvisionedGoogleCalendarSync>,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (failure: Throwable) {
            rollbackAfterFailure(provisioned, failure)
            throw failure
        }

    private suspend fun rollbackAfterFailure(
        provisioned: List<ProvisionedGoogleCalendarSync>,
        failure: Throwable,
    ) {
        if (failure !is CancellationException) {
            rollback(provisioned)
            return
        }
        try {
            withContext(NonCancellable) {
                rollback(provisioned)
            }
        } catch (cleanupCancellation: CancellationException) {
            failure.addSuppressed(cleanupCancellation)
        }
    }

    private suspend fun rollback(provisioned: List<ProvisionedGoogleCalendarSync>) {
        provisioned.asReversed().forEach {
            syncs.rollbackProvisioning(it)
        }
    }

    private fun SelectedCalendarConnection.toProvision(): ProvisionGoogleCalendarSync =
        ProvisionGoogleCalendarSync(
            userCalendarUuid = EventUserCalendarUuid(userCalendarUuid.value),
            connection = CalendarConnection(googleCalendarId, accessToken),
        )

    private fun SelectedCalendarConnection.toDeprovision(): DeprovisionGoogleCalendarSync =
        DeprovisionGoogleCalendarSync(
            userCalendarUuid = EventUserCalendarUuid(userCalendarUuid.value),
            connection = CalendarConnection(googleCalendarId, accessToken),
        )

    private companion object {
        private val logger = LoggerFactory.getLogger(ReplaceGoogleCalendarSelectionCoordinator::class.java)
    }
}

sealed interface GoogleCalendarSelectionError {
    data class User(val cause: UserError) : GoogleCalendarSelectionError

    data class Event(val cause: EventError) : GoogleCalendarSelectionError
}
