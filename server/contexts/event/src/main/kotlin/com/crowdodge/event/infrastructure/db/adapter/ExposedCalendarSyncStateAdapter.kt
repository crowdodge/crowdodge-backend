package com.crowdodge.event.infrastructure.db.adapter

import com.crowdodge.event.application.port.CalendarSyncState
import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.port.CalendarWatchRegistration
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.infrastructure.db.datasource.ExposedEventCalendarSyncDataSource
import com.crowdodge.event.infrastructure.db.model.EventCalendarSync
import kotlin.time.Instant

@Suppress("TooManyFunctions")
class ExposedCalendarSyncStateAdapter(
    private val dataSource: ExposedEventCalendarSyncDataSource = ExposedEventCalendarSyncDataSource(),
) : CalendarSyncStatePort {
    override suspend fun find(userCalendarUuid: UserCalendarUuid): CalendarSyncState? =
        dataSource.findByUserCalendarUuid(userCalendarUuid)?.toState()

    override suspend fun findByChannelId(channelId: String): CalendarSyncState? =
        dataSource.findByWatchChannelId(channelId)?.toState()

    override suspend fun lock(userCalendarUuid: UserCalendarUuid): CalendarSyncState? =
        dataSource.lockByUserCalendarUuid(userCalendarUuid)?.toState()

    override suspend fun saveProvisioned(state: CalendarSyncState) {
        dataSource.upsert(state.toSync())
    }

    override suspend fun updateAfterSync(
        userCalendarUuid: UserCalendarUuid,
        nextSyncToken: String?,
        materializedUntil: Instant,
    ) {
        dataSource.updateAfterSync(userCalendarUuid, nextSyncToken, materializedUntil)
    }

    override suspend fun replaceWatch(
        userCalendarUuid: UserCalendarUuid,
        expectedChannelId: String,
        watch: CalendarWatchRegistration,
    ): Boolean =
        dataSource.replaceWatch(userCalendarUuid, expectedChannelId, watch)

    override suspend fun deleteIfChannelMatches(userCalendarUuid: UserCalendarUuid, channelId: String): Boolean =
        dataSource.deleteIfChannelMatches(userCalendarUuid, channelId)

    override suspend fun delete(userCalendarUuid: UserCalendarUuid): Boolean =
        dataSource.delete(userCalendarUuid)

    override suspend fun listAll(): List<CalendarSyncState> =
        dataSource.listAll().map { it.toState() }

    private fun CalendarSyncState.toSync(): EventCalendarSync =
        EventCalendarSync(
            userCalendarUuid = userCalendarUuid,
            syncToken = syncToken,
            materializedUntil = materializedUntil,
            watchChannelId = watchChannelId,
            watchResourceId = watchResourceId,
            watchChannelToken = watchChannelToken,
            watchExpiration = watchExpiration,
        )

    private fun EventCalendarSync.toState(): CalendarSyncState =
        CalendarSyncState(
            userCalendarUuid = userCalendarUuid,
            syncToken = syncToken,
            materializedUntil = materializedUntil,
            watchChannelId = watchChannelId,
            watchResourceId = watchResourceId,
            watchChannelToken = watchChannelToken,
            watchExpiration = watchExpiration,
        )
}
