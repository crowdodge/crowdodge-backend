package com.crowdodge.notification.application.command

import arrow.core.Either
import arrow.core.raise.either
import com.crowdodge.notification.application.port.EventRegistrationSource
import com.crowdodge.notification.application.port.RegistrationReadModelPort
import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.model.NotificationKind
import com.crowdodge.notification.domain.model.NotificationSchedule
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.notification.domain.service.NotificationTimingPolicy
import com.crowdodge.shared.kernel.TransactionRunner
import kotlin.time.Clock
import kotlin.time.Instant

/** EventScheduled 起点。Reminder 1 点 + CongestionAlert 最大 3 点（作成直後を含む）を登録する。 */
class RegisterNotificationSchedulesUseCase(
    private val readModel: RegistrationReadModelPort,
    private val schedules: NotificationScheduleRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend fun execute(eventUuid: EventUuid): Either<NotificationError, Unit> =
        either {
            val source = readModel.registrationSources(listOf(eventUuid))[eventUuid] ?: return@either
            val now = clock.now()
            val toRegister = buildSchedules(source, now, includeImmediate = true)

            transactions.inTransaction {
                schedules.deletePendingByEventUuid(eventUuid)
                schedules.saveAll(toRegister)
            }
        }
}

/** Register / Reschedule 共通の登録対象組み立て。予定個別の remindTiming が無ければユーザー既定値で補う。 */
internal fun buildSchedules(
    source: EventRegistrationSource,
    now: Instant,
    includeImmediate: Boolean,
): List<NotificationSchedule> = buildList {
    val remindTiming = source.remindTiming ?: source.defaultRemindTiming
    if (remindTiming != null) {
        NotificationTimingPolicy.reminderTime(source.start, remindTiming, now)?.let { time ->
            add(NotificationSchedule.schedule(source.userUuid, source.eventUuid, NotificationKind.Reminder, time))
        }
    }
    NotificationTimingPolicy.congestionAlertTimes(source.start, now, includeImmediate).forEach { time ->
        add(NotificationSchedule.schedule(source.userUuid, source.eventUuid, NotificationKind.CongestionAlert, time))
    }
}
