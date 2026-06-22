package com.crowdodge.event.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.shared.kernel.EntityUuid
import com.crowdodge.shared.kernel.TimeRange
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * 由来カレンダー識別子（events.user_calendar_uuid / event_calendar_syncs.user_calendar_uuid）。
 *
 * 参照先: user BC の `user_calendars.user_calendar_uuid`（§7 コンテキストマップ）。
 * BC 間のモジュール直接依存は禁止のため、event BC は外部識別子を自前の VO として再定義し、
 * 値（Uuid）のみで参照する（ACL）。採番は user BC が行うため `new()` は提供しない。
 */
@JvmInline
value class UserCalendarUuid(override val value: Uuid) : EntityUuid

/** 予定（投影）識別子（events.event_uuid）。サーバ側で採番する自社 ID。 */
@JvmInline
value class EventUuid(override val value: Uuid) : EntityUuid {
    companion object {
        fun new(): EventUuid = EventUuid(Uuid.random())
    }
}

/**
 * Google イベント（インスタンス）ID VO（events.google_event_id）。外部発行の不透明トークンで突合キー。
 * 前後空白は trim し、空は許さない。生成は [googleEventId] のみ（private constructor で施錠）。
 */
@JvmInline
value class GoogleEventId private constructor(val value: String) {
    companion object {
        fun Raise<EventError.ValidationError>.googleEventId(value: String): GoogleEventId {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { EventError.ValidationError.BlankGoogleEventId }
            return GoogleEventId(trimmed)
        }
    }
}

/**
 * Google シリーズ（マスタ）ID VO（events.recurring_event_id）。繰り返し予定の発生回を相関づける。
 * 単発予定では列が null のため、本 VO は「値が存在する場合」のみ生成する。空文字は許さない。
 */
@JvmInline
value class RecurringEventId private constructor(val value: String) {
    companion object {
        fun Raise<EventError.ValidationError>.recurringEventId(value: String): RecurringEventId {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { EventError.ValidationError.BlankRecurringEventId }
            return RecurringEventId(trimmed)
        }
    }
}

/**
 * カレンダーの予定の日時 VO。
 * 時刻指定（datetime ペア）か終日（date ペア）のいずれか一方で予定を管理する。
 * 各ペアは両方そろう必要があり、時刻指定は start < end、終日は startDate <= endDate（終了日は排他）。
 */
@ConsistentCopyVisibility
data class Schedule private constructor(
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
) {
    companion object {
        fun Raise<EventError.ValidationError>.eventSchedule(
            startTime: Instant? = null,
            endTime: Instant? = null,
            startDate: LocalDate? = null,
            endDate: LocalDate? = null
        ): Schedule {
            val isBlank = startTime == null && endTime == null && startDate == null && endDate == null
            val isTimeSet = startTime != null && endTime != null && startDate == null && endDate == null
            val isDateSet = startTime == null && endTime == null && startDate != null && endDate != null

            // なんらかの値がセットされている
            ensure(!isBlank) { EventError.ValidationError.BlankSchedule }

            // 時刻ペアのみ or 日付ペアのみ
            ensure(isTimeSet || isDateSet) { EventError.ValidationError.InvalidScheduleFormat }

            // 時刻指定は start < end
            if (isTimeSet) {
                ensure(startTime < endTime) { EventError.ValidationError.InvalidScheduleRange }
            }

            // 終日は startDate <= endDate（終了日は排他のため同日可、逆転は不可）
            if (isDateSet) {
                ensure(startDate <= endDate) { EventError.ValidationError.InvalidScheduleRange }
            }

            return Schedule(startTime, endTime, startDate, endDate)
        }
    }
}

/**
 * リマインドタイミング VO（events.remind_timing の `interval`）。予定の何分前に通知するか。正の値のみ許す。
 * 列 null（= user_settings の既定値を参照）は本 VO の不在で表す。生成は [remindTiming] のみ。
 *
 * user BC の同名 VO とは別物（BC 間のモジュール直接依存は禁止のため再定義）。セマンティクスは共通。
 */
@JvmInline
value class RemindTiming private constructor(val duration: Duration) {
    companion object {
        fun Raise<EventError.ValidationError>.remindTiming(duration: Duration): RemindTiming {
            ensure(duration.isPositive()) { EventError.ValidationError.InvalidRemindTiming }
            return RemindTiming(duration)
        }
    }
}

/**
 * カレンダーの項目
 */
data class EventContent(
    val title: String?,
    val description: String?,
    val location: String?,
    val schedule: Schedule,
    val remindTiming: RemindTiming?,
)

/**
 * 予定集約ルート（events）。Google カレンダーの個別インスタンスを近未来ローリング窓へ投影したもの。
 * Source of Truth は Google 側で、本集約は自社ドメイン（混雑予測・目的地推定・リマインド）が
 * 消費するフィールドのみを保持する（繰り返しルール rrule はサーバに持たない）。
 *
 * 不変条件は VO（[GoogleEventId]/[TimeRange] 等）が単体で担保する。
 * title/description/location は Google が省略し得る（無題・概要なし等）ため null 許容。
 * 由来カレンダー [userCalendarUuid] は別 BC（user）を [UserCalendarUuid] で値参照する。
 */
@ConsistentCopyVisibility
data class Event private constructor(
    val eventUuid: EventUuid,
    val userCalendarUuid: UserCalendarUuid,
    val googleEventId: GoogleEventId,
    val recurringEventId: RecurringEventId?,
    val eventContent: EventContent,
) {
    /** 時刻を変更する（Google 側の時刻編集を反映）。下流の再推定契機（EventRescheduled）。 */
    fun reschedule(schedule: Schedule): Event {
        val eventContent = eventContent.copy(schedule = schedule)
        return copy(eventContent = eventContent)
    }

    /** リマインドタイミングを変更する。null は user_settings の既定値参照を表す。 */
    fun changeRemindTiming(remindTiming: RemindTiming?): Event {
        val eventContent = eventContent.copy(remindTiming = remindTiming)
        return copy(eventContent = eventContent)
    }

    companion object {
        /** 新規投影（新しい [EventUuid] を採番）。 */
        fun schedule(
            userCalendarUuid: UserCalendarUuid,
            googleEventId: GoogleEventId,
            recurringEventId: RecurringEventId?,
            eventContent: EventContent,
        ): Event = Event(
            eventUuid = EventUuid.new(),
            userCalendarUuid = userCalendarUuid,
            googleEventId = googleEventId,
            recurringEventId = recurringEventId,
            eventContent = eventContent,
        )

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        fun reconstitute(
            eventUuid: EventUuid,
            userCalendarUuid: UserCalendarUuid,
            googleEventId: GoogleEventId,
            recurringEventId: RecurringEventId?,
            eventContent: EventContent
        ): Event = Event(
            eventUuid = eventUuid,
            userCalendarUuid = userCalendarUuid,
            googleEventId = googleEventId,
            recurringEventId = recurringEventId,
            eventContent = eventContent,
        )
    }
}
