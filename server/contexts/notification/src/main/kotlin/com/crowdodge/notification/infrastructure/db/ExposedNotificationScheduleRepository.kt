package com.crowdodge.notification.infrastructure.db

import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.model.NotificationKind
import com.crowdodge.notification.domain.model.NotificationSchedule
import com.crowdodge.notification.domain.model.NotificationScheduleUuid
import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.notification.infrastructure.persistence.NotificationSchedulesTable
import com.crowdodge.shared.kernel.PersistedDataCorruption
import com.crowdodge.shared.kernel.UserUuid
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.time.Instant

class ExposedNotificationScheduleRepository : NotificationScheduleRepository {

    override suspend fun save(schedule: NotificationSchedule) {
        NotificationSchedulesTable.upsert(onUpdateExclude = listOf(NotificationSchedulesTable.createdAt)) {
            toModel(schedule)(it)
        }
    }

    override suspend fun saveAll(schedules: List<NotificationSchedule>) {
        if (schedules.isEmpty()) return
        NotificationSchedulesTable.batchUpsert(
            schedules,
            NotificationSchedulesTable.notificationScheduleUuid,
            onUpdateExclude = listOf(NotificationSchedulesTable.createdAt),
        ) { schedule ->
            toModel(schedule)(this)
        }
    }

    override suspend fun findPendingByEventUuid(eventUuid: EventUuid): List<NotificationSchedule> =
        NotificationSchedulesTable.selectAll()
            .where {
                (NotificationSchedulesTable.eventUuid eq eventUuid.value) and
                    (NotificationSchedulesTable.status eq NotificationStatus.Pending.value)
            }
            .map { toDomain(it) }
            .toList()

    override suspend fun deletePendingByEventUuid(eventUuid: EventUuid) {
        NotificationSchedulesTable.deleteWhere {
            (NotificationSchedulesTable.eventUuid eq eventUuid.value) and
                (NotificationSchedulesTable.status eq NotificationStatus.Pending.value)
        }
    }

    override suspend fun findDue(now: Instant): List<NotificationSchedule> =
        NotificationSchedulesTable.selectAll()
            .where {
                (NotificationSchedulesTable.status eq NotificationStatus.Pending.value) and
                    (NotificationSchedulesTable.notificateTime lessEq now)
            }
            .map { toDomain(it) }
            .toList()

    private fun toDomain(row: ResultRow): NotificationSchedule =
        NotificationSchedule.reconstitute(
            notificationScheduleUuid = NotificationScheduleUuid(
                row[NotificationSchedulesTable.notificationScheduleUuid],
            ),
            userUuid = UserUuid(row[NotificationSchedulesTable.userUuid]),
            eventUuid = EventUuid(row[NotificationSchedulesTable.eventUuid]),
            kind = NotificationKind.fromOrNull(row[NotificationSchedulesTable.kind])
                ?: throw PersistedDataCorruption(
                    "NotificationSchedule の復元に失敗しました: kind=${row[NotificationSchedulesTable.kind]}",
                ),
            notificateTime = row[NotificationSchedulesTable.notificateTime],
            status = NotificationStatus.fromOrNull(row[NotificationSchedulesTable.status])
                ?: throw PersistedDataCorruption(
                    "NotificationSchedule の復元に失敗しました: status=${row[NotificationSchedulesTable.status]}",
                ),
        )

    private fun toModel(schedule: NotificationSchedule): UpdateBuilder<*>.() -> Unit = {
        this[NotificationSchedulesTable.notificationScheduleUuid] = schedule.notificationScheduleUuid.value
        this[NotificationSchedulesTable.userUuid] = schedule.userUuid.value
        this[NotificationSchedulesTable.eventUuid] = schedule.eventUuid.value
        this[NotificationSchedulesTable.notificateTime] = schedule.notificateTime
        this[NotificationSchedulesTable.kind] = schedule.kind.value
        this[NotificationSchedulesTable.status] = schedule.status.value
    }
}
