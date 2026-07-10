package com.crowdodge.app.notification

import com.crowdodge.event.domain.event.EventCancelled
import com.crowdodge.event.domain.event.EventNotificationTimingChanged
import com.crowdodge.event.domain.event.EventScheduled
import com.crowdodge.event.domain.event.NotificationTimingChangeReason
import com.crowdodge.notification.application.command.CancelNotificationUseCase
import com.crowdodge.notification.application.command.RegisterNotificationSchedulesUseCase
import com.crowdodge.notification.application.command.RescheduleNotificationUseCase
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventHandler
import org.slf4j.LoggerFactory
import com.crowdodge.notification.domain.model.EventUuid as NotificationEventUuid

/**
 * event BC のドメインイベントを購読し通知スケジュールを維持する。
 * EventRescheduled は購読しない。通知に必要な変更理由は EventNotificationTimingChanged が担う。
 */
class EventNotificationScheduleHandler(
    private val register: RegisterNotificationSchedulesUseCase,
    private val reschedule: RescheduleNotificationUseCase,
    private val cancel: CancelNotificationUseCase,
) : DomainEventHandler {
    override fun supports(event: DomainEvent): Boolean =
        event is EventScheduled || event is EventNotificationTimingChanged || event is EventCancelled

    override suspend fun handle(event: DomainEvent) {
        when (event) {
            is EventScheduled ->
                register.execute(NotificationEventUuid(event.eventUuid.value))
                    .onLeft { logger.warn("通知スケジュール登録に失敗: {}", it.code) }

            is EventNotificationTimingChanged ->
                reschedule.execute(
                    eventUuid = NotificationEventUuid(event.eventUuid.value),
                    includeImmediate = event.reason != NotificationTimingChangeReason.RemindTimingChanged,
                )
                    .onLeft { logger.warn("通知スケジュール再計算に失敗: {}", it.code) }

            is EventCancelled ->
                cancel.execute(NotificationEventUuid(event.eventUuid.value))
                    .onLeft { logger.warn("通知スケジュール取消に失敗: {}", it.code) }

            else -> Unit
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(EventNotificationScheduleHandler::class.java)
    }
}
