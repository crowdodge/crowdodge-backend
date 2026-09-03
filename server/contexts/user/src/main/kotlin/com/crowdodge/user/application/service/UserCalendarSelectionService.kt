package com.crowdodge.user.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarAccessRole
import com.crowdodge.user.application.port.GoogleCalendarListGateway
import com.crowdodge.user.application.port.GoogleCalendarListItem
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.event.CalendarInitialSyncRequested
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.domain.repository.UserCalendarRepository
import kotlin.time.Clock

data class SelectableGoogleCalendar(
    val id: String,
    val name: String,
    val color: String?,
    val primary: Boolean,
    val accessRole: GoogleCalendarAccessRole,
    val selected: Boolean,
)

data class SelectedUserCalendar(
    val userCalendarUuid: UserCalendarUuid,
    val userUuid: UserUuid,
    val googleCalendarId: String,
)

data class SelectedCalendarConnection(
    val userCalendarUuid: UserCalendarUuid,
    val userUuid: UserUuid,
    val googleCalendarId: String,
    val accessToken: String,
) {
    override fun toString(): String =
        "SelectedCalendarConnection(" +
            "userCalendarUuid=$userCalendarUuid, " +
            "userUuid=$userUuid, " +
            "googleCalendarId=$googleCalendarId, " +
            "accessToken=<redacted>)"
}

data class CalendarSelectionPlan(
    val userUuid: UserUuid,
    val additions: List<SelectedCalendarConnection>,
    val retained: List<SelectedUserCalendar>,
    val removals: List<SelectedCalendarConnection>,
)

data class CalendarSelectionMaintenanceSnapshot(
    val eligible: List<SelectedCalendarConnection>,
    val inaccessible: List<SelectedCalendarConnection>,
    val inspectionFailures: List<CalendarSelectionInspectionFailure>,
)

data class CalendarSelectionInspectionFailure(
    val selections: List<SelectedUserCalendar>,
    val error: UserError,
)

private data class InspectedCalendarSelections(
    val eligible: List<SelectedCalendarConnection>,
    val inaccessible: List<SelectedCalendarConnection>,
)

private val eligibleCalendarAccessRoles =
    setOf(GoogleCalendarAccessRole.OWNER, GoogleCalendarAccessRole.WRITER)

