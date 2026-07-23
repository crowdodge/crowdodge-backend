package com.crowdodge.event.domain.event

import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.shared.kernel.DomainEvent
import kotlin.time.Instant

/**
 * 予定が新規に投影された（§6.2 / §7）。下流（destination）が目的地推定を開始する契機。
 */
data class EventScheduled(
    val eventUuid: EventUuid,
    override val occurredAt: Instant,
) : DomainEvent

/**
 * 予定の時刻が変更された（§6.2 / §7）。下流（destination）がルート・混雑予測をやり直す契機。
 * 予測に効く内容（時刻・タイトル・概要・場所）の変更を含む。
 */
data class EventRescheduled(
    val eventUuid: EventUuid,
    override val occurredAt: Instant,
) : DomainEvent

/** notification BC がスケジュールを再計算する必要がある変更理由。 */
enum class NotificationTimingChangeReason {
    RemindTimingChanged,
    ScheduleChanged,
    ScheduleAndRemindTimingChanged,
}

/**
 * 通知時刻に影響する予定変更。通知 BC が通知を再スケジュールする契機。
 * [reason] が予定時刻の変更を含む場合は、混雑情報を即時に再計算・通知する。
 */
data class EventNotificationTimingChanged(
    val eventUuid: EventUuid,
    val reason: NotificationTimingChangeReason,
    override val occurredAt: Instant,
) : DomainEvent

/**
 * 予定が取り消された（Google 側削除・ローリング窓外への退避）（§6.2）。下流の後始末（通知取消等）の契機。
 */
data class EventCancelled(
    val eventUuid: EventUuid,
    override val occurredAt: Instant,
) : DomainEvent
