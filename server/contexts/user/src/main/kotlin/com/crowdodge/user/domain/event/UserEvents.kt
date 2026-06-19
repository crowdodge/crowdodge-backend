package com.crowdodge.user.domain.event

import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.UserId
import kotlin.time.Instant

/**
 * ユーザーが新規登録された（§6.1）。
 */
data class UserRegistered(
    val userId: UserId,
    override val occurredAt: Instant,
) : DomainEvent

/**
 * 混雑回避対象のカレンダー選択が変わった（§6.1）。
 */
data class CalendarSelectionChanged(
    val userId: UserId,
    override val occurredAt: Instant,
) : DomainEvent
