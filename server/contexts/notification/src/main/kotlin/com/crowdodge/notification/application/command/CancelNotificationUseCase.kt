package com.crowdodge.notification.application.command

import arrow.core.Either
import arrow.core.raise.either
import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.shared.kernel.TransactionRunner

/** EventCancelled 起点。当該予定の pending（全 kind）を canceled にする。 */
class CancelNotificationUseCase(
    private val schedules: NotificationScheduleRepository,
    private val transactions: TransactionRunner,
) {
    suspend fun execute(eventUuid: EventUuid): Either<NotificationError, Unit> =
        either {
            transactions.inTransaction {
                schedules.findPendingByEventUuid(eventUuid).forEach { schedule ->
                    when (val canceled = schedule.cancel()) {
                        is Either.Right -> schedules.save(canceled.value)
                        is Either.Left -> Unit
                    }
                }
            }
        }
}
