package com.crowdodge.event.domain.error

import com.crowdodge.shared.kernel.DomainError

/**
 * event BC の想定内ドメイン失敗（§10）。
 * presentation 層で Problem(RFC9457) に変換する。
 */
sealed interface EventError : DomainError {
    sealed interface ValidationError : EventError {
        val name: String

        /** Google イベント（インスタンス）ID が空。 */
        data object BlankGoogleEventId : ValidationError {
            override val name: String = "google_event_id"
            override val code: String = "blank-google-event-id"
        }

        /** Google シリーズ（マスタ）ID が空。 */
        data object BlankRecurringEventId : ValidationError {
            override val name: String = "recurring_event_id"
            override val code: String = "blank-recurring-event-id"
        }

        /** 開始/終了時刻が設定されていない。 */
        data object BlankSchedule : ValidationError {
            override val name: String = "schedule"
            override val code: String = "blank-schedule"
        }

        /** 予定の形式が不正（時刻指定 XOR 終日に反する＝片方だけ・時刻と日付の併存）。 */
        data object InvalidScheduleFormat : ValidationError {
            override val name: String = "schedule"
            override val code: String = "invalid-schedule-format"
        }

        /** 開始/終了が範囲をなさない（時刻指定は `start < end`、終日は `startDate < endDate` でない）。 */
        data object InvalidScheduleRange : ValidationError {
            override val name: String = "schedule"
            override val code: String = "invalid-schedule-range"
        }

        /** リマインドタイミングが不正（負値など）。 */
        data object InvalidRemindTiming : ValidationError {
            override val name: String = "remind_timing"
            override val code: String = "invalid-remind-timing"
        }
    }

    sealed interface ExternalError : EventError {
        data object GoogleCalendarError : ExternalError {
            override val code: String = "google-calendar-error"
        }
    }
}
