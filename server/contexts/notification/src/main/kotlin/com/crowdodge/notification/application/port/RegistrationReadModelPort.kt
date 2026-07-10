package com.crowdodge.notification.application.port

import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.shared.kernel.UserUuid
import kotlin.time.Duration
import kotlin.time.Instant

/** 通知スケジュール登録に必要な予定情報。owner（user_calendars）解決済み。読み取りは readmodel が実装。 */
data class EventRegistrationSource(
    val eventUuid: EventUuid,
    val userUuid: UserUuid,
    /** 開始時刻。終日予定は当日 0:00 JST を Instant 化した値。 */
    val start: Instant,
    /** 予定個別のリマインドタイミング。null は user 既定値参照。 */
    val remindTiming: Duration?,
    /** ユーザー既定のリマインドタイミング（user_settings）。未設定なら null。 */
    val defaultRemindTiming: Duration?,
)

/** 登録系（Register / Reschedule）用の BC 横断読み取り。実装は readmodel モジュール。 */
interface RegistrationReadModelPort {
    suspend fun registrationSources(eventUuids: List<EventUuid>): Map<EventUuid, EventRegistrationSource>
}
