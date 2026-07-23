package com.crowdodge.notification.application.port

import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.shared.kernel.UserUuid
import kotlin.time.Instant

/** dispatch に必要な予定情報。読み取りは readmodel モジュールが実装する。 */
data class EventDispatchSource(
    val eventUuid: EventUuid,
    val title: String?,
    /** 開始時刻。終日予定は当日 0:00 JST を Instant 化した値。 */
    val start: Instant,
    val isAllDay: Boolean,
)

/** dispatch 用の BC 横断読み取り。実装は readmodel モジュール。 */
interface DispatchReadModelPort {
    suspend fun eventSources(eventUuids: List<EventUuid>): Map<EventUuid, EventDispatchSource>
    suspend fun fcmTokens(userUuids: List<UserUuid>): Map<UserUuid, List<String>>
}
