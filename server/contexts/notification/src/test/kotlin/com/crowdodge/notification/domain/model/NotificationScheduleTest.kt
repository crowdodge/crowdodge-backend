package com.crowdodge.notification.domain.model

import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.shared.kernel.UserUuid
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NotificationScheduleTest : FunSpec({

    fun pending() = NotificationSchedule.schedule(
        userUuid = UserUuid(Uuid.random()),
        eventUuid = EventUuid(Uuid.random()),
        kind = NotificationKind.Reminder,
        notificateTime = Instant.parse("2026-08-01T00:00:00Z"),
    )

    test("schedule は Pending で採番し、渡した値を保持する") {
        val s = pending()
        s.status shouldBe NotificationStatus.Pending
        s.kind shouldBe NotificationKind.Reminder
        pending().notificationScheduleUuid shouldNotBe s.notificationScheduleUuid
    }

    test("Pending → Processing → Completed が正常遷移する") {
        val processing = pending().markProcessing().shouldBeRight()
        processing.status shouldBe NotificationStatus.Processing
        processing.complete().shouldBeRight().status shouldBe NotificationStatus.Completed
    }

    test("Processing → Failed が正常遷移する") {
        pending().markProcessing().shouldBeRight()
            .fail().shouldBeRight().status shouldBe NotificationStatus.Failed
    }

    test("cancel は Pending と Processing の両方から許可する") {
        pending().cancel().shouldBeRight().status shouldBe NotificationStatus.Canceled
        pending().markProcessing().shouldBeRight()
            .cancel().shouldBeRight().status shouldBe NotificationStatus.Canceled
    }

    test("Completed からの遷移は InvalidStatusTransition") {
        val completed = pending().markProcessing().shouldBeRight().complete().shouldBeRight()
        completed.cancel().shouldBeLeft() shouldBe NotificationError.TransitionError.InvalidStatusTransition(
            from = NotificationStatus.Completed,
            to = NotificationStatus.Canceled,
        )
        completed.markProcessing().shouldBeLeft()
        completed.fail().shouldBeLeft()
    }

    test("Pending から complete/fail は不可（Processing 経由必須）") {
        pending().complete().shouldBeLeft()
        pending().fail().shouldBeLeft()
    }

    test("kind/status の永続化文字列は確定仕様どおり") {
        NotificationKind.Reminder.value shouldBe "Reminder"
        NotificationKind.CongestionAlert.value shouldBe "CongestionAlert"
        NotificationStatus.Pending.value shouldBe "pending"
        NotificationStatus.Canceled.value shouldBe "canceled"
        NotificationKind.fromOrNull("Reminder") shouldBe NotificationKind.Reminder
        NotificationKind.fromOrNull("unknown") shouldBe null
        NotificationStatus.fromOrNull("processing") shouldBe NotificationStatus.Processing
        NotificationStatus.fromOrNull("unknown") shouldBe null
    }
})
