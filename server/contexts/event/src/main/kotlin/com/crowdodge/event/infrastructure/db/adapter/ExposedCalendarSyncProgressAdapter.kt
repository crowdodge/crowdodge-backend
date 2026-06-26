package com.crowdodge.event.infrastructure.db.adapter

import com.crowdodge.event.application.port.CalendarSyncProgressPort
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.infrastructure.db.datasource.ExposedEventCalendarSyncDataSource
import kotlin.time.Instant

/**
 * [CalendarSyncProgressPort] の Exposed(R2DBC) 実装。連携状態テーブル（event_calendar_syncs）への
 * アクセスは [ExposedEventCalendarSyncDataSource] に委譲し、application へは syncToken 文字列だけを見せる。
 * トランザクションは呼び出し側（ユースケース）が貼る。
 */
class ExposedCalendarSyncProgressAdapter(
    private val dataSource: ExposedEventCalendarSyncDataSource,
) : CalendarSyncProgressPort {
    override suspend fun loadSyncToken(userCalendarUuid: UserCalendarUuid): String? =
        dataSource.findSyncToken(userCalendarUuid)

    override suspend fun saveSyncToken(userCalendarUuid: UserCalendarUuid, syncToken: String?) =
        dataSource.saveSyncToken(userCalendarUuid, syncToken)

    override suspend fun materializedUntil(userCalendarUuid: UserCalendarUuid): Instant? =
        dataSource.materializedUntil(userCalendarUuid)
}
