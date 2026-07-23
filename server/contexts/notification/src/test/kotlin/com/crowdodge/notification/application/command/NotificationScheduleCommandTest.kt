package com.crowdodge.notification.application.command

import com.crowdodge.notification.application.port.EventRegistrationSource
import com.crowdodge.notification.application.port.RegistrationReadModelPort
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.model.NotificationKind
import com.crowdodge.notification.domain.model.NotificationSchedule
import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val NOW = Instant.parse("2026-07-08T00:00:00Z")

private object FixedClock : Clock {
    override fun now(): Instant = NOW
}

private object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class InMemoryScheduleRepository : NotificationScheduleRepository {
    val stored = mutableMapOf<Uuid, NotificationSchedule>()

    override suspend fun save(schedule: NotificationSchedule) {
        stored[schedule.notificationScheduleUuid.value] = schedule
    }

    override suspend fun saveAll(schedules: List<NotificationSchedule>) = schedules.forEach { save(it) }

    override suspend fun findPendingByEventUuid(eventUuid: EventUuid): List<NotificationSchedule> =
        stored.values.filter { it.eventUuid == eventUuid && it.status == NotificationStatus.Pending }

    override suspend fun deletePendingByEventUuid(eventUuid: EventUuid) {
        stored.values.filter { it.eventUuid == eventUuid && it.status == NotificationStatus.Pending }
            .forEach { stored.remove(it.notificationScheduleUuid.value) }
    }

    override suspend fun findDue(now: Instant): List<NotificationSchedule> =
        stored.values.filter { it.status == NotificationStatus.Pending && it.notificateTime <= now }
}

private fun source(
    eventUuid: EventUuid,
    userUuid: UserUuid = UserUuid(Uuid.random()),
    start: Instant = NOW + 60.days,
    remindTiming: Duration? = 30.minutes,
    defaultRemindTiming: Duration? = null,
): EventRegistrationSource = EventRegistrationSource(
    eventUuid = eventUuid,
    userUuid = userUuid,
    start = start,
    remindTiming = remindTiming,
    defaultRemindTiming = defaultRemindTiming,
)

/** 指定 source を 1 件だけ返す読み取りポート。null なら空 Map（予定なし）。 */
private fun readModel(source: EventRegistrationSource?): RegistrationReadModelPort =
    object : RegistrationReadModelPort {
        override suspend fun registrationSources(
            eventUuids: List<EventUuid>,
        ): Map<EventUuid, EventRegistrationSource> = source?.let { mapOf(it.eventUuid to it) } ?: emptyMap()
    }

