package com.crowdodge.event.application.port

import com.crowdodge.event.domain.model.UserCalendarUuid
import kotlin.time.Instant

interface CalendarSyncStatePort {
    suspend fun find(userCalendarUuid: UserCalendarUuid): CalendarSyncState?

    suspend fun findByChannelId(channelId: String): CalendarSyncState?

    suspend fun lock(userCalendarUuid: UserCalendarUuid): CalendarSyncState?

    suspend fun saveProvisioned(state: CalendarSyncState)

    suspend fun updateAfterSync(
        userCalendarUuid: UserCalendarUuid,
        nextSyncToken: String?,
        materializedUntil: Instant,
    )

    suspend fun replaceWatch(
        userCalendarUuid: UserCalendarUuid,
        expectedChannelId: String,
        watch: CalendarWatchRegistration,
    ): Boolean

    suspend fun deleteIfChannelMatches(userCalendarUuid: UserCalendarUuid, channelId: String): Boolean

    suspend fun delete(userCalendarUuid: UserCalendarUuid): Boolean

    suspend fun listAll(): List<CalendarSyncState>
}

data class CalendarSyncState(
    val userCalendarUuid: UserCalendarUuid,
    val syncToken: String?,
    val materializedUntil: Instant?,
    val watchChannelId: String?,
    val watchResourceId: String?,
    val watchChannelToken: String?,
    val watchExpiration: Instant?,
)
