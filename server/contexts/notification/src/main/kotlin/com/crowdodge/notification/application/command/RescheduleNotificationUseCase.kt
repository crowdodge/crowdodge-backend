package com.crowdodge.notification.application.command

import arrow.core.Either
import arrow.core.raise.either
import com.crowdodge.notification.application.port.RegistrationReadModelPort
import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.shared.kernel.TransactionRunner
import kotlin.time.Clock

/**
 * 通知時刻に影響する予定変更を起点に、pending を削除して再計算・再登録する。
 * 予定時刻の変更時は作成直後の CongestionAlert も再生成する。
 * 予定が取得できない場合は pending を canceled にする。
 */
class RescheduleNotificationUseCase(
    private val readModel: RegistrationReadModelPort,
    private val schedules: NotificationScheduleRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend fun execute(eventUuid: EventUuid, includeImmediate: Boolean): Either<NotificationError, Unit> =
        either {
            val source = readModel.registrationSources(listOf(eventUuid))[eventUuid]
            if (source == null) {
                cancelPending(eventUuid)
                return@either
            }
            val now = clock.now()
            val toRegister = buildSchedules(source, now, includeImmediate)

            transactions.inTransaction {
                val retained = if (includeImmediate) {
                    emptyList()
                } else {
                    schedules.findPendingByEventUuid(eventUuid).filter { schedule ->
                        schedule.kind == com.crowdodge.notification.domain.model.NotificationKind.CongestionAlert &&
                            schedule.notificateTime <= now
                    }
                }
                schedules.deletePendingByEventUuid(eventUuid)
                schedules.saveAll(retained + toRegister)
            }
        }

    private suspend fun cancelPending(eventUuid: EventUuid) {
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