class NotificationScheduleCommandTest : FunSpec({

    val eventUuid = EventUuid(Uuid.random())

    context("RegisterNotificationSchedulesUseCase") {
        test("Reminder 1 点 + CongestionAlert 3 点を pending 登録する") {
            val repo = InMemoryScheduleRepository()
            val src = source(eventUuid, start = NOW + 60.days, remindTiming = 30.minutes)
            val useCase = RegisterNotificationSchedulesUseCase(
                readModel = readModel(src),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            )

            useCase.execute(eventUuid).shouldBeRight()

            val reminders = repo.stored.values.filter { it.kind == NotificationKind.Reminder }
            val alerts = repo.stored.values.filter { it.kind == NotificationKind.CongestionAlert }
            reminders.map { it.notificateTime } shouldContainExactly listOf(src.start - 30.minutes)
            alerts.map { it.notificateTime }
                .shouldContainExactlyInAnyOrder(listOf(NOW, src.start - 30.days, src.start - 7.days))
            repo.stored.values.all { it.status == NotificationStatus.Pending } shouldBe true
            repo.stored.values.all { it.userUuid == src.userUuid } shouldBe true
        }

        test("event の remindTiming が null ならユーザー既定値で計算する") {
            val repo = InMemoryScheduleRepository()
            val src = source(eventUuid, remindTiming = null, defaultRemindTiming = 15.minutes)
            val useCase = RegisterNotificationSchedulesUseCase(
                readModel = readModel(src),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            )

            useCase.execute(eventUuid).shouldBeRight()

            repo.stored.values.filter { it.kind == NotificationKind.Reminder }
                .map { it.notificateTime } shouldContainExactly listOf(src.start - 15.minutes)
        }

        test("remindTiming が両方 null なら Reminder は登録せず CongestionAlert のみ") {
            val repo = InMemoryScheduleRepository()
            val useCase = RegisterNotificationSchedulesUseCase(
                readModel = readModel(source(eventUuid, remindTiming = null, defaultRemindTiming = null)),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            )

            useCase.execute(eventUuid).shouldBeRight()

            repo.stored.values.filter { it.kind == NotificationKind.Reminder }.shouldBeEmpty()
            repo.stored.values.filter { it.kind == NotificationKind.CongestionAlert }.size shouldBe 3
        }

        test("開始済みの予定は何も登録しない") {
            val repo = InMemoryScheduleRepository()
            val useCase = RegisterNotificationSchedulesUseCase(
                readModel = readModel(source(eventUuid, start = NOW - 1.minutes)),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            )

            useCase.execute(eventUuid).shouldBeRight()

            repo.stored.shouldBeEmpty()
        }

        test("再実行結果が空なら既存 pending を削除する") {
            val repo = InMemoryScheduleRepository()
            val pending = NotificationSchedule.schedule(
                userUuid = UserUuid(Uuid.random()),
                eventUuid = eventUuid,
                kind = NotificationKind.Reminder,
                notificateTime = NOW + 1.days,
            )
            repo.save(pending)
            val useCase = RegisterNotificationSchedulesUseCase(
                readModel = readModel(source(eventUuid, start = NOW - 1.minutes)),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            )

            useCase.execute(eventUuid).shouldBeRight()

            repo.stored.shouldBeEmpty()
        }

        test("予定が見つからなければ何もしない") {
            val repo = InMemoryScheduleRepository()
            val useCase = RegisterNotificationSchedulesUseCase(
                readModel = readModel(null),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            )

            useCase.execute(eventUuid).shouldBeRight()

            repo.stored.shouldBeEmpty()
        }

        test("再実行時は既存 pending を置き換える（重複しない）") {
            val repo = InMemoryScheduleRepository()
            val useCase = RegisterNotificationSchedulesUseCase(
                readModel = readModel(source(eventUuid)),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            )

            useCase.execute(eventUuid).shouldBeRight()
            useCase.execute(eventUuid).shouldBeRight()

            repo.stored.values.size shouldBe 4
        }
    }

    context("RescheduleNotificationUseCase") {
        test("予定時刻変更時はpendingを削除して即時分を含め再計算する") {
            val repo = InMemoryScheduleRepository()
            val src = source(eventUuid, start = NOW + 60.days, remindTiming = 30.minutes)
            RegisterNotificationSchedulesUseCase(
                readModel = readModel(src),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            ).execute(eventUuid).shouldBeRight()

            val moved = source(eventUuid, userUuid = src.userUuid, start = NOW + 90.days, remindTiming = 30.minutes)
            RescheduleNotificationUseCase(
                readModel = readModel(moved),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            ).execute(eventUuid, includeImmediate = true).shouldBeRight()

            val times = repo.stored.values.map { it.notificateTime }
            times shouldContainExactlyInAnyOrder listOf(
                moved.start - 30.minutes,
                NOW,
                moved.start - 30.days,
                moved.start - 7.days,
            )
        }

        test("リマインド時刻だけの変更は既存の即時CongestionAlertを保持する") {
            val repo = InMemoryScheduleRepository()
            val src = source(eventUuid, start = NOW + 60.days, remindTiming = 30.minutes)
            RegisterNotificationSchedulesUseCase(
                readModel = readModel(src),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            ).execute(eventUuid).shouldBeRight()

            val changedReminder = source(
                eventUuid,
                userUuid = src.userUuid,
                start = src.start,
                remindTiming = 15.minutes,
            )
            RescheduleNotificationUseCase(
                readModel = readModel(changedReminder),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            ).execute(eventUuid, includeImmediate = false).shouldBeRight()

            repo.stored.values.map { it.notificateTime } shouldContainExactlyInAnyOrder listOf(
                NOW,
                changedReminder.start - 15.minutes,
                changedReminder.start - 30.days,
                changedReminder.start - 7.days,
            )
        }

        test("確定済み（completed）は再計算で変更しない") {
            val repo = InMemoryScheduleRepository()
            val done = NotificationSchedule.schedule(
                userUuid = UserUuid(Uuid.random()),
                eventUuid = eventUuid,
                kind = NotificationKind.CongestionAlert,
                notificateTime = NOW,
            ).markProcessing().shouldBeRight().complete().shouldBeRight()
            repo.save(done)

            RescheduleNotificationUseCase(
                readModel = readModel(source(eventUuid)),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            ).execute(eventUuid, includeImmediate = false).shouldBeRight()

            repo.stored[done.notificationScheduleUuid.value] shouldBe done
        }

        test("予定が消えていたら pending を canceled にする") {
            val repo = InMemoryScheduleRepository()
            val pending = NotificationSchedule.schedule(
                userUuid = UserUuid(Uuid.random()),
                eventUuid = eventUuid,
                kind = NotificationKind.Reminder,
                notificateTime = NOW + 1.days,
            )
            repo.save(pending)

            RescheduleNotificationUseCase(
                readModel = readModel(null),
                schedules = repo,
                transactions = ImmediateTransactionRunner,
                clock = FixedClock,
            ).execute(eventUuid, includeImmediate = false).shouldBeRight()

            repo.stored[pending.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Canceled
        }
    }

    context("CancelNotificationUseCase") {
        test("pending を全 kind canceled にし、確定済みは触らない") {
            val repo = InMemoryScheduleRepository()
            val userUuid = UserUuid(Uuid.random())
            val pending = NotificationSchedule.schedule(userUuid, eventUuid, NotificationKind.Reminder, NOW + 1.days)
            val done = NotificationSchedule.schedule(userUuid, eventUuid, NotificationKind.CongestionAlert, NOW)
                .markProcessing().shouldBeRight().complete().shouldBeRight()
            repo.save(pending)
            repo.save(done)

            CancelNotificationUseCase(
                schedules = repo,
                transactions = ImmediateTransactionRunner,
            ).execute(eventUuid).shouldBeRight()

            repo.stored[pending.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Canceled
            repo.stored[done.notificationScheduleUuid.value] shouldBe done
        }
    }
})
