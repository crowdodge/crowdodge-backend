package com.crowdodge.notification.domain.repository

import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.model.NotificationSchedule
import kotlin.time.Instant

/**
 * NotificationSchedule 集約の永続化ポート。実装は infrastructure、トランザクション境界は application。
 */
interface NotificationScheduleRepository {
    suspend fun save(schedule: NotificationSchedule)

    suspend fun saveAll(schedules: List<NotificationSchedule>)

    suspend fun findPendingByEventUuid(eventUuid: EventUuid): List<NotificationSchedule>

    /** 当該予定の pending 行を物理削除する（再計算時の置き換え用）。 */
    suspend fun deletePendingByEventUuid(eventUuid: EventUuid)

    /** notificate_time が到来した pending 行。 */
    suspend fun findDue(now: Instant): List<NotificationSchedule>
}
