package com.crowdodge.notification.application.dispatch

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.notification.application.port.CongestionInfo
import com.crowdodge.notification.application.port.CongestionInfoResult
import com.crowdodge.notification.application.port.DispatchReadModelPort
import com.crowdodge.notification.application.port.EventDispatchSource
import com.crowdodge.notification.application.port.OutboundPushMessage
import com.crowdodge.notification.application.port.PushNotificationSender
import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.model.NotificationKind
import com.crowdodge.notification.domain.model.NotificationSchedule
import com.crowdodge.notification.domain.model.NotificationStatus
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Clock
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
    val saveAllBatches = mutableListOf<List<NotificationSchedule>>()

    override suspend fun save(schedule: NotificationSchedule) {
        stored[schedule.notificationScheduleUuid.value] = schedule
    }

    override suspend fun saveAll(schedules: List<NotificationSchedule>) {
        saveAllBatches += schedules
        schedules.forEach { save(it) }
    }

    override suspend fun findPendingByEventUuid(eventUuid: EventUuid): List<NotificationSchedule> =
        stored.values.filter { it.eventUuid == eventUuid && it.status == NotificationStatus.Pending }

    override suspend fun deletePendingByEventUuid(eventUuid: EventUuid) {
        stored.values.filter { it.eventUuid == eventUuid && it.status == NotificationStatus.Pending }
            .forEach { stored.remove(it.notificationScheduleUuid.value) }
    }

    override suspend fun findDue(now: Instant): List<NotificationSchedule> =
        stored.values.filter { it.status == NotificationStatus.Pending && it.notificateTime <= now }
}

/** Map を返すだけの DispatchReadModelPort。呼び出し回数も記録する。 */
private class FakeReadModel(
    private val sources: Map<EventUuid, EventDispatchSource> = emptyMap(),
    private val tokens: Map<UserUuid, List<String>> = emptyMap(),
) : DispatchReadModelPort {
    var eventSourcesCalls = 0
    var fcmTokensCalls = 0

    override suspend fun eventSources(eventUuids: List<EventUuid>): Map<EventUuid, EventDispatchSource> {
        eventSourcesCalls += 1
        return sources
    }

    override suspend fun fcmTokens(userUuids: List<UserUuid>): Map<UserUuid, List<String>> {
        fcmTokensCalls += 1
        return tokens
    }
}

private class FailingReadModel(
    private val failure: Throwable,
) : DispatchReadModelPort {
    override suspend fun eventSources(eventUuids: List<EventUuid>): Map<EventUuid, EventDispatchSource> = throw failure

    override suspend fun fcmTokens(userUuids: List<UserUuid>): Map<UserUuid, List<String>> = throw failure
}

/** 受け取ったメッセージを記録し、トークンごとの成否 Map に従って結果列を返す sender。 */
private class RecordingSender(
    private val results: Map<String, Boolean> = emptyMap(),
) : PushNotificationSender {
    val sent = mutableListOf<OutboundPushMessage>()
    var sendAllCalls = 0

    override suspend fun sendAll(
        messages: List<OutboundPushMessage>,
    ): List<Either<NotificationError.DispatchError, Unit>> {
        sendAllCalls += 1
        sent += messages
        return messages.map { message ->
            if (results.getOrDefault(message.fcmToken, true)) {
                Unit.right()
            } else {
                NotificationError.DispatchError.PushSendFailed.left()
            }
        }
    }
}

private fun source(
    eventUuid: EventUuid,
    start: Instant = Instant.parse("2026-07-08T10:00:00Z"),
    isAllDay: Boolean = false,
): EventDispatchSource = EventDispatchSource(
    eventUuid = eventUuid,
    title = "打合せ",
    start = start,
    isAllDay = isAllDay,
)