class UserCalendarSelectionService(
    private val calendarList: GoogleCalendarListGateway,
    private val accessTokens: GoogleAccessTokenProvider,
    private val calendars: UserCalendarRepository,
    private val transactions: TransactionRunner,
    private val publisher: DomainEventPublisher,
    private val clock: Clock = Clock.System,
) {
    suspend fun listAvailable(userUuid: UserUuid): Either<UserError, List<SelectableGoogleCalendar>> {
        val available = calendarList.listAll(userUuid).fold({ return it.left() }, { it })
        val selectedIds = transactions.readOnly { calendars.findByUserUuid(userUuid) }
            .mapTo(mutableSetOf()) { it.googleCalendarId.value }
        return available.map { it.toSelectable(it.id in selectedIds) }.right()
    }

    @Suppress("ReturnCount")
    suspend fun planReplacement(
        userUuid: UserUuid,
        calendarIds: List<String>,
    ): Either<UserError, CalendarSelectionPlan> {
        if (calendarIds.size > MAX_SELECTIONS) return UserError.ValidationError.TooManyCalendarSelections.left()
        if (calendarIds.distinct().size != calendarIds.size) {
            return UserError.ValidationError.DuplicateCalendarSelectionInput.left()
        }
        val available = calendarList.listAll(userUuid).fold({ return it.left() }, { it })
        val byId = available.associateBy { it.id }
        if (calendarIds.any { byId[it]?.accessRole !in eligibleCalendarAccessRoles }) {
            return UserError.AuthorizationError.InsufficientCalendarAccess.left()
        }
        val token = accessTokens.get(userUuid).fold({ return it.left() }, { it })
        val existing = transactions.readOnly { calendars.findByUserUuid(userUuid) }
        val existingById = existing.associateBy { it.googleCalendarId.value }
        val requested = calendarIds.toSet()
        val retained = calendarIds.mapNotNull(existingById::get).map { it.toSelected() }
        val additions = calendarIds.filterNot(existingById::containsKey).map { id ->
            SelectedCalendarConnection(UserCalendarUuid.new(), userUuid, id, token)
        }
        val removals = existing.filter { it.googleCalendarId.value !in requested }.map {
            SelectedCalendarConnection(it.userCalendarUuid, userUuid, it.googleCalendarId.value, token)
        }
        return CalendarSelectionPlan(userUuid, additions, retained, removals).right()
    }

    suspend fun commitReplacement(plan: CalendarSelectionPlan): Either<UserError, Unit> {
        val replacement = buildList {
            plan.retained.forEach {
                add(reconstitute(it))
            }
            plan.additions.forEach {
                add(reconstitute(it))
            }
        }
        return try {
            transactions.inTransaction {
                calendars.replaceForUser(plan.userUuid, replacement).fold(
                    ifLeft = { throw CalendarReplacementFailed(it) },
                    ifRight = {},
                )
                plan.additions.forEach {
                    publisher.publish(CalendarInitialSyncRequested(it.userCalendarUuid, clock.now()))
                }
            }
            Unit.right()
        } catch (exception: CalendarReplacementFailed) {
            exception.error.left()
        }
    }

    suspend fun removeSelection(userUuid: UserUuid, userCalendarUuid: UserCalendarUuid) {
        transactions.inTransaction { calendars.delete(userUuid, userCalendarUuid) }
    }

    suspend fun listSelected(userUuid: UserUuid): List<SelectedUserCalendar> =
        transactions.readOnly { calendars.findByUserUuid(userUuid) }.map { it.toSelected() }

    suspend fun inspectAllSelected(): Either<UserError, CalendarSelectionMaintenanceSnapshot> {
        val all = transactions.readOnly { calendars.findAll() }
        val eligible = mutableListOf<SelectedCalendarConnection>()
        val inaccessible = mutableListOf<SelectedCalendarConnection>()
        val inspectionFailures = mutableListOf<CalendarSelectionInspectionFailure>()
        all.groupBy { it.userUuid }.forEach { (userUuid, selected) ->
            inspectSelectedForUser(accessTokens, calendarList, userUuid, selected).fold(
                ifLeft = { inspectionFailures += it },
                ifRight = {
                    eligible += it.eligible
                    inaccessible += it.inaccessible
                },
            )
        }
        return CalendarSelectionMaintenanceSnapshot(eligible, inaccessible, inspectionFailures).right()
    }

    private fun GoogleCalendarListItem.toSelectable(selected: Boolean) =
        SelectableGoogleCalendar(id, name, color, primary, accessRole, selected)

    private fun UserCalendar.toSelected() =
        SelectedUserCalendar(userCalendarUuid, userUuid, googleCalendarId.value)

    private fun reconstitute(selected: SelectedUserCalendar): UserCalendar = either {
        UserCalendar.reconstitute(
            selected.userCalendarUuid,
            selected.userUuid,
            googleCalendarId(selected.googleCalendarId),
        )
    }.getOrNull() ?: error("validated calendar ID became invalid")

    private fun reconstitute(selected: SelectedCalendarConnection): UserCalendar = either {
        UserCalendar.reconstitute(
            selected.userCalendarUuid,
            selected.userUuid,
            googleCalendarId(selected.googleCalendarId),
        )
    }.getOrNull() ?: error("validated calendar ID became invalid")

    private companion object {
        const val MAX_SELECTIONS = 3
    }
}

private suspend fun inspectSelectedForUser(
    accessTokens: GoogleAccessTokenProvider,
    calendarList: GoogleCalendarListGateway,
    userUuid: UserUuid,
    selected: List<UserCalendar>,
): Either<CalendarSelectionInspectionFailure, InspectedCalendarSelections> = either {
    val selectedCalendars = selected.map {
        SelectedUserCalendar(it.userCalendarUuid, it.userUuid, it.googleCalendarId.value)
    }
    fun failure(error: UserError) = CalendarSelectionInspectionFailure(selectedCalendars, error)
    val token = accessTokens.get(userUuid).mapLeft(::failure).bind()
    val available = calendarList.listAll(userUuid)
        .mapLeft(::failure)
        .bind()
        .associateBy(GoogleCalendarListItem::id)
    val (eligible, inaccessible) = selected
        .map {
            val connection = SelectedCalendarConnection(
                it.userCalendarUuid,
                userUuid,
                it.googleCalendarId.value,
                token,
            )
            connection to (available[it.googleCalendarId.value]?.accessRole in eligibleCalendarAccessRoles)
        }
        .partition { it.second }
    InspectedCalendarSelections(
        eligible = eligible.map { it.first },
        inaccessible = inaccessible.map { it.first },
    )
}

private class CalendarReplacementFailed(
    val error: UserError.ConflictError.DuplicateCalendar,
) : RuntimeException()
