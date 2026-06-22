package com.crowdodge.event.domain.model

import arrow.core.raise.either
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.GoogleEventId.Companion.googleEventId
import com.crowdodge.event.domain.model.RecurringEventId.Companion.recurringEventId
import com.crowdodge.event.domain.model.RemindTiming.Companion.remindTiming
import com.crowdodge.event.domain.model.Schedule.Companion.eventSchedule
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * event BC ドメインの仕様を検証する（
 * 「予定（カレンダーイベント）」「不透明トークン」「リマインドタイミング」が
 * 一般に満たすべきセマンティクスから期待値を立てる）。
 *
 * Schedule の不変条件は次の一般概念に基づく:
 * - 予定は必ず開始/終了を持つ（最低 1 ペア必須）
 * - 時刻指定（datetime ペア）か終日（date ペア）の **いずれか一方**（XOR）
 * - 各ペアは「両方あり / 両方なし」のみ（片方だけは形式不正）
 * - 時刻指定は start < end、終日は startDate <= endDate（終了日排他のため同日可、逆転は不可）
 */
class EventTest : FunSpec({

    val t1 = Instant.fromEpochSeconds(1_000L)
    val t2 = Instant.fromEpochSeconds(2_000L)
    val d1 = LocalDate(2026, 1, 1)
    val d2 = LocalDate(2026, 1, 2)

    context("不透明トークン（trim + 非空）") {
        test("GoogleEventId は前後空白を trim する") {
            either { googleEventId("  eid  ") }.shouldBeRight().value shouldBe "eid"
        }
        test("GoogleEventId が空・空白のみは BlankGoogleEventId") {
            either { googleEventId("") }.shouldBeLeft() shouldBe EventError.ValidationError.BlankGoogleEventId
            either { googleEventId("   ") }.shouldBeLeft() shouldBe EventError.ValidationError.BlankGoogleEventId
        }
        test("RecurringEventId は前後空白を trim する") {
            either { recurringEventId("  rid  ") }.shouldBeRight().value shouldBe "rid"
        }
        test("RecurringEventId が空・空白のみは BlankRecurringEventId") {
            either { recurringEventId("") }.shouldBeLeft() shouldBe EventError.ValidationError.BlankRecurringEventId
            either { recurringEventId("   ") }.shouldBeLeft() shouldBe EventError.ValidationError.BlankRecurringEventId
        }
    }

    context("RemindTiming（正の値のみ）") {
        test("0 と負値は InvalidRemindTiming") {
            either { remindTiming(ZERO) }.shouldBeLeft() shouldBe EventError.ValidationError.InvalidRemindTiming
            either { remindTiming(-(1.minutes)) }.shouldBeLeft() shouldBe EventError.ValidationError.InvalidRemindTiming
        }
        test("正の値は成功し値を保持する") {
            either { remindTiming(10.minutes) }.shouldBeRight().duration shouldBe 10.minutes
        }
    }

    context("Schedule（時刻指定 XOR 終日、各ペアは両方そろう、開始 < / <= 終了）") {

        test("start/end が 4 つとも null は拒否される") {
            either { eventSchedule() }.shouldBeLeft(EventError.ValidationError.BlankSchedule)
        }

        context("不要な値は存在できない") {
            test("時刻ペアの片方だけ（startTime のみ）は InvalidScheduleFormat") {
                either { eventSchedule(startTime = t1) }.shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
            test("時刻ペアの片方だけ（endTime のみ）は InvalidScheduleFormat") {
                either { eventSchedule(endTime = t2) }.shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
            test("日付ペアの片方だけ（startDate のみ）は InvalidScheduleFormat") {
                either { eventSchedule(startDate = d1) }.shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
            test("日付ペアの片方だけ（endDate のみ）は InvalidScheduleFormat") {
                either { eventSchedule(endDate = d2) }.shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
            test("日付ペア + startDate は拒否される") {
                either { eventSchedule(startTime = t1, endTime = t2, startDate = d1) }
                    .shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
            test("日付ペア + endDate は拒否される") {
                either { eventSchedule(startTime = t1, endTime = t2, endDate = d2) }
                    .shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
            test("時刻ペア + startTime は拒否される") {
                either { eventSchedule(startTime = t1, startDate = d1, endDate = d2) }
                    .shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
            test("時刻ペア + endTime は拒否される") {
                either { eventSchedule(endTime = t2, startDate = d1, endDate = d2) }
                    .shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
            test("時刻 XOR 終日: 時刻ペアと日付ペアの併存は拒否される") {
                either { eventSchedule(startTime = t1, endTime = t2, startDate = d1, endDate = d2) }
                    .shouldBeLeft(EventError.ValidationError.InvalidScheduleFormat)
            }
        }

        context("時刻ペア") {
            test("時刻指定: start == end は範囲をなさず InvalidScheduleRange") {
                either { eventSchedule(startTime = t1, endTime = t1) }
                    .shouldBeLeft(EventError.ValidationError.InvalidScheduleRange)
            }
            test("時刻指定: start > end は InvalidScheduleRange") {
                either { eventSchedule(startTime = t2, endTime = t1) }
                    .shouldBeLeft(EventError.ValidationError.InvalidScheduleRange)
            }
            test("時刻指定: start < end は成功する") {
                either { eventSchedule(startTime = t1, endTime = t2) }.shouldBeRight()
            }
        }

        context("日付ペア") {
            test("終日: startDate > endDate（逆転）は拒否される") {
                either { eventSchedule(startDate = d2, endDate = d1) }
                    .shouldBeLeft(EventError.ValidationError.InvalidScheduleRange)
            }
            test("終日: startDate < endDate は成功する") {
                either { eventSchedule(startDate = d1, endDate = d2) }.shouldBeRight()
            }
            test("終日: startDate == endDate（同日）は成功する（終了日は排他のため）") {
                either { eventSchedule(startDate = d1, endDate = d1) }.shouldBeRight()
            }
        }
    }

    // --- ビルダ（成功が自明な VO はここで組み立てる） ---
    fun eid(value: String = "eid"): GoogleEventId = either { googleEventId(value) }.getOrNull()!!
    fun rid(value: String = "rid"): RecurringEventId = either { recurringEventId(value) }.getOrNull()!!
    fun rt(d: Duration = 10.minutes): RemindTiming = either { remindTiming(d) }.getOrNull()!!
    fun cal(): UserCalendarUuid = UserCalendarUuid(Uuid.random())

    fun timed(): Schedule = either { eventSchedule(startTime = t1, endTime = t2) }.getOrNull()!!
    fun content(
        schedule: Schedule = timed(),
        remind: RemindTiming? = rt(),
    ): EventContent = EventContent(
        title = "title",
        description = "description",
        location = "location",
        schedule = schedule,
        remindTiming = remind,
    )

    context("Event 集約") {
        test("schedule は呼ぶたびに異なる EventUuid を採番する") {
            val c = cal()
            val e1 = Event.schedule(c, eid(), rid(), content())
            val e2 = Event.schedule(c, eid(), rid(), content())
            e1.eventUuid shouldNotBe e2.eventUuid
        }
        test("schedule は渡したフィールドを保持する") {
            val c = cal()
            val g = eid("g-1")
            val r = rid("r-1")
            val ct = content()
            val e = Event.schedule(c, g, r, ct)
            e.userCalendarUuid shouldBe c
            e.googleEventId shouldBe g
            e.recurringEventId shouldBe r
            e.eventContent shouldBe ct
        }
        test("schedule は recurringEventId 不在（単発予定）を許容する") {
            val e = Event.schedule(cal(), eid(), null, content())
            e.recurringEventId shouldBe null
        }
        test("reconstitute は渡した EventUuid を保持する（採番しない）") {
            val eventUuid = EventUuid.new()
            val e = Event.reconstitute(eventUuid, cal(), eid(), rid(), content())
            e.eventUuid shouldBe eventUuid
        }

        test("reschedule は schedule だけ差し替え、他フィールドと識別子を保つ") {
            val original = Event.schedule(cal(), eid(), rid(), content())
            val newSchedule = either { eventSchedule(startDate = d1, endDate = d2) }.getOrNull()!!
            val updated = original.reschedule(newSchedule)

            updated.eventContent.schedule shouldBe newSchedule
            updated.eventUuid shouldBe original.eventUuid
            updated.googleEventId shouldBe original.googleEventId
            updated.eventContent.remindTiming shouldBe original.eventContent.remindTiming
            // 元インスタンスは不変
            original.eventContent.schedule shouldBe timed()
        }

        test("changeRemindTiming は remindTiming だけ差し替える") {
            val original = Event.schedule(cal(), eid(), rid(), content(remind = rt(10.minutes)))
            val newTiming = rt(30.minutes)
            val updated = original.changeRemindTiming(newTiming)

            updated.eventContent.remindTiming shouldBe newTiming
            updated.eventContent.schedule shouldBe original.eventContent.schedule
            updated.eventUuid shouldBe original.eventUuid
        }
        test("changeRemindTiming は null（既定値参照）への変更を許容する") {
            val original = Event.schedule(cal(), eid(), rid(), content(remind = rt(10.minutes)))
            original.changeRemindTiming(null).eventContent.remindTiming shouldBe null
        }
    }
})