class DispatchDueNotificationsUseCaseTest : FunSpec({

    val eventUuid = EventUuid(Uuid.random())
    val userUuid = UserUuid(Uuid.random())

    fun due(
        kind: NotificationKind = NotificationKind.Reminder,
        notificateTime: Instant = NOW - 1.minutes,
    ) = NotificationSchedule.schedule(userUuid, eventUuid, kind, notificateTime)

    fun useCase(
        repo: NotificationScheduleRepository,
        readModel: DispatchReadModelPort,
        congestions: Map<EventUuid, CongestionInfo> = emptyMap(),
        congestionResults: Map<EventUuid, CongestionInfoResult> = congestions.mapValues { (_, info) ->
            CongestionInfoResult.Success(info)
        },
        sender: PushNotificationSender = RecordingSender(),
    ) = DispatchDueNotificationsUseCase(
        schedules = repo,
        readModel = readModel,
        congestions = { requestedEventUuids ->
            requestedEventUuids.associateWith { eventUuid ->
                congestionResults[eventUuid] ?: CongestionInfoResult.Success(null)
            }
        },
        sender = sender,
        transactions = ImmediateTransactionRunner,
        clock = FixedClock,
    )

    test("期限到来の Reminder を送信して completed にする") {
        val repo = InMemoryScheduleRepository()
        val schedule = due()
        repo.save(schedule)
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(repo, readModel, sender = sender).execute()

        result shouldBe DispatchResult(completed = 1, failed = 0, canceled = 0)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Completed
        sender.sent.map { it.fcmToken } shouldContainExactly listOf("token-1")
        sender.sent.single().notification.title shouldBe "打合せ"
        sender.sent.single().notification.body shouldContain "07/08 19:00"
    }

    test("同一eventUuidの期限到来通知は予定直前に最も近い1件だけ送信する") {
        val repo = InMemoryScheduleRepository()
        val oldAlert = due(NotificationKind.CongestionAlert, NOW - 7.days)
        val latestReminder = due(NotificationKind.Reminder, NOW - 1.minutes)
        repo.save(oldAlert)
        repo.save(latestReminder)
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(
            repo,
            readModel,
            congestions = mapOf(eventUuid to CongestionInfo("混雑中")),
            sender = sender,
        ).execute()

        result shouldBe DispatchResult(completed = 1, failed = 0, canceled = 1)
        repo.stored[oldAlert.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Canceled
        repo.stored[latestReminder.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Completed
        sender.sent shouldHaveSize 1
        readModel.eventSourcesCalls shouldBe 1
        readModel.fcmTokensCalls shouldBe 1
    }

    test("予定開始時刻を過ぎた期限到来通知は送信せず canceled にする") {
        val repo = InMemoryScheduleRepository()
        val schedule = due(NotificationKind.Reminder)
        repo.save(schedule)
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid, start = NOW - 1.minutes)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(
            repo,
            readModel,
            congestions = mapOf(eventUuid to CongestionInfo("混雑中")),
            sender = sender,
        ).execute()

        result shouldBe DispatchResult(completed = 0, failed = 0, canceled = 1)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Canceled
        sender.sent shouldBe emptyList()
        readModel.eventSourcesCalls shouldBe 1
        readModel.fcmTokensCalls shouldBe 0
    }

    test("期限未到来の pending は対象外") {
        val repo = InMemoryScheduleRepository()
        repo.save(NotificationSchedule.schedule(userUuid, eventUuid, NotificationKind.Reminder, NOW + 1.days))
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(repo, readModel, sender = sender).execute()

        result shouldBe DispatchResult(0, 0, 0)
        sender.sent.size shouldBe 0
        // 対象 0 件なら read model にも問い合わせない
        readModel.eventSourcesCalls shouldBe 0
        readModel.fcmTokensCalls shouldBe 0
    }

    test("CongestionAlert は混雑情報が null なら送信せず canceled") {
        val repo = InMemoryScheduleRepository()
        val schedule = due(NotificationKind.CongestionAlert)
        repo.save(schedule)
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(repo, readModel, sender = sender).execute()

        result shouldBe DispatchResult(completed = 0, failed = 0, canceled = 1)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Canceled
        sender.sent.size shouldBe 0
    }

    test("混雑情報の一時失敗は送信せず pending に戻して再試行する") {
        val repo = InMemoryScheduleRepository()
        val schedule = due(NotificationKind.CongestionAlert)
        repo.save(schedule)
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(
            repo = repo,
            readModel = readModel,
            congestionResults = mapOf(
                eventUuid to CongestionInfoResult.Failure(
                    NotificationError.CongestionInfoError.TemporarilyUnavailable,
                ),
            ),
            sender = sender,
        ).execute()

        result shouldBe DispatchResult(completed = 0, failed = 0, canceled = 0, retried = 1)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Pending
        sender.sent shouldBe emptyList()
    }

    test("Reminder は混雑情報の一時失敗でも混雑情報なしで送信する") {
        val repo = InMemoryScheduleRepository()
        val schedule = due(NotificationKind.Reminder)
        repo.save(schedule)
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(
            repo = repo,
            readModel = readModel,
            congestionResults = mapOf(
                eventUuid to CongestionInfoResult.Failure(
                    NotificationError.CongestionInfoError.TemporarilyUnavailable,
                ),
            ),
            sender = sender,
        ).execute()

        result shouldBe DispatchResult(completed = 1, failed = 0, canceled = 0)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Completed
        sender.sent.single().notification.body shouldBe "07/08 19:00 開始"
    }

    test("CongestionAlert は混雑情報の恒久失敗なら canceled にする") {
        val repo = InMemoryScheduleRepository()
        val schedule = due(NotificationKind.CongestionAlert)
        repo.save(schedule)

        val result = useCase(
            repo = repo,
            readModel = FakeReadModel(
                sources = mapOf(eventUuid to source(eventUuid)),
                tokens = mapOf(userUuid to listOf("token-1")),
            ),
            congestionResults = mapOf(
                eventUuid to CongestionInfoResult.Failure(
                    NotificationError.CongestionInfoError.PermanentlyUnavailable,
                ),
            ),
        ).execute()

        result shouldBe DispatchResult(completed = 0, failed = 0, canceled = 1)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Canceled
    }

    test("Reminder は混雑情報の恒久失敗でも混雑情報なしで送信する") {
        val repo = InMemoryScheduleRepository()
        repo.save(due(NotificationKind.Reminder))
        val sender = RecordingSender()

        val result = useCase(
            repo = repo,
            readModel = FakeReadModel(
                sources = mapOf(eventUuid to source(eventUuid)),
                tokens = mapOf(userUuid to listOf("token-1")),
            ),
            congestionResults = mapOf(
                eventUuid to CongestionInfoResult.Failure(
                    NotificationError.CongestionInfoError.PermanentlyUnavailable,
                ),
            ),
            sender = sender,
        ).execute()

        result shouldBe DispatchResult(completed = 1, failed = 0, canceled = 0)
        sender.sent.single().notification.body shouldBe "07/08 19:00 開始"
    }

    test("CongestionAlert は混雑情報があれば本文に含めて送信する") {
        val repo = InMemoryScheduleRepository()
        repo.save(due(NotificationKind.CongestionAlert))
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(
            repo,
            readModel,
            congestions = mapOf(eventUuid to CongestionInfo("周辺で混雑が予想されます")),
            sender = sender,
        ).execute()

        result shouldBe DispatchResult(completed = 1, failed = 0, canceled = 0)
        sender.sent.single().notification.body shouldContain "周辺で混雑が予想されます"
    }

    test("Reminder も混雑情報があれば本文に含めて送信する") {
        val repo = InMemoryScheduleRepository()
        repo.save(due(NotificationKind.Reminder))
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(
            repo,
            readModel,
            congestions = mapOf(eventUuid to CongestionInfo("会場周辺で混雑が予想されます")),
            sender = sender,
        ).execute()

        result shouldBe DispatchResult(completed = 1, failed = 0, canceled = 0)
        sender.sent.single().notification.body shouldContain "会場周辺で混雑が予想されます"
    }

    test("予定が取得できない行は canceled") {
        val repo = InMemoryScheduleRepository()
        val schedule = due()
        repo.save(schedule)
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = emptyMap(),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(repo, readModel, sender = sender).execute()

        result shouldBe DispatchResult(0, 0, 1)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Canceled
        sender.sent.size shouldBe 0
    }

    test("デバイストークンがなければ failed") {
        val repo = InMemoryScheduleRepository()
        val schedule = due()
        repo.save(schedule)
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = emptyMap(),
        )

        val result = useCase(repo, readModel).execute()

        result shouldBe DispatchResult(0, 1, 0)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Failed
    }

    test("複数トークンは 1 件以上成功で completed、全滅で failed") {
        val repo = InMemoryScheduleRepository()
        repo.save(due())
        useCase(
            repo,
            FakeReadModel(
                sources = mapOf(eventUuid to source(eventUuid)),
                tokens = mapOf(userUuid to listOf("ok", "ng")),
            ),
            sender = RecordingSender(results = mapOf("ok" to true, "ng" to false)),
        ).execute() shouldBe DispatchResult(1, 0, 0)

        val repo2 = InMemoryScheduleRepository()
        repo2.save(due())
        useCase(
            repo2,
            FakeReadModel(
                sources = mapOf(eventUuid to source(eventUuid)),
                tokens = mapOf(userUuid to listOf("ng1", "ng2")),
            ),
            sender = RecordingSender(results = mapOf("ng1" to false, "ng2" to false)),
        ).execute() shouldBe DispatchResult(0, 1, 0)
    }

    test("終日予定の本文は日付 + 終日表記") {
        val repo = InMemoryScheduleRepository()
        repo.save(due())
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(
                eventUuid to source(eventUuid, start = Instant.parse("2026-07-14T15:00:00Z"), isAllDay = true),
            ),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        useCase(repo, readModel, sender = sender).execute()

        sender.sent.single().notification.body shouldContain "07/15 終日"
    }

    test("同一 eventUuid のスケジュールが複数あっても読み取りは各 1 回") {
        val repo = InMemoryScheduleRepository()
        repo.save(due())
        repo.save(due(NotificationKind.CongestionAlert))
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )
        var congestionCalls = 0

        val result = DispatchDueNotificationsUseCase(
            schedules = repo,
            readModel = readModel,
            congestions = {
                congestionCalls += 1
                mapOf(eventUuid to CongestionInfoResult.Success(CongestionInfo("混雑中")))
            },
            sender = sender,
            transactions = ImmediateTransactionRunner,
            clock = FixedClock,
        ).execute()

        result shouldBe DispatchResult(completed = 1, failed = 0, canceled = 1)
        readModel.eventSourcesCalls shouldBe 1
        readModel.fcmTokensCalls shouldBe 1
        congestionCalls shouldBe 1
    }

    test("複数スケジュールでも送信は sendAll 1 回にまとめる") {
        val repo = InMemoryScheduleRepository()
        val otherUser = UserUuid(Uuid.random())
        val otherEvent = EventUuid(Uuid.random())
        repo.save(due())
        repo.save(NotificationSchedule.schedule(otherUser, otherEvent, NotificationKind.Reminder, NOW - 1.minutes))
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(
                eventUuid to source(eventUuid),
                otherEvent to source(otherEvent),
            ),
            tokens = mapOf(
                userUuid to listOf("token-1", "token-2"),
                otherUser to listOf("token-3"),
            ),
        )

        val result = useCase(repo, readModel, sender = sender).execute()

        result shouldBe DispatchResult(completed = 2, failed = 0, canceled = 0)
        sender.sendAllCalls shouldBe 1
        sender.sent.size shouldBe 3
    }

    test("複数スケジュールでも保存は claim と結果の saveAll 各 1 回") {
        val repo = InMemoryScheduleRepository()
        val schedule1 = due()
        val schedule2 = due(NotificationKind.CongestionAlert, NOW - 2.minutes)
        repo.save(schedule1)
        repo.save(schedule2)
        val readModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        useCase(repo, readModel).execute()

        // 1 回目は claim（processing 化）、2 回目が全 outcome の一括保存
        repo.saveAllBatches.size shouldBe 2
        repo.saveAllBatches[0].map { it.status }.toSet() shouldBe setOf(NotificationStatus.Processing)
        repo.saveAllBatches[1].map { it.notificationScheduleUuid.value }.toSet() shouldBe
            setOf(schedule1.notificationScheduleUuid.value, schedule2.notificationScheduleUuid.value)
        repo.saveAllBatches[1].map { it.status }.toSet() shouldBe
            setOf(NotificationStatus.Completed, NotificationStatus.Canceled)
    }

    test("completed / canceled / failed が混在する同一バッチを正しく確定する") {
        val repo = InMemoryScheduleRepository()
        val canceledEvent = EventUuid(Uuid.random())
        val failedEvent = EventUuid(Uuid.random())
        val noTokenUser = UserUuid(Uuid.random())
        val completedSchedule = due()
        val canceledSchedule =
            NotificationSchedule.schedule(userUuid, canceledEvent, NotificationKind.Reminder, NOW - 1.minutes)
        val failedSchedule =
            NotificationSchedule.schedule(noTokenUser, failedEvent, NotificationKind.Reminder, NOW - 1.minutes)
        repo.save(completedSchedule)
        repo.save(canceledSchedule)
        repo.save(failedSchedule)
        val sender = RecordingSender()
        val readModel = FakeReadModel(
            sources = mapOf(
                eventUuid to source(eventUuid),
                failedEvent to source(failedEvent),
            ),
            tokens = mapOf(userUuid to listOf("token-1")),
        )

        val result = useCase(repo, readModel, sender = sender).execute()

        result shouldBe DispatchResult(completed = 1, failed = 1, canceled = 1)
        repo.stored[completedSchedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Completed
        repo.stored[canceledSchedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Canceled
        repo.stored[failedSchedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Failed
        sender.sent.map { it.fcmToken } shouldContainExactly listOf("token-1")
    }

    test("claim後の全体例外では processing を pending に戻し、次回実行で再取得できる") {
        val repo = InMemoryScheduleRepository()
        val schedule = due()
        repo.save(schedule)
        val failure = IllegalStateException("read model unavailable")

        shouldThrow<IllegalStateException> {
            useCase(repo, FailingReadModel(failure)).execute()
        }.message shouldBe "read model unavailable"
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Pending
        repo.saveAllBatches.map { it.map(NotificationSchedule::status) } shouldBe listOf(
            listOf(NotificationStatus.Processing),
            listOf(NotificationStatus.Pending),
        )

        val retryReadModel = FakeReadModel(
            sources = mapOf(eventUuid to source(eventUuid)),
            tokens = mapOf(userUuid to listOf("token-1")),
        )
        useCase(repo, retryReadModel).execute() shouldBe DispatchResult(1, 0, 0)
        repo.stored[schedule.notificationScheduleUuid.value]?.status shouldBe NotificationStatus.Completed
    }
})
