package com.crowdodge.event.application.port

import com.crowdodge.event.domain.model.UserCalendarUuid
import kotlin.time.Instant

/**
 * 同期進捗（syncToken）の永続化ポート（被駆動）。実装は infrastructure（event_calendar_syncs）。
 *
 * syncToken は連携状態であり domain には属さないため、application のポートとして
 * 不透明な文字列で扱う。取り込み完了後にだけ前進させる（順序はユースケースが保証）。
 */
interface CalendarSyncProgressPort {
    /** 継続トークンを読む（未同期なら null＝初回フル同期）。 */
    suspend fun loadSyncToken(userCalendarUuid: UserCalendarUuid): String?

    /** 取り込み成功後に継続トークンを前進させる。 */
    suspend fun saveSyncToken(userCalendarUuid: UserCalendarUuid, syncToken: String?)

    /** 予定情報の取り込み期間の最大値を取得 */
    suspend fun materializedUntil(userCalendarUuid: UserCalendarUuid): Instant?
}
