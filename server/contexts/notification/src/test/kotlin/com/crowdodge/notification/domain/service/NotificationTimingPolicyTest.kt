package com.crowdodge.notification.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NotificationTimingPolicyTest : FunSpec({

    val now = Instant.parse("2026-07-08T00:00:00Z")

    context("reminderTime") {
        test("開始が未来なら 開始 − remindTiming を返す") {
            val start = Instant.parse("2026-07-08T10:00:00Z")
            NotificationTimingPolicy.reminderTime(start, 30.minutes, now) shouldBe
                Instant.parse("2026-07-08T09:30:00Z")
        }
        test("通知時刻が過去でも開始が未来なら返す（次回 Job が即送信）") {
            val start = now + 10.minutes
            NotificationTimingPolicy.reminderTime(start, 30.minutes, now) shouldBe start - 30.minutes
        }
        test("開始済みの予定は null（リマインド不要）") {
            NotificationTimingPolicy.reminderTime(now - 1.minutes, 30.minutes, now) shouldBe null
            NotificationTimingPolicy.reminderTime(now, 30.minutes, now) shouldBe null
        }
    }

    context("congestionAlertTimes") {
        test("十分先の予定は immediate + 30日前 + 7日前 の 3 点") {
            val start = now + 60.days
            NotificationTimingPolicy.congestionAlertTimes(start, now, includeImmediate = true)
                .shouldContainExactly(now, start - 30.days, start - 7.days)
        }
        test("30日前が過去なら immediate + 7日前 の 2 点") {
            val start = now + 10.days
            NotificationTimingPolicy.congestionAlertTimes(start, now, includeImmediate = true)
                .shouldContainExactly(now, start - 7.days)
        }
        test("7日前も過去なら immediate のみ") {
            val start = now + 3.days
            NotificationTimingPolicy.congestionAlertTimes(start, now, includeImmediate = true)
                .shouldContainExactly(now)
        }
        test("includeImmediate=false（再計算時）は immediate を含めない") {
            val start = now + 60.days
            NotificationTimingPolicy.congestionAlertTimes(start, now, includeImmediate = false)
                .shouldContainExactly(start - 30.days, start - 7.days)
        }
        test("開始済みの予定は空") {
            NotificationTimingPolicy.congestionAlertTimes(now - 1.minutes, now, includeImmediate = true)
                .shouldContainExactly()
        }
    }
})
