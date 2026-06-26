package com.crowdodge.event.infrastructure.db.adapter

import com.crowdodge.event.application.port.CalendarWatch
import com.crowdodge.event.application.port.CalendarWatchPort
import com.crowdodge.event.infrastructure.db.datasource.ExposedEventCalendarSyncDataSource

class ExposedCalendarWatchAdapter(
    private val dataSource: ExposedEventCalendarSyncDataSource,
) : CalendarWatchPort {
    override suspend fun findByChannelId(channelId: String): CalendarWatch? =
        dataSource.findByWatchChannelId(channelId)?.let {
            CalendarWatch(
                userCalendarUuid = it.userCalendarUuid,
                resourceId = it.watchResourceId,
                channelToken = it.watchChannelToken,
                expiration = it.watchExpiration,
            )
        }
}
