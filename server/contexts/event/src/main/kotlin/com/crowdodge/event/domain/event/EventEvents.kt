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
 */
data class EventRescheduled(
    val eventUuid: EventUuid,
    override val occurredAt: Instant,
) : DomainEvent

/**
 * 予定が取り消された（Google 側削除・ローリング窓外への退避）（§6.2）。下流の後始末（通知取消等）の契機。
 */
data class EventCancelled(
    val eventUuid: EventUuid,
    override val occurredAt: Instant,
) : DomainEvent
