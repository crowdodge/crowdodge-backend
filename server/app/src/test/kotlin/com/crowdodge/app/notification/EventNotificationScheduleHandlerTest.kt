package com.crowdodge.app.notification

import com.crowdodge.event.domain.event.EventCancelled
import com.crowdodge.event.domain.event.EventNotificationTimingChanged
import com.crowdodge.event.domain.event.EventRescheduled
import com.crowdodge.event.domain.event.EventScheduled
import com.crowdodge.event.domain.event.NotificationTimingChangeReason
import com.crowdodge.notification.application.command.CancelNotificationUseCase
import com.crowdodge.notification.application.command.RegisterNotificationSchedulesUseCase
import com.crowdodge.notification.application.command.RescheduleNotificationUseCase
import com.crowdodge.notification.application.port.EventRegistrationSource
import com.crowdodge.notification.application.port.RegistrationReadModelPort
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.model.NotificationKind
import com.crowdodge.notification.domain.model.NotificationSchedule
import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.crowdodge.event.domain.model.EventUuid as EventBcEventUuid
import com.crowdodge.notification.domain.model.EventUuid as NotificationEventUuid

private object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class RecordingScheduleRepository : NotificationScheduleRepository {
    val deletedFor = mutableListOf<NotificationEventUuid>()
    val saved = mutableListOf<NotificationSchedule>()
    val saveAllBatches = mutableListOf<List<NotificationSchedule>>()
    val pendingQueried = mutableListOf<NotificationEventUuid>()
    var pendingSchedulesByEvent = emptyMap<NotificationEventUuid, List<NotificationSchedule>>()

    override suspend fun save(schedule: NotificationSchedule) {
        saved += schedule
    }

    override suspend fun saveAll(schedules: List<NotificationSchedule>) {
        saveAllBatches += listOf(schedules)
    }

    override suspend fun findPendingByEventUuid(eventUuid: NotificationEventUuid): List<NotificationSchedule> {
        pendingQueried += eventUuid
        return pendingSchedulesByEvent[eventUuid].orEmpty()
    }

    override suspend fun deletePendingByEventUuid(eventUuid: NotificationEventUuid) {
        deletedFor += eventUuid
    }

    override suspend fun findDue(now: Instant): List<NotificationSchedule> = emptyList()
}

class EventNotificationScheduleHandlerTest : FunSpec({

    val occurredAt = Instant.parse("2026-07-08T00:00:00Z")
    val eventUuid = EventBcEventUuid(Uuid.random())
    val notificationEventUuid = NotificationEventUuid(eventUuid.value)
    val source = EventRegistrationSource(
        eventUuid = notificationEventUuid,
        userUuid = UserUuid(Uuid.random()),
        start = occurredAt + 60.days,
        remindTiming = 30.minutes,
        defaultRemindTiming = null,
    )

    fun handler(
        repo: RecordingScheduleRepository,
        eventInfo: EventRegistrationSource? = null,
    ): EventNotificationScheduleHandler {
        val readModel = object : RegistrationReadModelPort {
            override suspend fun registrationSources(
                eventUuids: List<EventUuid>,
            ): Map<EventUuid, EventRegistrationSource> = eventInfo?.let { mapOf(it.eventUuid to it) } ?: emptyMap()
        }
        val clock = object : Clock {
            override fun now(): Instant = occurredAt
        }
        return EventNotificationScheduleHandler(
            register = RegisterNotificationSchedulesUseCase(readModel, repo, ImmediateTransactionRunner, clock),
            reschedule = RescheduleNotificationUseCase(readModel, repo, ImmediateTransactionRunner, clock),
            cancel = CancelNotificationUseCase(repo, ImmediateTransactionRunner),
        )
    }

    test("supports は 3 イベントのみ true（EventRescheduled は購読しない）") {
        val h = handler(RecordingScheduleRepository())
        h.supports(EventScheduled(eventUuid, occurredAt)) shouldBe true
        h.supports(
            EventNotificationTimingChanged(
                eventUuid,
                NotificationTimingChangeReason.RemindTimingChanged,
                occurredAt,
            ),
        ) shouldBe true
        h.supports(EventCancelled(eventUuid, occurredAt)) shouldBe true
        h.supports(EventRescheduled(eventUuid, occurredAt)) shouldBe false
    }

    test("EventScheduled は register に振り分けて immediate を含む 4 件を saveAll する") {
        val repo = RecordingScheduleRepository()
        handler(repo, eventInfo = source).handle(EventScheduled(eventUuid, occurredAt))

        repo.deletedFor shouldBe listOf(notificationEventUuid)
        repo.pendingQueried shouldBe emptyList()
        repo.saved shouldBe emptyList()
        repo.saveAllBatches.size shouldBe 1
        repo.saveAllBatches.single().size shouldBe 4
        repo.saveAllBatches.single().map { it.kind }.sortedBy { it.name } shouldContainExactly listOf(
            NotificationKind.CongestionAlert,
            NotificationKind.CongestionAlert,
            NotificationKind.CongestionAlert,
            NotificationKind.Reminder,
        )
    }

    test("リマインド時刻だけの変更は即時分なしで再スケジュールする") {
        val repo = RecordingScheduleRepository()
        handler(repo, eventInfo = source).handle(
            EventNotificationTimingChanged(
                eventUuid,
                NotificationTimingChangeReason.RemindTimingChanged,
                occurredAt,
            ),
        )

        repo.deletedFor shouldBe listOf(notificationEventUuid)
        repo.pendingQueried shouldBe listOf(notificationEventUuid)
        repo.saved shouldBe emptyList()
        repo.saveAllBatches.size shouldBe 1
        repo.saveAllBatches.single().size shouldBe 3
        repo.saveAllBatches.single().map { it.kind }.sortedBy { it.name } shouldContainExactly listOf(
            NotificationKind.CongestionAlert,
            NotificationKind.CongestionAlert,
            NotificationKind.Reminder,
        )
    }

    test("予定時刻の変更は即時CongestionAlertを含めて再スケジュールする") {
        val repo = RecordingScheduleRepository()
        handler(repo, eventInfo = source).handle(
            EventNotificationTimingChanged(
                eventUuid,
                NotificationTimingChangeReason.ScheduleChanged,
                occurredAt,
            ),
        )

        repo.saveAllBatches.single().size shouldBe 4
        repo.saveAllBatches.single().count { it.kind == NotificationKind.CongestionAlert } shouldBe 3
    }

    test("EventCancelled は pending を canceled にして save する") {
        val repo = RecordingScheduleRepository()
        val pending = NotificationSchedule.schedule(
            userUuid = source.userUuid,
            eventUuid = notificationEventUuid,
            kind = NotificationKind.Reminder,
            notificateTime = occurredAt + 1.days,
        )
        repo.pendingSchedulesByEvent = mapOf(notificationEventUuid to listOf(pending))

        handler(repo, eventInfo = source).handle(EventCancelled(eventUuid, occurredAt))

        repo.pendingQueried shouldBe listOf(notificationEventUuid)
        repo.deletedFor shouldBe emptyList()
        repo.saveAllBatches shouldBe emptyList()
        repo.saved.size shouldBe 1
        repo.saved.single().notificationScheduleUuid shouldBe pending.notificationScheduleUuid
        repo.saved.single().status shouldBe NotificationStatus.Canceled
    }
})
