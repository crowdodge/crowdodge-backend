package com.crowdodge.event.infrastructure.db.model

import com.crowdodge.event.domain.model.UserCalendarUuid
import kotlin.time.Instant

/**
 * `event_calendar_syncs` 行の infra DTO。Google Calendar 連携機構の状態であり業務不変条件を持たないため、
 * domain には属さず infrastructure に閉じる（domain repository IF は持たない）。
 *
 * per-user 同期: 1 [userCalendarUuid] につき 1 watch チャネル + 1 [syncToken]。
 * watch 系・syncToken・materializedUntil は連携の進捗で随時 null から埋まる/戻るため nullable。
 */
data class EventCalendarSync(
    val userCalendarUuid: UserCalendarUuid,
    val syncToken: String?,
    val materializedUntil: Instant?,
    val watchChannelId: String?,
    val watchResourceId: String?,
    val watchChannelToken: String?,
    val watchExpiration: Instant?,
)
