package com.crowdodge.user.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarAccessRole
import com.crowdodge.user.application.port.GoogleCalendarListGateway
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.domain.repository.UserCalendarRepository

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

data class CalendarSelectionMaintenanceSnapshot(
    val eligible: List<SelectedCalendarConnection>,
    val inaccessible: List<SelectedCalendarConnection>,
)

class UserCalendarSelectionService(
    private val calendarList: GoogleCalendarListGateway,
    private val accessTokens: GoogleAccessTokenProvider,
    private val calendars: UserCalendarRepository,
    private val transactions: TransactionRunner,
) {
    suspend fun removeSelection(userUuid: UserUuid, userCalendarUuid: UserCalendarUuid) {
        transactions.inTransaction { calendars.delete(userUuid, userCalendarUuid) }
    }

    @Suppress("ReturnCount")
    suspend fun inspectAllSelected(): Either<UserError, CalendarSelectionMaintenanceSnapshot> {
        val all = transactions.readOnly { calendars.findAll() }
        val eligible = mutableListOf<SelectedCalendarConnection>()
        val inaccessible = mutableListOf<SelectedCalendarConnection>()
        for ((userUuid, selected) in all.groupBy { it.userUuid }) {
            val token = accessTokens.get(userUuid).fold({ return it.left() }, { it })
            val available = calendarList.listAll(userUuid).fold({ return it.left() }, { it })
                .associateBy { it.id }
            selected.forEach {
                val connection = SelectedCalendarConnection(
                    it.userCalendarUuid,
                    userUuid,
                    it.googleCalendarId.value,
                    token,
                )
                if (available[it.googleCalendarId.value]?.accessRole in ELIGIBLE_ROLES) {
                    eligible += connection
                } else {
                    inaccessible += connection
                }
            }
        }
        return CalendarSelectionMaintenanceSnapshot(eligible, inaccessible).right()
    }

    private companion object {
        val ELIGIBLE_ROLES = setOf(GoogleCalendarAccessRole.OWNER, GoogleCalendarAccessRole.WRITER)
    }
}
