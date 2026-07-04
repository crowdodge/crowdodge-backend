package com.crowdodge.app.calendar

import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.service.DeprovisionGoogleCalendarSync
import com.crowdodge.event.application.service.GoogleCalendarSyncLifecycleService
import com.crowdodge.event.application.service.ReconcileGoogleCalendarSync
import com.crowdodge.user.application.service.SelectedCalendarConnection
import com.crowdodge.user.application.service.UserCalendarSelectionService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import com.crowdodge.event.domain.model.UserCalendarUuid as EventUserCalendarUuid

class MaintainGoogleCalendarSyncCoordinator(
    private val selections: UserCalendarSelectionService,
    private val syncs: GoogleCalendarSyncLifecycleService,
) {
    suspend fun execute(): MaintenanceResult {
        val snapshot = selections.inspectAllSelected().fold(
            ifLeft = { error ->
                throw GoogleCalendarMaintenanceStartupException(
                    "Failed to inspect selected Google Calendars: ${error.code}"
                )
            },
            ifRight = { it },
        )

        var inaccessibleSucceeded = 0
        var inaccessibleFailed = 0
        val preserved = mutableSetOf<EventUserCalendarUuid>()
        snapshot.inaccessible.forEach { connection ->
            runCatchingPreservingCancellation {
                selections.removeSelection(connection.userUuid, connection.userCalendarUuid)
                syncs.deprovisionSync(connection.toDeprovision())
            }.onSuccess {
                inaccessibleSucceeded += 1
            }.onFailure { error ->
                inaccessibleFailed += 1
                preserved += EventUserCalendarUuid(connection.userCalendarUuid.value)
                logger.warn(
                    "Failed to remove inaccessible Google Calendar selection: userCalendarUuid={}",
                    connection.userCalendarUuid,
                    error,
                )
            }
        }

        val syncResult = syncs.reconcile(
            selected = snapshot.eligible.map { it.toReconcile() },
            preservedUserCalendarUuids = preserved,
        )

        val result = MaintenanceResult(
            succeeded = syncResult.succeeded + inaccessibleSucceeded,
            failed = syncResult.failed + inaccessibleFailed,
        )
        logger.info(
            "Google Calendar maintenance finished: succeeded={}, failed={}",
            result.succeeded,
            result.failed,
        )
        return result
    }

    private fun SelectedCalendarConnection.toReconcile(): ReconcileGoogleCalendarSync =
        ReconcileGoogleCalendarSync(
            userCalendarUuid = EventUserCalendarUuid(userCalendarUuid.value),
            connection = CalendarConnection(googleCalendarId, accessToken),
        )

    private fun SelectedCalendarConnection.toDeprovision(): DeprovisionGoogleCalendarSync =
        DeprovisionGoogleCalendarSync(
            userCalendarUuid = EventUserCalendarUuid(userCalendarUuid.value),
            connection = CalendarConnection(googleCalendarId, accessToken),
        )

    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun <T> runCatchingPreservingCancellation(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(MaintainGoogleCalendarSyncCoordinator::class.java)
    }
}

data class MaintenanceResult(
    val succeeded: Int,
    val failed: Int,
)

class GoogleCalendarMaintenanceStartupException(message: String) : RuntimeException(message)
